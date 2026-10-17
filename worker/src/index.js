/**
 * Octopus Live relay for Cloudflare Workers (free plan).
 *
 * The same idea as the server/ relay, with nothing to run at home: a single Durable Object
 * calls the Octopus API at most once per POLL_SECONDS and shares the latest reading with all
 * your devices.
 *
 *   GET /api/live?token=...   -> JSON (same format as the home-server relay)
 *   GET /?token=...           -> status page
 *   GET /health?token=...     -> "ok" / error
 *
 * Secrets:  OCTOPUS_API_KEY, RELAY_TOKEN
 * Vars:     OCTOPUS_ACCOUNT (optional), POLL_SECONDS (default 60), TZ (default Europe/London)
 */
import { DurableObject } from "cloudflare:workers";

const VERSION = "1.5-worker";
const GAS_MS = 30 * 60 * 1000;
const SYNC_MS = 30 * 60 * 1000;

// ---------------------------------------------------------------- entry point

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!env.RELAY_TOKEN) {
      return json({ ok: false, status: "RELAY_TOKEN secret is not set" }, 500);
    }
    const token = url.searchParams.get("token") || request.headers.get("X-Relay-Token") || "";
    if (!(await safeEqual(token, env.RELAY_TOKEN))) {
      return json({ ok: false, status: "Relay token required" }, 401);
    }
    const path = url.pathname.replace(/\/+$/, "");
    if (path === "" ) return new Response(PAGE, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
    if (path !== "/api/live" && path !== "/api" && path !== "/health") return new Response("Not found", { status: 404 });

    const stub = env.RELAY.get(env.RELAY.idFromName("main"));
    const data = await stub.latest();
    if (path === "/health") return new Response(data.ok ? "ok" : data.status, { status: data.ok ? 200 : 503 });
    return json(data);
  },
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" },
  });
}

async function safeEqual(a, b) {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(a)), crypto.subtle.digest("SHA-256", enc.encode(b))]);
  const x = new Uint8Array(ha), y = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

// ---------------------------------------------------------------- the shared relay

class ApiError extends Error {
  constructor(message, code) { super(message); this.code = code; }
}

