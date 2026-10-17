#!/usr/bin/env python3
"""
Octopus Live relay server.

Polls the Octopus Energy API once (on behalf of all your devices) and serves the latest
reading on your home network, so the Rainmeter skin and the Android app can read from here
instead of each calling Octopus themselves.

  GET /api/live   -> JSON with live demand, today's electricity and gas
  GET /           -> a small status page you can open in a browser

Python 3.8+ standard library only. Configure with environment variables (see octopus-relay.env).
"""

import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

API_URL = os.environ.get("OCTOPUS_API_URL", "https://api.octopus.energy/v1/graphql/")
API_KEY = os.environ.get("OCTOPUS_API_KEY", "").strip()
ACCOUNT = os.environ.get("OCTOPUS_ACCOUNT", "").strip()
POLL_SECONDS = max(30, int(os.environ.get("POLL_SECONDS", "60")))   # 1 telemetry request per poll
RESYNC_SECONDS = 30 * 60                                              # re-check today's total from half-hourly data
GAS_SECONDS = 30 * 60                                                 # gas meters report every 30 min
HOST = os.environ.get("RELAY_HOST", "0.0.0.0")
PORT = int(os.environ.get("RELAY_PORT", "8765"))
RELAY_TOKEN = os.environ.get("RELAY_TOKEN", "").strip()              # optional shared secret
VERSION = "1.5"