export class Relay extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.env = env;
    this.s = null;          // persisted state (loaded lazily)
    this.inflight = null;   // one Octopus fetch at a time
  }

  get pollMs() { return Math.max(30, parseInt(this.env.POLL_SECONDS || "60", 10) || 60) * 1000; }
  get tz() { return this.env.TZ || "Europe/London"; }
  get apiUrl() { return this.env.OCTOPUS_API_URL || "https://api.octopus.energy/v1/graphql/"; }

  async load() {
    if (!this.s) this.s = (await this.ctx.storage.get("s")) || { data: { ok: false, status: "Starting", version: VERSION } };
    return this.s;
  }
  async save() { await this.ctx.storage.put("s", this.s); }

  /** Called by the Worker for every device request. */
  async latest() {
    const s = await this.load();
    const now = Date.now();
    const due = now - (s.lastFetch || 0) >= this.pollMs && now >= (s.nextAllowed || 0);
    if (due) {
      if (!this.inflight) this.inflight = this.refresh().finally(() => { this.inflight = null; });
      await this.inflight;
    }
    const d = { ...this.s.data };
    if (d.ok && now - (this.s.lastOk || 0) > Math.max(5 * 60 * 1000, this.pollMs * 4)) {
      d.ok = false; d.status = "Stale - can't reach Octopus";
    }
    return d;
  }

  async refresh() {
    const s = this.s;
    s.lastFetch = Date.now();
    try {
      if (!this.env.OCTOPUS_API_KEY) throw new ApiError("OCTOPUS_API_KEY secret is not set", "CONFIG");
      let elec;
      for (let attempt = 1; ; attempt++) {
        try {
          await this.ensureToken();
          await this.ensureMeters();
          elec = await this.fetchElec();
          break;
        } catch (e) {
          if (attempt === 1 && e instanceof ApiError && (e.code || "").startsWith("KT-CT-11") && !/rejected/.test(e.message)) {
            s.token = null; continue;   // expired token: retry once
          }
          throw e;
        }
      }
      await this.updateGas();
      s.failures = 0; s.nextAllowed = 0; s.lastOk = Date.now();
      s.data = {
        ok: true, status: "OK", version: VERSION,
        demandW: elec.demand, todayKwh: round(elec.todayWh / 1000, 3), readAt: elec.readAt,
        hasGas: !!s.gasId,
        gasKwh: s.gasId ? round(s.gasKwh ?? 0, 3) : null, gasAt: s.gasId ? s.gasAt ?? null : null,
        gasKw: s.gasId ? round(s.gasKw ?? 0, 2) : null, gasKwAt: s.gasId ? s.gasKwAt ?? null : null,
        updated: new Date().toISOString(), pollSeconds: this.pollMs / 1000,
      };
    } catch (e) {
      s.failures = (s.failures || 0) + 1;
      const msg = e instanceof ApiError ? e.message : `${e.name}: ${e.message}`;
      const backoff = /rejected|not set/.test(msg) ? 15 * 60 * 1000 : Math.min(this.pollMs * 2 ** Math.min(s.failures - 1, 4), 10 * 60 * 1000);
      s.nextAllowed = Date.now() + backoff;
      s.data = { ...(s.data || {}), ok: false, status: msg, version: VERSION, updated: new Date().toISOString() };
    }
    await this.save();
  }

  // ---------------- Octopus API

  async gql(query, auth = true) {
    const headers = { "Content-Type": "application/json", "User-Agent": `OctopusLive-Worker/${VERSION}` };
    if (auth && this.s.token) headers.Authorization = this.s.token;
    let res;
    try {
      res = await fetch(this.apiUrl, { method: "POST", headers, body: JSON.stringify({ query }) });
    } catch (e) {
      throw new ApiError(`Network error: ${e.message}`);
    }
    if (res.status === 429) throw new ApiError("Rate limited by Octopus", "RATE");
    let body;
    try { body = await res.json(); } catch { throw new ApiError(`HTTP ${res.status} from Octopus`); }
    const errors = body.errors || [];
    if (errors.length) {
      const code = errors[0].extensions?.errorCode;
      if (["KT-CT-1139", "KT-CT-1111", "KT-CT-1143"].includes(code)) throw new ApiError("API key rejected - check OCTOPUS_API_KEY", code);
      throw new ApiError(errors[0].message || "Octopus API error", code);
    }
    return body.data || {};
  }

  async ensureToken() {
    const s = this.s;
    if (s.token && Date.now() < (s.tokenExpires || 0)) return;
    s.token = null;
    const d = await this.gql(`mutation { obtainKrakenToken(input: { APIKey: "${clean(this.env.OCTOPUS_API_KEY)}" }) { token } }`, false);
    s.token = d.obtainKrakenToken.token;
    s.tokenExpires = Date.now() + 50 * 60 * 1000;
  }

  async ensureMeters() {
    const s = this.s;
    if (s.elecId && s.gasId !== undefined) return;
    let account = (this.env.OCTOPUS_ACCOUNT || "").trim();
    if (!account) {
      const d = await this.gql("query { viewer { accounts { number } } }");
      account = d.viewer?.accounts?.[0]?.number;
      if (!account) throw new ApiError("No account found - set OCTOPUS_ACCOUNT");
    }
    const d = await this.gql(`query { account(accountNumber: "${clean(account)}") {
      electricityAgreements(active: true) { meterPoint { direction meters(includeInactive: false) { smartImportElectricityMeter { deviceId } } } }
      gasAgreements(active: true) { meterPoint { meters(includeInactive: false) { smartGasMeter { deviceId } } } } } }`);
    const acc = d.account || {};
    let elec = null;
    for (const ag of acc.electricityAgreements || []) {
      if (ag.meterPoint?.direction === "EXPORT") continue;
      for (const m of ag.meterPoint?.meters || []) {
        if (m.smartImportElectricityMeter?.deviceId) { elec = m.smartImportElectricityMeter.deviceId; break; }
      }
      if (elec) break;
    }
    if (!elec) throw new ApiError("No smart electricity meter found on this account");
    let gas = "";
    for (const ag of acc.gasAgreements || []) {
      for (const m of ag.meterPoint?.meters || []) {
        if (m.smartGasMeter?.deviceId) { gas = m.smartGasMeter.deviceId; break; }
      }
      if (gas) break;
    }
    s.elecId = elec; s.gasId = gas;
  }

  /** One telemetry request per poll; today's total re-checked every 30 minutes. */
  async fetchElec() {
    const s = this.s;
    const { start, end, day } = dayBounds(this.tz);
    const id = clean(s.elecId);
    const needSync = s.syncDay !== day || s.syncMeterWh == null || Date.now() - (s.syncTime || 0) >= SYNC_MS;
    const todayPart = needSync
      ? `today: smartMeterTelemetry(deviceId: "${id}", grouping: HALF_HOURLY, start: "${start}", end: "${end}") { readAt consumptionDelta }`
      : "";
    const d = await this.gql(`query { live: smartMeterTelemetry(deviceId: "${id}") { readAt demand consumption } ${todayPart} }`);
    const live = (d.live || []).filter(Boolean).sort((a, b) => String(a.readAt).localeCompare(String(b.readAt))).pop();
    if (!live) throw new ApiError("No live reading - is the Home Mini online?");
    const meterWh = live.consumption != null ? Number(live.consumption) : null;

    let todayWh;
    if (needSync) {
      todayWh = (d.today || []).reduce((t, r) => t + (r && r.consumptionDelta != null ? Number(r.consumptionDelta) : 0), 0);
      Object.assign(s, { syncDay: day, syncTime: Date.now(), syncTodayWh: todayWh, syncMeterWh: meterWh });
    } else if (meterWh != null && meterWh >= s.syncMeterWh) {
      todayWh = s.syncTodayWh + (meterWh - s.syncMeterWh);
    } else {
      todayWh = s.lastTodayWh || 0;
      s.syncMeterWh = null;
    }
    s.lastTodayWh = todayWh;
    return { demand: live.demand != null ? Math.round(Number(live.demand)) : null, todayWh, readAt: live.readAt || null };
  }

  async updateGas() {
    const s = this.s;
    if (!s.gasId) { s.gasKwh = s.gasAt = s.gasKw = s.gasKwAt = null; return; }
    const { start, end, day } = dayBounds(this.tz);
    if (s.gasDay === day && Date.now() - (s.gasFetched || 0) < GAS_MS) return;
    try {
      const d = await this.gql(`query { gas: smartMeterTelemetry(deviceId: "${clean(s.gasId)}", grouping: HALF_HOURLY, start: "${start}", end: "${end}") { readAt consumptionDelta } }`);
      let wh = 0, last = null, latest = null;
      for (const r of d.gas || []) {
        if (!r || r.consumptionDelta == null) continue;
        const v = Number(r.consumptionDelta);
        wh += v;
        if (v > 0) last = r.readAt;
        if (!latest || String(r.readAt) >= String(latest.readAt)) latest = r;
      }
      s.gasKwh = wh / 1000;
      s.gasAt = last;
      s.gasKw = latest ? Number(latest.consumptionDelta) / 500 : 0;   // half-hour Wh -> average kW
      s.gasKwAt = latest ? latest.readAt : null;
      s.gasDay = day; s.gasFetched = Date.now();
    } catch {
      if (s.gasDay !== day) { s.gasKwh = 0; s.gasAt = null; s.gasKw = 0; s.gasKwAt = null; }
    }
  }
}

// ---------------------------------------------------------------- helpers

function clean(s) { return String(s).replace(/["\\\s]/g, ""); }
function round(x, n) { const f = 10 ** n; return Math.round(x * f) / f; }

/** Local midnight and now, as ISO strings with the zone's UTC offset, plus the local date. */
function dayBounds(timeZone) {
  const now = new Date();
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-GB", {
    timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
    hourCycle: "h23", timeZoneName: "longOffset",
  }).formatToParts(now).map((p) => [p.type, p.value]));
  const m = /GMT([+-]\d{2}):?(\d{2})?/.exec(parts.timeZoneName || "");
  const offset = m ? `${m[1]}:${m[2] || "00"}` : "+00:00";
  const day = `${parts.year}-${parts.month}-${parts.day}`;
  return { day, start: `${day}T00:00:00${offset}`, end: `${day}T${parts.hour}:${parts.minute}:${parts.second}${offset}` };
}

const PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Octopus Live</title>
<style>
:root{color-scheme:dark;--bg:#0b021d;--card:#160830;--line:#2b184e;--dim:#b8b2c8;--pink:#f050f8}
body{margin:0;background:var(--bg);color:#fff;font:15px system-ui,sans-serif;display:grid;place-items:center;min-height:100vh}
.c{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:22px 24px;width:min(340px,90vw)}
.t{color:var(--pink);font-weight:700;font-size:11px;letter-spacing:.1em}.w{font-size:44px;font-weight:900;margin:6px 0}
.r{display:flex;justify-content:space-between;color:var(--dim);margin-top:6px}.r b{color:#fff}.s{color:var(--dim);font-size:12px;margin-top:14px}
.b{display:flex;align-items:center;gap:8px;margin-top:8px;font-size:10px;font-weight:700;color:var(--dim)}
.b span{width:32px}.k{flex:1;height:6px;border-radius:3px;background:#ffffff38;overflow:hidden}.k i{display:block;height:100%;width:0;border-radius:3px;transition:width .6s}
</style></head><body><div class="c"><div class="t">OCTOPUS LIVE · CLOUD</div><div class="w" id="w">-- W</div>
<div class="b"><span>ELEC</span><div class="k"><i id="eb"></i></div></div>
<div class="b" id="gr"><span style="color:#ff8a3d">GAS</span><div class="k"><i id="gb" style="background:#ff8a3d"></i></div></div>
<div class="r" style="margin-top:14px">Today electricity<b id="e">--</b></div><div class="r">Today gas<b id="g">--</b></div>
<div class="s" id="s">Loading…</div></div><script>
const q=location.search;function hm(x){return x?new Date(x).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'}):'--:--'}
async function u(){try{const d=await (await fetch('api/live'+q)).json();const w=d.demandW;
const col=w==null?'#fff':w<500?'#00e676':w<=2000?'#ffb300':'#ff5252';const el=document.getElementById('w');
el.textContent=w==null?'-- W':(w>=1000?(w/1000).toFixed(2)+' kW':w+' W');el.style.color=col;
const eb=document.getElementById('eb');eb.style.background=col;eb.style.width=Math.min(100,(w||0)/40)+'%';
document.getElementById('gr').style.display=d.hasGas?'flex':'none';document.getElementById('gb').style.width=Math.min(100,(d.gasKw||0)*10)+'%';
document.getElementById('e').textContent=d.todayKwh==null?'--':d.todayKwh.toFixed(2)+' kWh';
document.getElementById('g').textContent=!d.hasGas?'no gas meter':(d.gasKwh??0).toFixed(2)+' kWh · '+(d.gasKw??0).toFixed(1)+' kW · '+hm(d.gasKwAt||d.gasAt);
document.getElementById('s').textContent=(d.ok?'● live':'⚠ '+d.status)+' · meter '+hm(d.readAt);}catch(e){document.getElementById('s').textContent='Not reachable';}}
u();setInterval(u,15000);</script></body></html>`;