def log(msg):
    print(f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  {msg}", flush=True)


def iso(dt):
    return dt.isoformat(timespec="seconds") if dt else None


class ApiError(Exception):
    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


class Octopus:
    """Minimal Octopus GraphQL client with token + meter-ID caching."""

    def __init__(self, api_key, account):
        self.api_key = api_key
        self.account = account
        self.token = None
        self.token_expires = 0.0
        self.elec_id = None
        self.gas_id = None            # "" = no smart gas meter
        # "Today" for electricity is kept up to date from the meter's running total, so only the
        # live reading is fetched each poll; the half-hourly total is re-checked every 30 minutes.
        self.sync_day = None
        self.sync_time = 0.0
        self.sync_today_wh = 0.0
        self.sync_meter_wh = None
        self.last_today_wh = 0.0
        self.gas_kwh = None
        self.gas_at = None
        self.gas_kw = None            # average gas power over the latest half-hour
        self.gas_kw_at = None
        self.gas_day = None
        self.gas_fetched = 0.0

    @staticmethod
    def clean(s):
        return "".join(c for c in str(s) if c not in '"\\' and not c.isspace())

    def gql(self, query, auth=True):
        body = json.dumps({"query": query}).encode()
        req = urllib.request.Request(API_URL, data=body, method="POST", headers={
            "Content-Type": "application/json",
            "User-Agent": f"OctopusLive-Relay/{VERSION}",
            **({"Authorization": self.token} if auth and self.token else {}),
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            if e.code == 429:
                raise ApiError("Rate limited by Octopus", "RATE")
            try:
                data = json.loads(e.read().decode())
            except Exception:
                raise ApiError(f"HTTP {e.code} from Octopus")
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ApiError(f"Network error: {e}")
        errors = data.get("errors") or []
        if errors:
            code = (errors[0].get("extensions") or {}).get("errorCode")
            if code in ("KT-CT-1139", "KT-CT-1111", "KT-CT-1143"):
                raise ApiError("API key rejected - check OCTOPUS_API_KEY", code)
            raise ApiError(errors[0].get("message", "Octopus API error"), code)
        return data.get("data") or {}

    def ensure_token(self):
        if self.token and time.time() < self.token_expires:
            return
        self.token = None
        d = self.gql('mutation { obtainKrakenToken(input: { APIKey: "%s" }) { token } }' % self.clean(self.api_key), auth=False)
        self.token = d["obtainKrakenToken"]["token"]
        self.token_expires = time.time() + 50 * 60
        log("Got new API token")

    def ensure_meters(self):
        if self.elec_id is not None and self.gas_id is not None:
            return
        account = self.account
        if not account:
            d = self.gql("query { viewer { accounts { number } } }")
            accounts = (d.get("viewer") or {}).get("accounts") or []
            if not accounts:
                raise ApiError("No account found - set OCTOPUS_ACCOUNT")
            account = accounts[0]["number"]
        d = self.gql(
            'query { account(accountNumber: "%s") { '
            'electricityAgreements(active: true) { meterPoint { direction meters(includeInactive: false) { smartImportElectricityMeter { deviceId } } } } '
            'gasAgreements(active: true) { meterPoint { meters(includeInactive: false) { smartGasMeter { deviceId } } } } } }' % self.clean(account))
        acc = d.get("account") or {}
        elec = None
        for ag in acc.get("electricityAgreements") or []:
            mp = ag.get("meterPoint") or {}
            if mp.get("direction") == "EXPORT":
                continue
            for m in mp.get("meters") or []:
                dev = (m.get("smartImportElectricityMeter") or {}).get("deviceId")
                if dev:
                    elec = dev
                    break
            if elec:
                break
        if not elec:
            raise ApiError("No smart electricity meter found on this account")
        gas = ""
        for ag in acc.get("gasAgreements") or []:
            for m in (ag.get("meterPoint") or {}).get("meters") or []:
                dev = (m.get("smartGasMeter") or {}).get("deviceId")
                if dev:
                    gas = dev
                    break
            if gas:
                break
        self.elec_id, self.gas_id = elec, gas
        log(f"Account {account}: electricity meter found, gas meter {'found' if gas else 'not found'}")

    def fetch(self):
        """Returns (demand_w, today_kwh, read_at_dt). Raises ApiError."""
        for attempt in (1, 2):
            try:
                self.ensure_token()
                self.ensure_meters()
                return self._fetch_elec()
            except ApiError as e:
                if attempt == 1 and (e.code or "").startswith("KT-CT-11") and "rejected" not in str(e):
                    self.token = None   # expired token: get a new one and retry once
                    continue
                raise

    def _fetch_elec(self):
        now = datetime.now().astimezone()
        i = self.clean(self.elec_id)
        need_sync = (self.sync_day != now.date().isoformat() or self.sync_meter_wh is None
                     or time.time() - self.sync_time >= RESYNC_SECONDS)
        if need_sync:
            start = now.replace(hour=0, minute=0, second=0, microsecond=0)
            d = self.gql(
                'query { live: smartMeterTelemetry(deviceId: "%s") { readAt demand consumption } '
                'today: smartMeterTelemetry(deviceId: "%s", grouping: HALF_HOURLY, start: "%s", end: "%s") { readAt consumptionDelta } }'
                % (i, i, iso(start), iso(now)))
        else:
            d = self.gql('query { live: smartMeterTelemetry(deviceId: "%s") { readAt demand consumption } }' % i)
        live = [r for r in d.get("live") or [] if r]
        if not live:
            raise ApiError("No live reading - is the Home Mini online?")
        live = max(live, key=lambda r: r.get("readAt") or "")
        meter_wh = float(live["consumption"]) if live.get("consumption") is not None else None

        if need_sync:
            today_wh = sum(float(r["consumptionDelta"]) for r in d.get("today") or []
                           if r and r.get("consumptionDelta") is not None)
            self.sync_day, self.sync_time = now.date().isoformat(), time.time()
            self.sync_today_wh, self.sync_meter_wh = today_wh, meter_wh
        elif meter_wh is not None and meter_wh >= self.sync_meter_wh:
            today_wh = self.sync_today_wh + (meter_wh - self.sync_meter_wh)
        else:
            # Meter total missing or went backwards: keep the last figure, re-check next poll
            today_wh = self.last_today_wh
            self.sync_meter_wh = None
        self.last_today_wh = today_wh

        demand = live.get("demand")
        demand = round(float(demand)) if demand is not None else None
        read_at = parse_dt(live.get("readAt"))
        return demand, today_wh / 1000.0, read_at

    def update_gas(self):
        if not self.gas_id:
            self.gas_kwh, self.gas_at, self.gas_kw, self.gas_kw_at = None, None, None, None
            return
        now = datetime.now().astimezone()
        today = now.date().isoformat()
        if self.gas_day == today and time.time() - self.gas_fetched < GAS_SECONDS:
            return
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        try:
            d = self.gql(
                'query { gas: smartMeterTelemetry(deviceId: "%s", grouping: HALF_HOURLY, start: "%s", end: "%s") { readAt consumptionDelta } }'
                % (self.clean(self.gas_id), iso(start), iso(now)))
            wh, last, latest = 0.0, None, None
            for r in d.get("gas") or []:
                if not r or r.get("consumptionDelta") is None:
                    continue
                v = float(r["consumptionDelta"])
                wh += v
                if v > 0:
                    last = r.get("readAt")
                if latest is None or (r.get("readAt") or "") >= (latest.get("readAt") or ""):
                    latest = r
            self.gas_kwh = wh / 1000.0
            self.gas_at = parse_dt(last)
            # Half-hour Wh -> average kW over that half-hour (Wh / 1000 * 2)
            if latest is not None:
                self.gas_kw = float(latest["consumptionDelta"]) / 500.0
                self.gas_kw_at = parse_dt(latest.get("readAt"))
            else:
                self.gas_kw, self.gas_kw_at = 0.0, None
            self.gas_day = today
            self.gas_fetched = time.time()
        except ApiError as e:
            log(f"Gas: {e}")
            if self.gas_day != today:
                self.gas_kwh, self.gas_at, self.gas_kw, self.gas_kw_at = 0.0, None, 0.0, None


def parse_dt(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.data = {"status": "Starting", "ok": False, "version": VERSION}
        self.last_ok = 0.0

    def set(self, **kw):
        with self.lock:
            self.data.update(kw)

    def get(self):
        with self.lock:
            d = dict(self.data)
        # Flag stale data if polling has been failing for a while
        if d.get("ok") and time.time() - self.last_ok > max(5 * 60, POLL_SECONDS * 4):
            d["ok"] = False
            d["status"] = "Stale - relay can't reach Octopus"
        return d


STATE = State()


def poller(client):
    failures = 0
    while True:
        try:
            demand, today_kwh, read_at = client.fetch()
            client.update_gas()
            STATE.last_ok = time.time()
            STATE.set(ok=True, status="OK", demandW=demand, todayKwh=round(today_kwh, 3),
                      readAt=iso(read_at), gasKwh=None if client.gas_kwh is None else round(client.gas_kwh, 3),
                      gasAt=iso(client.gas_at), hasGas=bool(client.gas_id),
                      gasKw=None if client.gas_kw is None else round(client.gas_kw, 2), gasKwAt=iso(client.gas_kw_at),
                      updated=iso(datetime.now(timezone.utc)), pollSeconds=POLL_SECONDS)
            if failures:
                log("Recovered")
            failures = 0
            delay = POLL_SECONDS
        except Exception as e:   # keep running whatever happens
            failures += 1
            msg = str(e) if isinstance(e, ApiError) else f"{type(e).__name__}: {e}"
            log(f"Fetch failed ({failures}): {msg}")
            STATE.set(ok=False, status=msg, updated=iso(datetime.now(timezone.utc)))
            if isinstance(e, ApiError) and "rejected" in msg:
                delay = 15 * 60            # bad key: don't hammer the API
            else:
                delay = min(POLL_SECONDS * (2 ** min(failures - 1, 4)), 10 * 60)
        time.sleep(delay)


PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Octopus Live relay</title>
<style>
:root{color-scheme:dark;--bg:#0b021d;--card:#160830;--line:#2b184e;--dim:#b8b2c8;--pink:#f050f8}
body{margin:0;background:var(--bg);color:#fff;font:15px system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}
.c{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:22px 24px;width:min(340px,90vw)}
.t{color:var(--pink);font-weight:700;font-size:11px;letter-spacing:.1em}.w{font-size:44px;font-weight:900;margin:6px 0}
.r{display:flex;justify-content:space-between;color:var(--dim);margin-top:6px}
.b{display:flex;align-items:center;gap:8px;margin-top:8px;font-size:10px;font-weight:700;color:var(--dim)}
.b span{width:32px}.k{flex:1;height:6px;border-radius:3px;background:#ffffff38;overflow:hidden}.k i{display:block;height:100%;width:0;border-radius:3px;transition:width .6s}.r b{color:#fff}.s{color:var(--dim);font-size:12px;margin-top:14px}
</style></head><body><div class="c"><div class="t">OCTOPUS LIVE · RELAY</div><div class="w" id="w">-- W</div>
<div class="b"><span>ELEC</span><div class="k"><i id="eb"></i></div></div>
<div class="b" id="gr"><span style="color:#ff8a3d">GAS</span><div class="k"><i id="gb" style="background:#ff8a3d"></i></div></div>
<div class="r" style="margin-top:14px">Today electricity<b id="e">--</b></div><div class="r">Today gas<b id="g">--</b></div>
<div class="s" id="s">Loading…</div></div><script>
const q=location.search;function hm(x){return x?new Date(x).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}):'--:--'}
async function u(){try{const d=await (await fetch('api/live'+q)).json();const w=d.demandW;
document.getElementById('w').textContent=w==null?'-- W':(w>=1000?(w/1000).toFixed(2)+' kW':w+' W');
const col=w==null?'#fff':w<500?'#00e676':w<=2000?'#ffb300':'#ff5252';document.getElementById('w').style.color=col;
const eb=document.getElementById('eb');eb.style.background=col;eb.style.width=Math.min(100,(w||0)/40)+'%';
document.getElementById('gr').style.display=d.hasGas?'flex':'none';document.getElementById('gb').style.width=Math.min(100,(d.gasKw||0)*10)+'%';
document.getElementById('e').textContent=d.todayKwh==null?'--':d.todayKwh.toFixed(2)+' kWh';
document.getElementById('g').textContent=!d.hasGas?'no gas meter':(d.gasKwh??0).toFixed(2)+' kWh · '+(d.gasKw??0).toFixed(1)+' kW · '+hm(d.gasKwAt||d.gasAt);
document.getElementById('s').textContent=(d.ok?'● live':'⚠ '+d.status)+' · meter '+hm(d.readAt);}catch(e){document.getElementById('s').textContent='Relay not reachable';}}
u();setInterval(u,10000);</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = f"OctopusLiveRelay/{VERSION}"

    def log_message(self, fmt, *args):
        pass   # keep the journal quiet

    def _send(self, code, body, ctype):
        b = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        url = urlparse(self.path)
        if RELAY_TOKEN:
            token = (parse_qs(url.query).get("token") or [""])[0] or self.headers.get("X-Relay-Token", "")
            if not hmac.compare_digest(token.encode(), RELAY_TOKEN.encode()):
                return self._send(401, json.dumps({"ok": False, "status": "Relay token required"}), "application/json")
        path = url.path.rstrip("/")
        if path in ("/api/live", "/api"):
            return self._send(200, json.dumps(STATE.get()), "application/json")
        if path == "":
            return self._send(200, PAGE, "text/html; charset=utf-8")
        if path == "/health":
            d = STATE.get()
            return self._send(200 if d.get("ok") else 503, "ok" if d.get("ok") else d.get("status", "error"), "text/plain")
        self._send(404, "Not found", "text/plain")


def main():
    if not API_KEY:
        print("OCTOPUS_API_KEY is not set. Put it in /etc/octopus-relay.env", file=sys.stderr)
        sys.exit(2)
    client = Octopus(API_KEY, ACCOUNT)
    threading.Thread(target=poller, args=(client,), daemon=True).start()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    log(f"Octopus Live relay {VERSION} on http://{HOST}:{PORT}  (polling every {POLL_SECONDS}s)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
