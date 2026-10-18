/**
 * Octopus Live relay for Cloudflare Workers (free plan).
 *
 * The same idea as the server/ relay, with nothing to run at home: a single Durable Object
 * calls the Octopus API at most once per POLL_SECONDS and shares the latest reading with all
 * your devices.
 *
 *   GET  /                    -> web page: first-time setup, live reading, device links, settings
 *   GET  /api/live?token=...  -> JSON (same format as the home-server relay)
 *   GET  /api/setup/state     -> whether it's set up, and whether the given token is valid
 *   POST /api/setup           -> first-time setup: {apiKey, account?} -> {token}  (only when not set up)
 *   POST /api/settings        -> change key / make a new token (needs the token)
 *
 * Set up by opening the Worker's address in a browser: the API key and token are saved in the
 * Durable Object. Older deployments that use the OCTOPUS_API_KEY / RELAY_TOKEN / OCTOPUS_ACCOUNT
 * secrets keep working until settings are changed on the page.
 * Optional secret ADMIN_TOKEN: always accepted, to get back in if you lose your link.
 * Vars: POLL_SECONDS (default 60), TZ (default Europe/London)
 */
import { DurableObject } from "cloudflare:workers";

const VERSION = "1.7-worker";
const GAS_MS = 30 * 60 * 1000;
const SYNC_MS = 30 * 60 * 1000;
const GAS_ON_WH = 100;   // a half-hour with at least this much gas (0.2 kW average) counts as "on"

// ---------------------------------------------------------------- entry point

export default {
  async fetch(request, env) {
    // Everything is handled by the one Durable Object, which holds the settings and the latest reading
    return env.RELAY.get(env.RELAY.idFromName("main")).fetch(request);
  },
};

const HTML_HEADERS = {
  "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store",
  "X-Frame-Options": "DENY", "Referrer-Policy": "no-referrer",
};

function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...extra },
  });
}

const COOKIE = "ol_session";
const SESSION_DAYS = 30;
const MIN_PASSWORD = 8;
const MAX_USERNAME = 64;
const PBKDF2_ROUNDS = 100000;   // the most Cloudflare Workers allow

function cookie(request, name) {
  const m = new RegExp("(?:^|;\\s*)" + name + "=([^;]+)").exec(request.headers.get("Cookie") || "");
  return m ? m[1] : "";
}

const hex = (buf) => Array.from(new Uint8Array(buf), (x) => x.toString(16).padStart(2, "0")).join("");
const unhex = (h) => new Uint8Array(h.match(/../g).map((x) => parseInt(x, 16)));

async function pbkdf2(password, salt, rounds) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  return crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations: rounds }, key, 256);
}
async function hashPassword(password) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  return `pbkdf2$${PBKDF2_ROUNDS}$${hex(salt)}$${hex(await pbkdf2(password, salt, PBKDF2_ROUNDS))}`;
}
async function verifyPassword(password, stored) {
  try {
    const [, rounds, salt, want] = stored.split("$");
    return safeEqual(hex(await pbkdf2(password, unhex(salt), parseInt(rounds, 10))), want);
  } catch { return false; }
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

  // ---------------- settings (API key, account, token)

  async cfg() {
    if (this.c === undefined) {
      this.c = (await this.ctx.storage.get("cfg")) || null;
      // Older deployments: settings came from secrets
      if (!this.c && this.env.OCTOPUS_API_KEY) {
        this.c = { apiKey: this.env.OCTOPUS_API_KEY, account: this.env.OCTOPUS_ACCOUNT || "", token: this.env.RELAY_TOKEN || "", fromEnv: true };
      }
    }
    return this.c;
  }

  async authorized(token) {
    const c = await this.cfg();
    if (!c) return false;
    if (this.env.ADMIN_TOKEN && await safeEqual(token, this.env.ADMIN_TOKEN)) return true;
    if (!c.token) return true;
    return safeEqual(token, c.token);
  }

  async isAdmin(token) {
    return !!(this.env.ADMIN_TOKEN && token && (await safeEqual(token, this.env.ADMIN_TOKEN)));
  }

  async saveCfg(c) {
    this.c = { apiKey: c.apiKey, account: c.account || "", token: c.token, username: c.username || "", passwordHash: c.passwordHash || "" };
    await this.ctx.storage.put("cfg", this.c);
    // New key or account: start again with a fresh Octopus login and meter look-up
    const s = await this.load();
    Object.assign(s, { token: null, tokenExpires: 0, elecId: null, gasId: undefined, accountFound: null,
      syncDay: null, gasDay: null, lastFetch: 0, nextAllowed: 0, failures: 0,
      data: { ok: false, status: "Starting", version: VERSION } });
    await this.save();
  }

  async saveLogin(username, password) {
    this.c = { ...this.c };
    if (username) this.c.username = username;
    if (password) this.c.passwordHash = await hashPassword(password);
    delete this.c.fromEnv;
    await this.ctx.storage.put("cfg", this.c);
  }

  // ---------------- signed-in browsers

  async sessions() {
    if (!this.sess) this.sess = (await this.ctx.storage.get("sess")) || {};
    return this.sess;
  }
  async newSession() {
    const all = await this.sessions();
    const now = Date.now();
    for (const k of Object.keys(all)) if (all[k] < now) delete all[k];
    const sid = randomToken() + randomToken();
    all[sid] = now + SESSION_DAYS * 86400 * 1000;
    await this.ctx.storage.put("sess", all);
    return sid;
  }
  async signedIn(request) {
    const sid = cookie(request, COOKIE);
    if (!sid || !(await this.cfg())) return false;
    return ((await this.sessions())[sid] || 0) > Date.now();
  }
  signInResponse(obj, sid) {
    return json(obj, 200, { "Set-Cookie": `${COOKIE}=${sid}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${SESSION_DAYS * 86400}` });
  }

  /** Tries a key with Octopus (without touching the live state). Returns the account number. */
  async checkKey(apiKey, account) {
    if (!/^sk_/.test(apiKey)) throw new ApiError("That doesn't look like an Octopus API key (it starts with sk_live_)");
    const probe = Object.create(this);
    probe.s = { };
    probe.c = { apiKey, account };
    await probe.ensureToken();
    await probe.ensureMeters();
    return probe.s.accountFound;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");
    const token = url.searchParams.get("token") || request.headers.get("X-Relay-Token") || "";
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    const c = await this.cfg();

    if (request.method === "GET" && (path === "" || path === "/setup")) return new Response(PAGE, { headers: HTML_HEADERS });

    if (request.method === "GET" && path === "/api/setup/state") {
      const signed = await this.signedIn(request);
      const out = {
        configured: !!c, hasPassword: !!(c && c.passwordHash), signedIn: signed,
        tokenValid: !!c && (await this.authorized(token)), kind: "cloud", version: VERSION, persistent: true,
      };
      // May set a (new) password: no password yet and a valid device link, or the ADMIN_TOKEN recovery secret
      out.canReset = !!c && ((!c.passwordHash && out.tokenValid) || (await this.isAdmin(token)));
      if (signed) {
        const d = (await this.load()).data || {};
        Object.assign(out, { token: c.token, account: c.account || this.s.accountFound || "", username: c.username || "",
          keyOk: !!d.ok || d.status === "Starting", lastError: d.ok ? null : d.status });
      }
      return json(out);
    }

    if (request.method === "POST") {
      let body = {};
      try { body = await request.json(); } catch { return json({ status: "Bad request" }, 400); }
      const apiKey = String(body.apiKey || "").trim();
      const account = String(body.account || "").trim().toUpperCase();
      const password = String(body.password || "");
      const username = String(body.username || "").trim();
      if (username.length > MAX_USERNAME) return json({ status: `Use a username of up to ${MAX_USERNAME} characters` }, 400);

      if (path === "/api/setup") {
        if (c) return json({ status: "This relay is already set up" }, 409);
        if (!username) return json({ status: "Choose a username" }, 400);
        if (password.length < MIN_PASSWORD) return json({ status: `Choose a password of at least ${MIN_PASSWORD} characters` }, 400);
        let found;
        try { found = await this.checkKey(apiKey, account); } catch (e) { return json({ status: e.message }, 400); }
        const tok = randomToken();
        await this.saveCfg({ apiKey, account, token: tok, username, passwordHash: await hashPassword(password) });
        return this.signInResponse({ token: tok, account: found }, await this.newSession());
      }

      if (path === "/api/login") {
        // 5 wrong passwords from one address, or 20 in total, locks sign-in for 15 minutes
        const f = this.failures || (this.failures = new Map());
        const fresh = (r) => r && Date.now() - r.first < 15 * 60 * 1000;
        if ((fresh(f.get(ip)) && f.get(ip).n >= 5) || (fresh(f.get("*")) && f.get("*").n >= 20)) {
          return json({ status: "Too many attempts. Try again in 15 minutes." }, 429);
        }
        // Username is case-insensitive; relays set up before usernames existed accept any (the first sign-in saves it)
        const pwOk = !!c && !!c.passwordHash && (await verifyPassword(password, c.passwordHash));
        const userOk = !!c && (!c.username || (await safeEqual(username.toLowerCase(), c.username.toLowerCase())));
        if (!pwOk || !userOk) {
          for (const key of [ip, "*"]) {
            const r = fresh(f.get(key)) ? f.get(key) : { n: 0, first: Date.now() };
            r.n++; f.set(key, r);
          }
          await new Promise((res) => setTimeout(res, 1000));
          return json({ status: "Wrong username or password" }, 401);
        }
        f.delete(ip);
        if (!c.username && username) await this.saveLogin(username, null);
        return this.signInResponse({ ok: true }, await this.newSession());
      }

      if (path === "/api/logout") {
        const sid = cookie(request, COOKIE);
        const all = await this.sessions();
        if (sid && all[sid]) { delete all[sid]; await this.ctx.storage.put("sess", all); }
        return json({ ok: true }, 200, { "Set-Cookie": `${COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0` });
      }

      if (path === "/api/password") {
        const allowed = (await this.signedIn(request)) || (!!c && !c.passwordHash && (await this.authorized(token))) ||
          (!!c && (await this.isAdmin(token)));
        if (!allowed) return json({ status: "Sign in first" }, 401);
        // Signed in: either may change (blank password keeps the current one). Otherwise both are needed.
        const needPw = !c.passwordHash || !!password || (await this.isAdmin(token) && !(await this.signedIn(request)));
        if (!username && !c.username) return json({ status: "Choose a username" }, 400);
        if (needPw && password.length < MIN_PASSWORD) return json({ status: `Use a password of at least ${MIN_PASSWORD} characters` }, 400);
        await this.saveLogin(username, password);
        this.sess = {}; await this.ctx.storage.put("sess", {});   // sign out everywhere
        return this.signInResponse({ ok: true }, await this.newSession());
      }

      if (path === "/api/settings") {
        if (!(await this.signedIn(request))) return json({ status: "Sign in first" }, 401);
        let found = null;
        const next = { ...c };
        delete next.fromEnv;
        if (apiKey) {
          try { found = await this.checkKey(apiKey, account); } catch (e) { return json({ status: e.message }, 400); }
          next.apiKey = apiKey; next.account = account;
        }
        if (body.rotateToken || !next.token) next.token = randomToken();
        if (apiKey) await this.saveCfg(next);
        else { this.c = next; await this.ctx.storage.put("cfg", next); }
        return json({ token: next.token, account: found });
      }
      return json({ status: "Not found" }, 404);
    }

    if (!c) return json({ ok: false, status: "Not set up yet - open this relay's web page" }, 503);
    if (!((await this.authorized(token)) || (await this.signedIn(request)))) return json({ ok: false, status: "Relay token required" }, 401);
    if (path !== "/api/live" && path !== "/api" && path !== "/health") return new Response("Not found", { status: 404 });
    const data = await this.latest();
    if (path === "/health") return new Response(data.ok ? "ok" : data.status, { status: data.ok ? 200 : 503 });
    return json(data);
  }

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
      if (!(await this.cfg())) throw new ApiError("Not set up yet - open this relay's web page", "CONFIG");
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
        gasRunMinutes: s.gasId ? s.gasRunMin || 0 : 0, gasRunSince: s.gasId ? s.gasRunSince ?? null : null,
        updated: new Date().toISOString(), pollSeconds: this.pollMs / 1000,
      };
    } catch (e) {
      s.failures = (s.failures || 0) + 1;
      const msg = e instanceof ApiError ? e.message : `${e.name}: ${e.message}`;
      const backoff = /rejected|not set up/i.test(msg) ? 15 * 60 * 1000 : Math.min(this.pollMs * 2 ** Math.min(s.failures - 1, 4), 10 * 60 * 1000);
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
      if (["KT-CT-1139", "KT-CT-1111", "KT-CT-1143"].includes(code)) throw new ApiError("API key rejected - check your Octopus API key", code);
      throw new ApiError(errors[0].message || "Octopus API error", code);
    }
    return body.data || {};
  }

  async ensureToken() {
    const s = this.s;
    if (s.token && Date.now() < (s.tokenExpires || 0)) return;
    s.token = null;
    const d = await this.gql(`mutation { obtainKrakenToken(input: { APIKey: "${clean(this.c.apiKey)}" }) { token } }`, false);
    s.token = d.obtainKrakenToken.token;
    s.tokenExpires = Date.now() + 50 * 60 * 1000;
  }

  async ensureMeters() {
    const s = this.s;
    if (s.elecId && s.gasId !== undefined) return;
    let account = (this.c.account || "").trim();
    if (!account) {
      const d = await this.gql("query { viewer { accounts { number } } }");
      account = d.viewer?.accounts?.[0]?.number;
      if (!account) throw new ApiError("No account found for this API key");
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
    s.elecId = elec; s.gasId = gas; s.accountFound = account;
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
    if (!s.gasId) { s.gasKwh = s.gasAt = s.gasKw = s.gasKwAt = null; s.gasRunMin = 0; s.gasRunSince = null; return; }
    const { start, end, day } = dayBounds(this.tz);
    if (s.gasDay === day && Date.now() - (s.gasFetched || 0) < GAS_MS) return;
    const midnight = Date.parse(start);
    // Start 3 hours before midnight so a run that began yesterday evening is counted
    const from = new Date(midnight - 3 * 3600 * 1000).toISOString();
    try {
      const d = await this.gql(`query { gas: smartMeterTelemetry(deviceId: "${clean(s.gasId)}", grouping: HALF_HOURLY, start: "${from}", end: "${end}") { readAt consumptionDelta } }`);
      const rows = (d.gas || [])
        .filter((r) => r && r.consumptionDelta != null && !isNaN(Date.parse(r.readAt)))
        .map((r) => ({ t: Date.parse(r.readAt), at: r.readAt, v: Number(r.consumptionDelta) }))
        .sort((a, b) => a.t - b.t);
      const today = rows.filter((r) => r.t >= midnight);
      s.gasKwh = today.reduce((t, r) => t + r.v, 0) / 1000;
      const used = today.filter((r) => r.v > 0);
      s.gasAt = used.length ? used[used.length - 1].at : null;
      const latest = rows[rows.length - 1];
      s.gasKw = latest ? latest.v / 500 : 0;          // half-hour Wh -> average kW
      s.gasKwAt = latest ? latest.at : null;
      let n = 0;                                      // consecutive latest half-hours with gas on
      for (let i = rows.length - 1; i >= 0 && rows[i].v >= GAS_ON_WH; i--) n++;
      s.gasRunMin = n * 30;
      s.gasRunSince = n ? rows[rows.length - n].at : null;
      s.gasDay = day; s.gasFetched = Date.now();
    } catch {
      if (s.gasDay !== day) { s.gasKwh = 0; s.gasAt = null; s.gasKw = 0; s.gasKwAt = null; s.gasRunMin = 0; s.gasRunSince = null; }
    }
  }
}

// ---------------------------------------------------------------- helpers

function randomToken() {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

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

// BEGIN PAGE (generated by tools/build_pages.py from web/relay.html - edit that file, not this)
const PAGE = "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n<meta name=\"robots\" content=\"noindex\">\n<meta name=\"referrer\" content=\"no-referrer\">\n<title>Octopus Live</title>\n<style>\n:root{\n  --bg:#f6f6f8;--card:#fff;--line:#e9e9ee;--text:#111114;--text2:#5c5c66;--muted:#8e8e99;\n  --input:#f6f6f8;--inputLine:#e0e0e6;--pink:#e8478b;--pinkDark:#c93373;\n  --green:#1fa463;--amber:#e69a00;--red:#e5484d;--gas:#f07a2a;--gasBg:#f07a2a14;--warnBg:#e5484d14;\n  color-scheme:light dark;\n}\n@media (prefers-color-scheme:dark){:root{\n  --bg:#0f0f12;--card:#19191e;--line:#26262d;--text:#f4f4f6;--text2:#b4b4be;--muted:#7e7e89;\n  --input:#141418;--inputLine:#2e2e36;--green:#3dd68c;--amber:#ffb224;--red:#ff6369;--gas:#ff8f4a;--gasBg:#ff8f4a24;--warnBg:#ff636924;\n}}\n*{box-sizing:border-box}\nhtml,body{margin:0;background:var(--bg);color:var(--text);\n  font:15px/1.45 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif;-webkit-font-smoothing:antialiased}\nmain{max-width:560px;margin:0 auto;padding:28px 20px 48px}\nheader{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:24px}\n.brand{display:flex;align-items:center;gap:10px;font-weight:600;font-size:19px}\n.logo{width:30px;height:30px;border-radius:9px;background:var(--pink);display:grid;place-items:center}\n.logo svg{width:18px;height:18px;fill:#fff}\n.chip{display:inline-flex;align-items:center;gap:8px;height:30px;padding:0 12px;border-radius:99px;\n  background:var(--card);border:1px solid var(--line);color:var(--text2);font-size:13px;font-weight:500;white-space:nowrap}\n.dot{width:8px;height:8px;border-radius:50%;background:var(--muted)}\n.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px;margin-top:12px}\n.section{margin:28px 4px 8px;font-size:13px;font-weight:600;color:var(--text2)}\nh1{font-size:26px;line-height:1.2;margin:0 0 8px;font-weight:600;letter-spacing:-.01em}\nh2{font-size:17px;margin:0 0 4px;font-weight:600}\np{margin:0;color:var(--text2)}\n.small{font-size:13px;color:var(--muted)}\nlabel{display:block;font-size:13px;font-weight:600;color:var(--text2);margin:18px 0 6px}\ninput{width:100%;height:48px;padding:0 14px;border-radius:12px;border:1px solid var(--inputLine);background:var(--input);\n  color:var(--text);font:14px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;outline:none}\ninput[autocomplete=username]{font:15px inherit;font-family:inherit}\ninput:focus{border:2px solid var(--pink);padding:0 13px;background:var(--card)}\n.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;height:48px;padding:0 20px;border-radius:14px;\n  border:0;background:var(--pink);color:#fff;font:600 15px system-ui,sans-serif;cursor:pointer;text-decoration:none;width:100%}\n.btn:hover{background:var(--pinkDark)}\n.btn[disabled]{opacity:.6;cursor:default}\n.btn.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}\n.btn.ghost:hover{background:var(--input)}\n.btn.danger{background:transparent;color:var(--red);border:1px solid var(--line)}\n.btn.small{height:36px;padding:0 14px;font-size:13px;border-radius:10px;width:auto}\n.row{display:flex;gap:10px;align-items:center}\n.row input{flex:1}\n.err{color:var(--red);font-size:13px;margin-top:10px;min-height:1px}\n.ok{color:var(--green)}\na.link{color:var(--pink);font-weight:600;font-size:13px;text-decoration:none}\ndetails summary{cursor:pointer;color:var(--text2);font-size:13px;font-weight:600;margin-top:16px;list-style:none}\ndetails summary::-webkit-details-marker{display:none}\ndetails summary:before{content:\"+ \"}details[open] summary:before{content:\"\u2013 \"}\n.hero{padding:4px 4px 0}\n.hero .label{font-size:13px;color:var(--text2)}\n.watts{font-size:56px;font-weight:600;letter-spacing:-.02em;line-height:1.05;margin:2px 0 4px}\n.bar{height:6px;border-radius:3px;background:var(--line);overflow:hidden;margin-top:14px}\n.bar i{display:block;height:100%;width:0;border-radius:3px;transition:width .6s,background .6s}\n.tiles{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:20px}\n.tiles .card{margin:0;padding:16px}\n.tile-v{font-size:22px;font-weight:600;margin-top:4px}\n.banner{display:flex;gap:12px;align-items:center;padding:14px;border-radius:14px;margin-top:16px;font-weight:500;font-size:14px}\n.banner.gas{background:var(--gasBg)}.banner.info{background:var(--card);border:1px solid var(--line)}\n.banner.warn{background:var(--warnBg)}\n.qr{display:flex;gap:18px;align-items:center;margin-top:16px}\n.qr .code{background:#fff;padding:10px;border-radius:12px;border:1px solid var(--line);line-height:0;flex:none}\n.qr .code svg{width:132px;height:132px;display:block}\n.code-line{font:12px ui-monospace,Menlo,Consolas,monospace;background:var(--input);border:1px solid var(--inputLine);\n  border-radius:10px;padding:10px 12px;overflow-wrap:anywhere;color:var(--text2)}\n.divider{height:1px;background:var(--line);margin:18px -20px}\n.toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%) translateY(20px);background:var(--text);color:var(--bg);\n  padding:10px 16px;border-radius:12px;font-size:14px;font-weight:500;opacity:0;transition:.25s;pointer-events:none}\n.toast.show{opacity:1;transform:translateX(-50%) translateY(0)}\n.center{text-align:center}\n.lock{width:52px;height:52px;border-radius:16px;background:var(--input);display:grid;place-items:center;margin:0 auto 16px}\n.lock svg{width:24px;height:24px;fill:var(--muted)}\nfooter{margin-top:32px;text-align:center;font-size:12px;color:var(--muted)}\nfooter a{color:var(--muted)}\n.hidden{display:none!important}\n@media (max-width:420px){.qr{flex-direction:column;align-items:flex-start}}\n</style>\n</head>\n<body>\n<main>\n  <header>\n    <div class=\"brand\"><div class=\"logo\"><svg viewBox=\"0 0 24 24\"><path d=\"M13 2 4 14h7l-1 8 10-13h-7z\"/></svg></div>Octopus Live</div>\n    <div class=\"chip\" id=\"chip\"><span class=\"dot\" id=\"chipDot\"></span><span id=\"chipText\">Loading\u2026</span></div>\n  </header>\n\n  <!-- Loading -->\n  <section id=\"vLoading\"><p class=\"small\">Loading\u2026</p></section>\n\n  <!-- First-time setup -->\n  <section id=\"vSetup\" class=\"hidden\">\n    <h1>Set up your relay</h1>\n    <p>Paste your Octopus API key. It's stored on this <span class=\"kind\">relay</span> and only ever sent to Octopus Energy.</p>\n    <div class=\"card\">\n      <form id=\"setupForm\" autocomplete=\"off\">\n        <label for=\"setupKey\" style=\"margin-top:0\">Octopus API key</label>\n        <input id=\"setupKey\" type=\"password\" placeholder=\"sk_live_\u2026\" spellcheck=\"false\" autocapitalize=\"off\" required>\n        <div style=\"margin-top:8px\"><a class=\"link\" href=\"https://octopus.energy/dashboard/new/accounts/personal-details/api-access\" target=\"_blank\" rel=\"noopener\">Find your API key \u2197</a></div>\n        <label for=\"setupUser\">Choose a username</label>\n        <input id=\"setupUser\" type=\"text\" autocomplete=\"username\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" maxlength=\"64\" required>\n        <label for=\"setupPw\">Choose a password</label>\n        <input id=\"setupPw\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" required placeholder=\"At least 8 characters\">\n        <label for=\"setupPw2\">Confirm password</label>\n        <input id=\"setupPw2\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" required>\n        <details><summary>More options</summary>\n          <label for=\"setupAccount\">Account number (only if you have more than one)</label>\n          <input id=\"setupAccount\" placeholder=\"A-1234ABCD\" spellcheck=\"false\" autocapitalize=\"characters\">\n        </details>\n        <div class=\"err\" id=\"setupErr\"></div>\n        <button class=\"btn\" id=\"setupBtn\" style=\"margin-top:16px\">Connect</button>\n      </form>\n    </div>\n    <p class=\"small\" style=\"margin:14px 4px 0\">You'll sign in to this page with your username and password. Your phone and PC get their own device link, which can only read your usage.</p>\n  </section>\n\n  <!-- Sign in -->\n  <section id=\"vLogin\" class=\"hidden\">\n    <h1>Sign in</h1>\n    <p>Sign in to manage this relay.</p>\n    <div class=\"card\">\n      <form id=\"loginForm\">\n        <label for=\"loginUser\" style=\"margin-top:0\">Username</label>\n        <input id=\"loginUser\" type=\"text\" autocomplete=\"username\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" maxlength=\"64\" required>\n        <label for=\"loginPw\">Password</label>\n        <input id=\"loginPw\" type=\"password\" autocomplete=\"current-password\" required>\n        <div class=\"err\" id=\"loginErr\"></div>\n        <button class=\"btn\" id=\"loginBtn\" style=\"margin-top:16px\">Sign in</button>\n      </form>\n    </div>\n    <p class=\"small\" style=\"margin:14px 4px 0\">Forgot your details? See \u201cForgot your password\u201d in the <a href=\"https://github.com/ruwaid-7/octopus-live#forgot-your-password\" target=\"_blank\" rel=\"noopener\" style=\"color:inherit\">setup guide</a>.</p>\n  </section>\n\n  <!-- Older relay or after a reset: create a password (needs the device link) -->\n  <section id=\"vNewPw\" class=\"hidden\">\n    <h1>Create your sign-in</h1>\n    <p>From now on you'll sign in to this page with a username and password. Your devices keep working as they are.</p>\n    <div class=\"card\">\n      <form id=\"newPwForm\">\n        <label for=\"newUser\" style=\"margin-top:0\">Username</label>\n        <input id=\"newUser\" type=\"text\" autocomplete=\"username\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" maxlength=\"64\" required>\n        <label for=\"newPw1\">Password</label>\n        <input id=\"newPw1\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" required placeholder=\"At least 8 characters\">\n        <label for=\"newPw2\">Confirm password</label>\n        <input id=\"newPw2\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" required>\n        <div class=\"err\" id=\"newPwErr\"></div>\n        <button class=\"btn\" id=\"newPwBtn\" style=\"margin-top:16px\">Save</button>\n      </form>\n    </div>\n  </section>\n\n  <!-- Opened from the QR code on a phone -->\n  <section id=\"vConnect\" class=\"hidden\">\n    <h1>Connect this phone</h1>\n    <p>Tap the button to add this relay to the Octopus Live app.</p>\n    <div class=\"card\">\n      <a class=\"btn\" id=\"connectBtn\" href=\"#\">Open in Octopus Live</a>\n      <p class=\"small\" style=\"margin-top:14px\">Don't have the app yet? Get it from <a class=\"link\" href=\"https://github.com/ruwaid-7/octopus-live/releases/latest\" target=\"_blank\" rel=\"noopener\">GitHub</a>, then come back to this page.</p>\n    </div>\n  </section>\n\n  <!-- Set up, but no (valid) link -->\n  <section id=\"vLocked\" class=\"hidden\">\n    <div class=\"card center\" style=\"padding:32px 24px\">\n      <div class=\"lock\"><svg viewBox=\"0 0 24 24\"><path d=\"M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zm-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm3.1-9H8.9V6a3.1 3.1 0 0 1 6.2 0z\"/></svg></div>\n      <h2>This relay is private</h2>\n      <p style=\"margin-top:6px\">Open your device link to create a username and password for this page.</p>\n      <p class=\"small\" style=\"margin-top:14px\">Forgot your details? See \u201cForgot your password\u201d in the <a href=\"https://github.com/ruwaid-7/octopus-live#forgot-your-password\" target=\"_blank\" rel=\"noopener\" style=\"color:inherit\">setup guide</a>.</p>\n    </div>\n  </section>\n\n  <!-- Dashboard -->\n  <section id=\"vMain\" class=\"hidden\">\n    <div class=\"banner info hidden\" id=\"notice\" style=\"margin:0 0 24px\"></div>\n\n    <div class=\"hero\">\n      <div class=\"label\">Using now</div>\n      <div class=\"watts\" id=\"watts\">\u2014</div>\n      <div class=\"small\" id=\"wattsSub\"></div>\n      <div class=\"bar\"><i id=\"bar\"></i></div>\n    </div>\n\n    <div class=\"banner gas hidden\" id=\"gasBanner\">\n      <svg viewBox=\"0 0 24 24\" width=\"20\" height=\"20\" style=\"fill:var(--gas);flex:none\"><path d=\"M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-4.8 4.8z\"/></svg>\n      <span id=\"gasText\"></span>\n    </div>\n\n    <div class=\"tiles\">\n      <div class=\"card\"><p class=\"small\">Electricity today</p><div class=\"tile-v\" id=\"elecToday\">\u2014</div></div>\n      <div class=\"card\" id=\"gasTile\"><p class=\"small\">Gas today</p><div class=\"tile-v\" id=\"gasToday\">\u2014</div><p class=\"small\" id=\"gasSub\"></p></div>\n    </div>\n\n    <div class=\"section\">Connect your devices</div>\n    <div class=\"card\">\n      <h2>Phone</h2>\n      <p>On your Android phone, tap the button. On a computer, scan the code with your phone. The app fills everything in for you.</p>\n      <a class=\"btn\" id=\"openApp\" style=\"margin-top:14px\" href=\"#\">Open in Octopus Live</a>\n      <div class=\"qr\" id=\"qrRow\">\n        <div class=\"code\" id=\"qr\"></div>\n        <p class=\"small\">Scan with your phone's camera, then tap <b>Open in Octopus Live</b>. No sign-in needed on the phone.</p>\n      </div>\n      <div class=\"divider\"></div>\n      <h2>Device link</h2>\n      <p>For the app's Relay address, Home Assistant or anything else. It can only read your usage.</p>\n      <div class=\"row\" style=\"margin-top:12px\">\n        <div class=\"code-line\" id=\"link\" style=\"flex:1\"></div>\n        <button class=\"btn ghost small\" data-copy=\"link\">Copy</button>\n      </div>\n      <div class=\"divider\"></div>\n      <h2>Windows (Rainmeter)</h2>\n      <p>Put this line in <code>Settings.inc</code>, then refresh the skin.</p>\n      <div class=\"row\" style=\"margin-top:12px\">\n        <div class=\"code-line\" id=\"rainmeter\" style=\"flex:1\"></div>\n        <button class=\"btn ghost small\" data-copy=\"rainmeter\">Copy</button>\n      </div>\n    </div>\n\n    <div class=\"section\">Settings</div>\n    <div class=\"card\">\n      <h2>Octopus API key</h2>\n      <p id=\"keyStatus\">\u2014</p>\n      <button class=\"btn ghost small\" id=\"changeKeyBtn\" style=\"margin-top:12px\">Change key</button>\n      <form id=\"keyForm\" class=\"hidden\" autocomplete=\"off\" style=\"margin-top:4px\">\n        <label for=\"newKey\">New API key</label>\n        <input id=\"newKey\" type=\"password\" placeholder=\"sk_live_\u2026\" spellcheck=\"false\" autocapitalize=\"off\">\n        <label for=\"newAccount\">Account number (optional)</label>\n        <input id=\"newAccount\" placeholder=\"A-1234ABCD\" spellcheck=\"false\" autocapitalize=\"characters\">\n        <div class=\"err\" id=\"keyErr\"></div>\n        <div class=\"row\" style=\"margin-top:14px\">\n          <button class=\"btn\" id=\"saveKeyBtn\" type=\"submit\" style=\"flex:1\">Save</button>\n          <button class=\"btn ghost\" id=\"cancelKeyBtn\" type=\"button\" style=\"flex:1\">Cancel</button>\n        </div>\n      </form>\n      <div class=\"divider\"></div>\n      <h2>Sign-in</h2>\n      <p>Signed in as <strong id=\"whoami\"></strong>. Changing your details signs out every browser.</p>\n      <button class=\"btn ghost small\" id=\"changePwBtn\" style=\"margin-top:12px\">Change username or password</button>\n      <form id=\"pwForm\" class=\"hidden\" autocomplete=\"off\" style=\"margin-top:4px\">\n        <label for=\"pwUser\">Username</label>\n        <input id=\"pwUser\" type=\"text\" autocomplete=\"username\" autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\" maxlength=\"64\" required>\n        <label for=\"pw1\">New password</label>\n        <input id=\"pw1\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" placeholder=\"Leave blank to keep your current one\">\n        <label for=\"pw2\">Confirm new password</label>\n        <input id=\"pw2\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\">\n        <div class=\"err\" id=\"pwErr\"></div>\n        <div class=\"row\" style=\"margin-top:14px\">\n          <button class=\"btn\" id=\"savePwBtn\" type=\"submit\" style=\"flex:1\">Save</button>\n          <button class=\"btn ghost\" id=\"cancelPwBtn\" type=\"button\" style=\"flex:1\">Cancel</button>\n        </div>\n      </form>\n      <div class=\"divider\"></div>\n      <h2>Device link</h2>\n      <p>If your device link was shared by mistake, make a new one. Devices using the old link stop working until you give them the new one.</p>\n      <button class=\"btn danger small\" id=\"rotateBtn\" style=\"margin-top:12px\">Make a new device link</button>\n      <div class=\"err\" id=\"rotateErr\"></div>\n    </div>\n    <div class=\"card\" style=\"display:flex;align-items:center;justify-content:space-between;gap:12px\">\n      <p>Signed in on this browser</p>\n      <button class=\"btn ghost small\" id=\"logoutBtn\">Sign out</button>\n    </div>\n    <p class=\"small hidden\" id=\"persistWarn\" style=\"margin:12px 4px 0\"></p>\n  </section>\n\n  <footer><span id=\"ver\"></span> \u00b7 Unofficial, not affiliated with Octopus Energy \u00b7 <a href=\"https://github.com/ruwaid-7/octopus-live\" target=\"_blank\" rel=\"noopener\">GitHub</a></footer>\n</main>\n<div class=\"toast\" id=\"toast\"></div>\n\n<script>\n// qrcode-generator by Kazuhiko Arase - MIT License - https://github.com/kazuhikoarase/qrcode-generator\n// qrcode-generator by Kazuhiko Arase - MIT License - https://github.com/kazuhikoarase/qrcode-generator\n// URL: http://www.d-project.com/\n//  http://www.opensource.org/licenses/mit-license.php\n//  http://www.denso-wave.com/qrcode/faqpatent-e.html\nvar qrcode = function() {\n/**\n* qrcode\n* @param typeNumber 1 to 40\n* @param errorCorrectionLevel 'L','M','Q','H'\n*/\nvar qrcode = function(typeNumber, errorCorrectionLevel) {\nvar PAD0 = 0xEC;\nvar PAD1 = 0x11;\nvar _typeNumber = typeNumber;\nvar _errorCorrectionLevel = QRErrorCorrectionLevel[errorCorrectionLevel];\nvar _modules = null;\nvar _moduleCount = 0;\nvar _dataCache = null;\nvar _dataList = [];\nvar _this = {};\nvar makeImpl = function(test, maskPattern) {\n_moduleCount = _typeNumber * 4 + 17;\n_modules = function(moduleCount) {\nvar modules = new Array(moduleCount);\nfor (var row = 0; row < moduleCount; row += 1) {\nmodules[row] = new Array(moduleCount);\nfor (var col = 0; col < moduleCount; col += 1) {\nmodules[row][col] = null;\n}\n}\nreturn modules;\n}(_moduleCount);\nsetupPositionProbePattern(0, 0);\nsetupPositionProbePattern(_moduleCount - 7, 0);\nsetupPositionProbePattern(0, _moduleCount - 7);\nsetupPositionAdjustPattern();\nsetupTimingPattern();\nsetupTypeInfo(test, maskPattern);\nif (_typeNumber >= 7) {\nsetupTypeNumber(test);\n}\nif (_dataCache == null) {\n_dataCache = createData(_typeNumber, _errorCorrectionLevel, _dataList);\n}\nmapData(_dataCache, maskPattern);\n};\nvar setupPositionProbePattern = function(row, col) {\nfor (var r = -1; r <= 7; r += 1) {\nif (row + r <= -1 || _moduleCount <= row + r) continue;\nfor (var c = -1; c <= 7; c += 1) {\nif (col + c <= -1 || _moduleCount <= col + c) continue;\nif ( (0 <= r && r <= 6 && (c == 0 || c == 6) )\n|| (0 <= c && c <= 6 && (r == 0 || r == 6) )\n|| (2 <= r && r <= 4 && 2 <= c && c <= 4) ) {\n_modules[row + r][col + c] = true;\n} else {\n_modules[row + r][col + c] = false;\n}\n}\n}\n};\nvar getBestMaskPattern = function() {\nvar minLostPoint = 0;\nvar pattern = 0;\nfor (var i = 0; i < 8; i += 1) {\nmakeImpl(true, i);\nvar lostPoint = QRUtil.getLostPoint(_this);\nif (i == 0 || minLostPoint > lostPoint) {\nminLostPoint = lostPoint;\npattern = i;\n}\n}\nreturn pattern;\n};\nvar setupTimingPattern = function() {\nfor (var r = 8; r < _moduleCount - 8; r += 1) {\nif (_modules[r][6] != null) {\ncontinue;\n}\n_modules[r][6] = (r % 2 == 0);\n}\nfor (var c = 8; c < _moduleCount - 8; c += 1) {\nif (_modules[6][c] != null) {\ncontinue;\n}\n_modules[6][c] = (c % 2 == 0);\n}\n};\nvar setupPositionAdjustPattern = function() {\nvar pos = QRUtil.getPatternPosition(_typeNumber);\nfor (var i = 0; i < pos.length; i += 1) {\nfor (var j = 0; j < pos.length; j += 1) {\nvar row = pos[i];\nvar col = pos[j];\nif (_modules[row][col] != null) {\ncontinue;\n}\nfor (var r = -2; r <= 2; r += 1) {\nfor (var c = -2; c <= 2; c += 1) {\nif (r == -2 || r == 2 || c == -2 || c == 2\n|| (r == 0 && c == 0) ) {\n_modules[row + r][col + c] = true;\n} else {\n_modules[row + r][col + c] = false;\n}\n}\n}\n}\n}\n};\nvar setupTypeNumber = function(test) {\nvar bits = QRUtil.getBCHTypeNumber(_typeNumber);\nfor (var i = 0; i < 18; i += 1) {\nvar mod = (!test && ( (bits >> i) & 1) == 1);\n_modules[Math.floor(i / 3)][i % 3 + _moduleCount - 8 - 3] = mod;\n}\nfor (var i = 0; i < 18; i += 1) {\nvar mod = (!test && ( (bits >> i) & 1) == 1);\n_modules[i % 3 + _moduleCount - 8 - 3][Math.floor(i / 3)] = mod;\n}\n};\nvar setupTypeInfo = function(test, maskPattern) {\nvar data = (_errorCorrectionLevel << 3) | maskPattern;\nvar bits = QRUtil.getBCHTypeInfo(data);\nfor (var i = 0; i < 15; i += 1) {\nvar mod = (!test && ( (bits >> i) & 1) == 1);\nif (i < 6) {\n_modules[i][8] = mod;\n} else if (i < 8) {\n_modules[i + 1][8] = mod;\n} else {\n_modules[_moduleCount - 15 + i][8] = mod;\n}\n}\nfor (var i = 0; i < 15; i += 1) {\nvar mod = (!test && ( (bits >> i) & 1) == 1);\nif (i < 8) {\n_modules[8][_moduleCount - i - 1] = mod;\n} else if (i < 9) {\n_modules[8][15 - i - 1 + 1] = mod;\n} else {\n_modules[8][15 - i - 1] = mod;\n}\n}\n_modules[_moduleCount - 8][8] = (!test);\n};\nvar mapData = function(data, maskPattern) {\nvar inc = -1;\nvar row = _moduleCount - 1;\nvar bitIndex = 7;\nvar byteIndex = 0;\nvar maskFunc = QRUtil.getMaskFunction(maskPattern);\nfor (var col = _moduleCount - 1; col > 0; col -= 2) {\nif (col == 6) col -= 1;\nwhile (true) {\nfor (var c = 0; c < 2; c += 1) {\nif (_modules[row][col - c] == null) {\nvar dark = false;\nif (byteIndex < data.length) {\ndark = ( ( (data[byteIndex] >>> bitIndex) & 1) == 1);\n}\nvar mask = maskFunc(row, col - c);\nif (mask) {\ndark = !dark;\n}\n_modules[row][col - c] = dark;\nbitIndex -= 1;\nif (bitIndex == -1) {\nbyteIndex += 1;\nbitIndex = 7;\n}\n}\n}\nrow += inc;\nif (row < 0 || _moduleCount <= row) {\nrow -= inc;\ninc = -inc;\nbreak;\n}\n}\n}\n};\nvar createBytes = function(buffer, rsBlocks) {\nvar offset = 0;\nvar maxDcCount = 0;\nvar maxEcCount = 0;\nvar dcdata = new Array(rsBlocks.length);\nvar ecdata = new Array(rsBlocks.length);\nfor (var r = 0; r < rsBlocks.length; r += 1) {\nvar dcCount = rsBlocks[r].dataCount;\nvar ecCount = rsBlocks[r].totalCount - dcCount;\nmaxDcCount = Math.max(maxDcCount, dcCount);\nmaxEcCount = Math.max(maxEcCount, ecCount);\ndcdata[r] = new Array(dcCount);\nfor (var i = 0; i < dcdata[r].length; i += 1) {\ndcdata[r][i] = 0xff & buffer.getBuffer()[i + offset];\n}\noffset += dcCount;\nvar rsPoly = QRUtil.getErrorCorrectPolynomial(ecCount);\nvar rawPoly = qrPolynomial(dcdata[r], rsPoly.getLength() - 1);\nvar modPoly = rawPoly.mod(rsPoly);\necdata[r] = new Array(rsPoly.getLength() - 1);\nfor (var i = 0; i < ecdata[r].length; i += 1) {\nvar modIndex = i + modPoly.getLength() - ecdata[r].length;\necdata[r][i] = (modIndex >= 0)? modPoly.getAt(modIndex) : 0;\n}\n}\nvar totalCodeCount = 0;\nfor (var i = 0; i < rsBlocks.length; i += 1) {\ntotalCodeCount += rsBlocks[i].totalCount;\n}\nvar data = new Array(totalCodeCount);\nvar index = 0;\nfor (var i = 0; i < maxDcCount; i += 1) {\nfor (var r = 0; r < rsBlocks.length; r += 1) {\nif (i < dcdata[r].length) {\ndata[index] = dcdata[r][i];\nindex += 1;\n}\n}\n}\nfor (var i = 0; i < maxEcCount; i += 1) {\nfor (var r = 0; r < rsBlocks.length; r += 1) {\nif (i < ecdata[r].length) {\ndata[index] = ecdata[r][i];\nindex += 1;\n}\n}\n}\nreturn data;\n};\nvar createData = function(typeNumber, errorCorrectionLevel, dataList) {\nvar rsBlocks = QRRSBlock.getRSBlocks(typeNumber, errorCorrectionLevel);\nvar buffer = qrBitBuffer();\nfor (var i = 0; i < dataList.length; i += 1) {\nvar data = dataList[i];\nbuffer.put(data.getMode(), 4);\nbuffer.put(data.getLength(), QRUtil.getLengthInBits(data.getMode(), typeNumber) );\ndata.write(buffer);\n}\nvar totalDataCount = 0;\nfor (var i = 0; i < rsBlocks.length; i += 1) {\ntotalDataCount += rsBlocks[i].dataCount;\n}\nif (buffer.getLengthInBits() > totalDataCount * 8) {\nthrow 'code length overflow. ('\n+ buffer.getLengthInBits()\n+ '>'\n+ totalDataCount * 8\n+ ')';\n}\nif (buffer.getLengthInBits() + 4 <= totalDataCount * 8) {\nbuffer.put(0, 4);\n}\nwhile (buffer.getLengthInBits() % 8 != 0) {\nbuffer.putBit(false);\n}\nwhile (true) {\nif (buffer.getLengthInBits() >= totalDataCount * 8) {\nbreak;\n}\nbuffer.put(PAD0, 8);\nif (buffer.getLengthInBits() >= totalDataCount * 8) {\nbreak;\n}\nbuffer.put(PAD1, 8);\n}\nreturn createBytes(buffer, rsBlocks);\n};\n_this.addData = function(data, mode) {\nmode = mode || 'Byte';\nvar newData = null;\nswitch(mode) {\ncase 'Numeric' :\nnewData = qrNumber(data);\nbreak;\ncase 'Alphanumeric' :\nnewData = qrAlphaNum(data);\nbreak;\ncase 'Byte' :\nnewData = qr8BitByte(data);\nbreak;\ncase 'Kanji' :\nnewData = qrKanji(data);\nbreak;\ndefault :\nthrow 'mode:' + mode;\n}\n_dataList.push(newData);\n_dataCache = null;\n};\n_this.isDark = function(row, col) {\nif (row < 0 || _moduleCount <= row || col < 0 || _moduleCount <= col) {\nthrow row + ',' + col;\n}\nreturn _modules[row][col];\n};\n_this.getModuleCount = function() {\nreturn _moduleCount;\n};\n_this.make = function() {\nif (_typeNumber < 1) {\nvar typeNumber = 1;\nfor (; typeNumber < 40; typeNumber++) {\nvar rsBlocks = QRRSBlock.getRSBlocks(typeNumber, _errorCorrectionLevel);\nvar buffer = qrBitBuffer();\nfor (var i = 0; i < _dataList.length; i++) {\nvar data = _dataList[i];\nbuffer.put(data.getMode(), 4);\nbuffer.put(data.getLength(), QRUtil.getLengthInBits(data.getMode(), typeNumber) );\ndata.write(buffer);\n}\nvar totalDataCount = 0;\nfor (var i = 0; i < rsBlocks.length; i++) {\ntotalDataCount += rsBlocks[i].dataCount;\n}\nif (buffer.getLengthInBits() <= totalDataCount * 8) {\nbreak;\n}\n}\n_typeNumber = typeNumber;\n}\nmakeImpl(false, getBestMaskPattern() );\n};\n_this.createTableTag = function(cellSize, margin) {\ncellSize = cellSize || 2;\nmargin = (typeof margin == 'undefined')? cellSize * 4 : margin;\nvar qrHtml = '';\nqrHtml += '<table style=\"';\nqrHtml += ' border-width: 0px; border-style: none;';\nqrHtml += ' border-collapse: collapse;';\nqrHtml += ' padding: 0px; margin: ' + margin + 'px;';\nqrHtml += '\">';\nqrHtml += '<tbody>';\nfor (var r = 0; r < _this.getModuleCount(); r += 1) {\nqrHtml += '<tr>';\nfor (var c = 0; c < _this.getModuleCount(); c += 1) {\nqrHtml += '<td style=\"';\nqrHtml += ' border-width: 0px; border-style: none;';\nqrHtml += ' border-collapse: collapse;';\nqrHtml += ' padding: 0px; margin: 0px;';\nqrHtml += ' width: ' + cellSize + 'px;';\nqrHtml += ' height: ' + cellSize + 'px;';\nqrHtml += ' background-color: ';\nqrHtml += _this.isDark(r, c)? '#000000' : '#ffffff';\nqrHtml += ';';\nqrHtml += '\"/>';\n}\nqrHtml += '</tr>';\n}\nqrHtml += '</tbody>';\nqrHtml += '</table>';\nreturn qrHtml;\n};\n_this.createSvgTag = function(cellSize, margin, alt, title) {\nvar opts = {};\nif (typeof arguments[0] == 'object') {\nopts = arguments[0];\ncellSize = opts.cellSize;\nmargin = opts.margin;\nalt = opts.alt;\ntitle = opts.title;\n}\ncellSize = cellSize || 2;\nmargin = (typeof margin == 'undefined')? cellSize * 4 : margin;\nalt = (typeof alt === 'string') ? {text: alt} : alt || {};\nalt.text = alt.text || null;\nalt.id = (alt.text) ? alt.id || 'qrcode-description' : null;\ntitle = (typeof title === 'string') ? {text: title} : title || {};\ntitle.text = title.text || null;\ntitle.id = (title.text) ? title.id || 'qrcode-title' : null;\nvar size = _this.getModuleCount() * cellSize + margin * 2;\nvar c, mc, r, mr, qrSvg='', rect;\nrect = 'l' + cellSize + ',0 0,' + cellSize +\n' -' + cellSize + ',0 0,-' + cellSize + 'z ';\nqrSvg += '<svg version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\"';\nqrSvg += !opts.scalable ? ' width=\"' + size + 'px\" height=\"' + size + 'px\"' : '';\nqrSvg += ' viewBox=\"0 0 ' + size + ' ' + size + '\" ';\nqrSvg += ' preserveAspectRatio=\"xMinYMin meet\"';\nqrSvg += (title.text || alt.text) ? ' role=\"img\" aria-labelledby=\"' +\nescapeXml([title.id, alt.id].join(' ').trim() ) + '\"' : '';\nqrSvg += '>';\nqrSvg += (title.text) ? '<title id=\"' + escapeXml(title.id) + '\">' +\nescapeXml(title.text) + '</title>' : '';\nqrSvg += (alt.text) ? '<description id=\"' + escapeXml(alt.id) + '\">' +\nescapeXml(alt.text) + '</description>' : '';\nqrSvg += '<rect width=\"100%\" height=\"100%\" fill=\"white\" cx=\"0\" cy=\"0\"/>';\nqrSvg += '<path d=\"';\nfor (r = 0; r < _this.getModuleCount(); r += 1) {\nmr = r * cellSize + margin;\nfor (c = 0; c < _this.getModuleCount(); c += 1) {\nif (_this.isDark(r, c) ) {\nmc = c*cellSize+margin;\nqrSvg += 'M' + mc + ',' + mr + rect;\n}\n}\n}\nqrSvg += '\" stroke=\"transparent\" fill=\"black\"/>';\nqrSvg += '</svg>';\nreturn qrSvg;\n};\n_this.createDataURL = function(cellSize, margin) {\ncellSize = cellSize || 2;\nmargin = (typeof margin == 'undefined')? cellSize * 4 : margin;\nvar size = _this.getModuleCount() * cellSize + margin * 2;\nvar min = margin;\nvar max = size - margin;\nreturn createDataURL(size, size, function(x, y) {\nif (min <= x && x < max && min <= y && y < max) {\nvar c = Math.floor( (x - min) / cellSize);\nvar r = Math.floor( (y - min) / cellSize);\nreturn _this.isDark(r, c)? 0 : 1;\n} else {\nreturn 1;\n}\n} );\n};\n_this.createImgTag = function(cellSize, margin, alt) {\ncellSize = cellSize || 2;\nmargin = (typeof margin == 'undefined')? cellSize * 4 : margin;\nvar size = _this.getModuleCount() * cellSize + margin * 2;\nvar img = '';\nimg += '<img';\nimg += '\\u0020src=\"';\nimg += _this.createDataURL(cellSize, margin);\nimg += '\"';\nimg += '\\u0020width=\"';\nimg += size;\nimg += '\"';\nimg += '\\u0020height=\"';\nimg += size;\nimg += '\"';\nif (alt) {\nimg += '\\u0020alt=\"';\nimg += escapeXml(alt);\nimg += '\"';\n}\nimg += '/>';\nreturn img;\n};\nvar escapeXml = function(s) {\nvar escaped = '';\nfor (var i = 0; i < s.length; i += 1) {\nvar c = s.charAt(i);\nswitch(c) {\ncase '<': escaped += '&lt;'; break;\ncase '>': escaped += '&gt;'; break;\ncase '&': escaped += '&amp;'; break;\ncase '\"': escaped += '&quot;'; break;\ndefault : escaped += c; break;\n}\n}\nreturn escaped;\n};\nvar _createHalfASCII = function(margin) {\nvar cellSize = 1;\nmargin = (typeof margin == 'undefined')? cellSize * 2 : margin;\nvar size = _this.getModuleCount() * cellSize + margin * 2;\nvar min = margin;\nvar max = size - margin;\nvar y, x, r1, r2, p;\nvar blocks = {\n'\u2588\u2588': '\u2588',\n'\u2588 ': '\u2580',\n' \u2588': '\u2584',\n'  ': ' '\n};\nvar blocksLastLineNoMargin = {\n'\u2588\u2588': '\u2580',\n'\u2588 ': '\u2580',\n' \u2588': ' ',\n'  ': ' '\n};\nvar ascii = '';\nfor (y = 0; y < size; y += 2) {\nr1 = Math.floor((y - min) / cellSize);\nr2 = Math.floor((y + 1 - min) / cellSize);\nfor (x = 0; x < size; x += 1) {\np = '\u2588';\nif (min <= x && x < max && min <= y && y < max && _this.isDark(r1, Math.floor((x - min) / cellSize))) {\np = ' ';\n}\nif (min <= x && x < max && min <= y+1 && y+1 < max && _this.isDark(r2, Math.floor((x - min) / cellSize))) {\np += ' ';\n}\nelse {\np += '\u2588';\n}\nascii += (margin < 1 && y+1 >= max) ? blocksLastLineNoMargin[p] : blocks[p];\n}\nascii += '\\n';\n}\nif (size % 2 && margin > 0) {\nreturn ascii.substring(0, ascii.length - size - 1) + Array(size+1).join('\u2580');\n}\nreturn ascii.substring(0, ascii.length-1);\n};\n_this.createASCII = function(cellSize, margin) {\ncellSize = cellSize || 1;\nif (cellSize < 2) {\nreturn _createHalfASCII(margin);\n}\ncellSize -= 1;\nmargin = (typeof margin == 'undefined')? cellSize * 2 : margin;\nvar size = _this.getModuleCount() * cellSize + margin * 2;\nvar min = margin;\nvar max = size - margin;\nvar y, x, r, p;\nvar white = Array(cellSize+1).join('\u2588\u2588');\nvar black = Array(cellSize+1).join('  ');\nvar ascii = '';\nvar line = '';\nfor (y = 0; y < size; y += 1) {\nr = Math.floor( (y - min) / cellSize);\nline = '';\nfor (x = 0; x < size; x += 1) {\np = 1;\nif (min <= x && x < max && min <= y && y < max && _this.isDark(r, Math.floor((x - min) / cellSize))) {\np = 0;\n}\nline += p ? white : black;\n}\nfor (r = 0; r < cellSize; r += 1) {\nascii += line + '\\n';\n}\n}\nreturn ascii.substring(0, ascii.length-1);\n};\n_this.renderTo2dContext = function(context, cellSize) {\ncellSize = cellSize || 2;\nvar length = _this.getModuleCount();\nfor (var row = 0; row < length; row++) {\nfor (var col = 0; col < length; col++) {\ncontext.fillStyle = _this.isDark(row, col) ? 'black' : 'white';\ncontext.fillRect(col * cellSize, row * cellSize, cellSize, cellSize);\n}\n}\n}\nreturn _this;\n};\nqrcode.stringToBytesFuncs = {\n'default' : function(s) {\nvar bytes = [];\nfor (var i = 0; i < s.length; i += 1) {\nvar c = s.charCodeAt(i);\nbytes.push(c & 0xff);\n}\nreturn bytes;\n}\n};\nqrcode.stringToBytes = qrcode.stringToBytesFuncs['default'];\n/**\n* @param unicodeData base64 string of byte array.\n* [16bit Unicode],[16bit Bytes], ...\n* @param numChars\n*/\nqrcode.createStringToBytes = function(unicodeData, numChars) {\nvar unicodeMap = function() {\nvar bin = base64DecodeInputStream(unicodeData);\nvar read = function() {\nvar b = bin.read();\nif (b == -1) throw 'eof';\nreturn b;\n};\nvar count = 0;\nvar unicodeMap = {};\nwhile (true) {\nvar b0 = bin.read();\nif (b0 == -1) break;\nvar b1 = read();\nvar b2 = read();\nvar b3 = read();\nvar k = String.fromCharCode( (b0 << 8) | b1);\nvar v = (b2 << 8) | b3;\nunicodeMap[k] = v;\ncount += 1;\n}\nif (count != numChars) {\nthrow count + ' != ' + numChars;\n}\nreturn unicodeMap;\n}();\nvar unknownChar = '?'.charCodeAt(0);\nreturn function(s) {\nvar bytes = [];\nfor (var i = 0; i < s.length; i += 1) {\nvar c = s.charCodeAt(i);\nif (c < 128) {\nbytes.push(c);\n} else {\nvar b = unicodeMap[s.charAt(i)];\nif (typeof b == 'number') {\nif ( (b & 0xff) == b) {\nbytes.push(b);\n} else {\nbytes.push(b >>> 8);\nbytes.push(b & 0xff);\n}\n} else {\nbytes.push(unknownChar);\n}\n}\n}\nreturn bytes;\n};\n};\nvar QRMode = {\nMODE_NUMBER :    1 << 0,\nMODE_ALPHA_NUM : 1 << 1,\nMODE_8BIT_BYTE : 1 << 2,\nMODE_KANJI :     1 << 3\n};\nvar QRErrorCorrectionLevel = {\nL : 1,\nM : 0,\nQ : 3,\nH : 2\n};\nvar QRMaskPattern = {\nPATTERN000 : 0,\nPATTERN001 : 1,\nPATTERN010 : 2,\nPATTERN011 : 3,\nPATTERN100 : 4,\nPATTERN101 : 5,\nPATTERN110 : 6,\nPATTERN111 : 7\n};\nvar QRUtil = function() {\nvar PATTERN_POSITION_TABLE = [\n[],\n[6, 18],\n[6, 22],\n[6, 26],\n[6, 30],\n[6, 34],\n[6, 22, 38],\n[6, 24, 42],\n[6, 26, 46],\n[6, 28, 50],\n[6, 30, 54],\n[6, 32, 58],\n[6, 34, 62],\n[6, 26, 46, 66],\n[6, 26, 48, 70],\n[6, 26, 50, 74],\n[6, 30, 54, 78],\n[6, 30, 56, 82],\n[6, 30, 58, 86],\n[6, 34, 62, 90],\n[6, 28, 50, 72, 94],\n[6, 26, 50, 74, 98],\n[6, 30, 54, 78, 102],\n[6, 28, 54, 80, 106],\n[6, 32, 58, 84, 110],\n[6, 30, 58, 86, 114],\n[6, 34, 62, 90, 118],\n[6, 26, 50, 74, 98, 122],\n[6, 30, 54, 78, 102, 126],\n[6, 26, 52, 78, 104, 130],\n[6, 30, 56, 82, 108, 134],\n[6, 34, 60, 86, 112, 138],\n[6, 30, 58, 86, 114, 142],\n[6, 34, 62, 90, 118, 146],\n[6, 30, 54, 78, 102, 126, 150],\n[6, 24, 50, 76, 102, 128, 154],\n[6, 28, 54, 80, 106, 132, 158],\n[6, 32, 58, 84, 110, 136, 162],\n[6, 26, 54, 82, 110, 138, 166],\n[6, 30, 58, 86, 114, 142, 170]\n];\nvar G15 = (1 << 10) | (1 << 8) | (1 << 5) | (1 << 4) | (1 << 2) | (1 << 1) | (1 << 0);\nvar G18 = (1 << 12) | (1 << 11) | (1 << 10) | (1 << 9) | (1 << 8) | (1 << 5) | (1 << 2) | (1 << 0);\nvar G15_MASK = (1 << 14) | (1 << 12) | (1 << 10) | (1 << 4) | (1 << 1);\nvar _this = {};\nvar getBCHDigit = function(data) {\nvar digit = 0;\nwhile (data != 0) {\ndigit += 1;\ndata >>>= 1;\n}\nreturn digit;\n};\n_this.getBCHTypeInfo = function(data) {\nvar d = data << 10;\nwhile (getBCHDigit(d) - getBCHDigit(G15) >= 0) {\nd ^= (G15 << (getBCHDigit(d) - getBCHDigit(G15) ) );\n}\nreturn ( (data << 10) | d) ^ G15_MASK;\n};\n_this.getBCHTypeNumber = function(data) {\nvar d = data << 12;\nwhile (getBCHDigit(d) - getBCHDigit(G18) >= 0) {\nd ^= (G18 << (getBCHDigit(d) - getBCHDigit(G18) ) );\n}\nreturn (data << 12) | d;\n};\n_this.getPatternPosition = function(typeNumber) {\nreturn PATTERN_POSITION_TABLE[typeNumber - 1];\n};\n_this.getMaskFunction = function(maskPattern) {\nswitch (maskPattern) {\ncase QRMaskPattern.PATTERN000 :\nreturn function(i, j) { return (i + j) % 2 == 0; };\ncase QRMaskPattern.PATTERN001 :\nreturn function(i, j) { return i % 2 == 0; };\ncase QRMaskPattern.PATTERN010 :\nreturn function(i, j) { return j % 3 == 0; };\ncase QRMaskPattern.PATTERN011 :\nreturn function(i, j) { return (i + j) % 3 == 0; };\ncase QRMaskPattern.PATTERN100 :\nreturn function(i, j) { return (Math.floor(i / 2) + Math.floor(j / 3) ) % 2 == 0; };\ncase QRMaskPattern.PATTERN101 :\nreturn function(i, j) { return (i * j) % 2 + (i * j) % 3 == 0; };\ncase QRMaskPattern.PATTERN110 :\nreturn function(i, j) { return ( (i * j) % 2 + (i * j) % 3) % 2 == 0; };\ncase QRMaskPattern.PATTERN111 :\nreturn function(i, j) { return ( (i * j) % 3 + (i + j) % 2) % 2 == 0; };\ndefault :\nthrow 'bad maskPattern:' + maskPattern;\n}\n};\n_this.getErrorCorrectPolynomial = function(errorCorrectLength) {\nvar a = qrPolynomial([1], 0);\nfor (var i = 0; i < errorCorrectLength; i += 1) {\na = a.multiply(qrPolynomial([1, QRMath.gexp(i)], 0) );\n}\nreturn a;\n};\n_this.getLengthInBits = function(mode, type) {\nif (1 <= type && type < 10) {\nswitch(mode) {\ncase QRMode.MODE_NUMBER    : return 10;\ncase QRMode.MODE_ALPHA_NUM : return 9;\ncase QRMode.MODE_8BIT_BYTE : return 8;\ncase QRMode.MODE_KANJI     : return 8;\ndefault :\nthrow 'mode:' + mode;\n}\n} else if (type < 27) {\nswitch(mode) {\ncase QRMode.MODE_NUMBER    : return 12;\ncase QRMode.MODE_ALPHA_NUM : return 11;\ncase QRMode.MODE_8BIT_BYTE : return 16;\ncase QRMode.MODE_KANJI     : return 10;\ndefault :\nthrow 'mode:' + mode;\n}\n} else if (type < 41) {\nswitch(mode) {\ncase QRMode.MODE_NUMBER    : return 14;\ncase QRMode.MODE_ALPHA_NUM : return 13;\ncase QRMode.MODE_8BIT_BYTE : return 16;\ncase QRMode.MODE_KANJI     : return 12;\ndefault :\nthrow 'mode:' + mode;\n}\n} else {\nthrow 'type:' + type;\n}\n};\n_this.getLostPoint = function(qrcode) {\nvar moduleCount = qrcode.getModuleCount();\nvar lostPoint = 0;\nfor (var row = 0; row < moduleCount; row += 1) {\nfor (var col = 0; col < moduleCount; col += 1) {\nvar sameCount = 0;\nvar dark = qrcode.isDark(row, col);\nfor (var r = -1; r <= 1; r += 1) {\nif (row + r < 0 || moduleCount <= row + r) {\ncontinue;\n}\nfor (var c = -1; c <= 1; c += 1) {\nif (col + c < 0 || moduleCount <= col + c) {\ncontinue;\n}\nif (r == 0 && c == 0) {\ncontinue;\n}\nif (dark == qrcode.isDark(row + r, col + c) ) {\nsameCount += 1;\n}\n}\n}\nif (sameCount > 5) {\nlostPoint += (3 + sameCount - 5);\n}\n}\n};\nfor (var row = 0; row < moduleCount - 1; row += 1) {\nfor (var col = 0; col < moduleCount - 1; col += 1) {\nvar count = 0;\nif (qrcode.isDark(row, col) ) count += 1;\nif (qrcode.isDark(row + 1, col) ) count += 1;\nif (qrcode.isDark(row, col + 1) ) count += 1;\nif (qrcode.isDark(row + 1, col + 1) ) count += 1;\nif (count == 0 || count == 4) {\nlostPoint += 3;\n}\n}\n}\nfor (var row = 0; row < moduleCount; row += 1) {\nfor (var col = 0; col < moduleCount - 6; col += 1) {\nif (qrcode.isDark(row, col)\n&& !qrcode.isDark(row, col + 1)\n&&  qrcode.isDark(row, col + 2)\n&&  qrcode.isDark(row, col + 3)\n&&  qrcode.isDark(row, col + 4)\n&& !qrcode.isDark(row, col + 5)\n&&  qrcode.isDark(row, col + 6) ) {\nlostPoint += 40;\n}\n}\n}\nfor (var col = 0; col < moduleCount; col += 1) {\nfor (var row = 0; row < moduleCount - 6; row += 1) {\nif (qrcode.isDark(row, col)\n&& !qrcode.isDark(row + 1, col)\n&&  qrcode.isDark(row + 2, col)\n&&  qrcode.isDark(row + 3, col)\n&&  qrcode.isDark(row + 4, col)\n&& !qrcode.isDark(row + 5, col)\n&&  qrcode.isDark(row + 6, col) ) {\nlostPoint += 40;\n}\n}\n}\nvar darkCount = 0;\nfor (var col = 0; col < moduleCount; col += 1) {\nfor (var row = 0; row < moduleCount; row += 1) {\nif (qrcode.isDark(row, col) ) {\ndarkCount += 1;\n}\n}\n}\nvar ratio = Math.abs(100 * darkCount / moduleCount / moduleCount - 50) / 5;\nlostPoint += ratio * 10;\nreturn lostPoint;\n};\nreturn _this;\n}();\nvar QRMath = function() {\nvar EXP_TABLE = new Array(256);\nvar LOG_TABLE = new Array(256);\nfor (var i = 0; i < 8; i += 1) {\nEXP_TABLE[i] = 1 << i;\n}\nfor (var i = 8; i < 256; i += 1) {\nEXP_TABLE[i] = EXP_TABLE[i - 4]\n^ EXP_TABLE[i - 5]\n^ EXP_TABLE[i - 6]\n^ EXP_TABLE[i - 8];\n}\nfor (var i = 0; i < 255; i += 1) {\nLOG_TABLE[EXP_TABLE[i] ] = i;\n}\nvar _this = {};\n_this.glog = function(n) {\nif (n < 1) {\nthrow 'glog(' + n + ')';\n}\nreturn LOG_TABLE[n];\n};\n_this.gexp = function(n) {\nwhile (n < 0) {\nn += 255;\n}\nwhile (n >= 256) {\nn -= 255;\n}\nreturn EXP_TABLE[n];\n};\nreturn _this;\n}();\nfunction qrPolynomial(num, shift) {\nif (typeof num.length == 'undefined') {\nthrow num.length + '/' + shift;\n}\nvar _num = function() {\nvar offset = 0;\nwhile (offset < num.length && num[offset] == 0) {\noffset += 1;\n}\nvar _num = new Array(num.length - offset + shift);\nfor (var i = 0; i < num.length - offset; i += 1) {\n_num[i] = num[i + offset];\n}\nreturn _num;\n}();\nvar _this = {};\n_this.getAt = function(index) {\nreturn _num[index];\n};\n_this.getLength = function() {\nreturn _num.length;\n};\n_this.multiply = function(e) {\nvar num = new Array(_this.getLength() + e.getLength() - 1);\nfor (var i = 0; i < _this.getLength(); i += 1) {\nfor (var j = 0; j < e.getLength(); j += 1) {\nnum[i + j] ^= QRMath.gexp(QRMath.glog(_this.getAt(i) ) + QRMath.glog(e.getAt(j) ) );\n}\n}\nreturn qrPolynomial(num, 0);\n};\n_this.mod = function(e) {\nif (_this.getLength() - e.getLength() < 0) {\nreturn _this;\n}\nvar ratio = QRMath.glog(_this.getAt(0) ) - QRMath.glog(e.getAt(0) );\nvar num = new Array(_this.getLength() );\nfor (var i = 0; i < _this.getLength(); i += 1) {\nnum[i] = _this.getAt(i);\n}\nfor (var i = 0; i < e.getLength(); i += 1) {\nnum[i] ^= QRMath.gexp(QRMath.glog(e.getAt(i) ) + ratio);\n}\nreturn qrPolynomial(num, 0).mod(e);\n};\nreturn _this;\n};\nvar QRRSBlock = function() {\nvar RS_BLOCK_TABLE = [\n[1, 26, 19],\n[1, 26, 16],\n[1, 26, 13],\n[1, 26, 9],\n[1, 44, 34],\n[1, 44, 28],\n[1, 44, 22],\n[1, 44, 16],\n[1, 70, 55],\n[1, 70, 44],\n[2, 35, 17],\n[2, 35, 13],\n[1, 100, 80],\n[2, 50, 32],\n[2, 50, 24],\n[4, 25, 9],\n[1, 134, 108],\n[2, 67, 43],\n[2, 33, 15, 2, 34, 16],\n[2, 33, 11, 2, 34, 12],\n[2, 86, 68],\n[4, 43, 27],\n[4, 43, 19],\n[4, 43, 15],\n[2, 98, 78],\n[4, 49, 31],\n[2, 32, 14, 4, 33, 15],\n[4, 39, 13, 1, 40, 14],\n[2, 121, 97],\n[2, 60, 38, 2, 61, 39],\n[4, 40, 18, 2, 41, 19],\n[4, 40, 14, 2, 41, 15],\n[2, 146, 116],\n[3, 58, 36, 2, 59, 37],\n[4, 36, 16, 4, 37, 17],\n[4, 36, 12, 4, 37, 13],\n[2, 86, 68, 2, 87, 69],\n[4, 69, 43, 1, 70, 44],\n[6, 43, 19, 2, 44, 20],\n[6, 43, 15, 2, 44, 16],\n[4, 101, 81],\n[1, 80, 50, 4, 81, 51],\n[4, 50, 22, 4, 51, 23],\n[3, 36, 12, 8, 37, 13],\n[2, 116, 92, 2, 117, 93],\n[6, 58, 36, 2, 59, 37],\n[4, 46, 20, 6, 47, 21],\n[7, 42, 14, 4, 43, 15],\n[4, 133, 107],\n[8, 59, 37, 1, 60, 38],\n[8, 44, 20, 4, 45, 21],\n[12, 33, 11, 4, 34, 12],\n[3, 145, 115, 1, 146, 116],\n[4, 64, 40, 5, 65, 41],\n[11, 36, 16, 5, 37, 17],\n[11, 36, 12, 5, 37, 13],\n[5, 109, 87, 1, 110, 88],\n[5, 65, 41, 5, 66, 42],\n[5, 54, 24, 7, 55, 25],\n[11, 36, 12, 7, 37, 13],\n[5, 122, 98, 1, 123, 99],\n[7, 73, 45, 3, 74, 46],\n[15, 43, 19, 2, 44, 20],\n[3, 45, 15, 13, 46, 16],\n[1, 135, 107, 5, 136, 108],\n[10, 74, 46, 1, 75, 47],\n[1, 50, 22, 15, 51, 23],\n[2, 42, 14, 17, 43, 15],\n[5, 150, 120, 1, 151, 121],\n[9, 69, 43, 4, 70, 44],\n[17, 50, 22, 1, 51, 23],\n[2, 42, 14, 19, 43, 15],\n[3, 141, 113, 4, 142, 114],\n[3, 70, 44, 11, 71, 45],\n[17, 47, 21, 4, 48, 22],\n[9, 39, 13, 16, 40, 14],\n[3, 135, 107, 5, 136, 108],\n[3, 67, 41, 13, 68, 42],\n[15, 54, 24, 5, 55, 25],\n[15, 43, 15, 10, 44, 16],\n[4, 144, 116, 4, 145, 117],\n[17, 68, 42],\n[17, 50, 22, 6, 51, 23],\n[19, 46, 16, 6, 47, 17],\n[2, 139, 111, 7, 140, 112],\n[17, 74, 46],\n[7, 54, 24, 16, 55, 25],\n[34, 37, 13],\n[4, 151, 121, 5, 152, 122],\n[4, 75, 47, 14, 76, 48],\n[11, 54, 24, 14, 55, 25],\n[16, 45, 15, 14, 46, 16],\n[6, 147, 117, 4, 148, 118],\n[6, 73, 45, 14, 74, 46],\n[11, 54, 24, 16, 55, 25],\n[30, 46, 16, 2, 47, 17],\n[8, 132, 106, 4, 133, 107],\n[8, 75, 47, 13, 76, 48],\n[7, 54, 24, 22, 55, 25],\n[22, 45, 15, 13, 46, 16],\n[10, 142, 114, 2, 143, 115],\n[19, 74, 46, 4, 75, 47],\n[28, 50, 22, 6, 51, 23],\n[33, 46, 16, 4, 47, 17],\n[8, 152, 122, 4, 153, 123],\n[22, 73, 45, 3, 74, 46],\n[8, 53, 23, 26, 54, 24],\n[12, 45, 15, 28, 46, 16],\n[3, 147, 117, 10, 148, 118],\n[3, 73, 45, 23, 74, 46],\n[4, 54, 24, 31, 55, 25],\n[11, 45, 15, 31, 46, 16],\n[7, 146, 116, 7, 147, 117],\n[21, 73, 45, 7, 74, 46],\n[1, 53, 23, 37, 54, 24],\n[19, 45, 15, 26, 46, 16],\n[5, 145, 115, 10, 146, 116],\n[19, 75, 47, 10, 76, 48],\n[15, 54, 24, 25, 55, 25],\n[23, 45, 15, 25, 46, 16],\n[13, 145, 115, 3, 146, 116],\n[2, 74, 46, 29, 75, 47],\n[42, 54, 24, 1, 55, 25],\n[23, 45, 15, 28, 46, 16],\n[17, 145, 115],\n[10, 74, 46, 23, 75, 47],\n[10, 54, 24, 35, 55, 25],\n[19, 45, 15, 35, 46, 16],\n[17, 145, 115, 1, 146, 116],\n[14, 74, 46, 21, 75, 47],\n[29, 54, 24, 19, 55, 25],\n[11, 45, 15, 46, 46, 16],\n[13, 145, 115, 6, 146, 116],\n[14, 74, 46, 23, 75, 47],\n[44, 54, 24, 7, 55, 25],\n[59, 46, 16, 1, 47, 17],\n[12, 151, 121, 7, 152, 122],\n[12, 75, 47, 26, 76, 48],\n[39, 54, 24, 14, 55, 25],\n[22, 45, 15, 41, 46, 16],\n[6, 151, 121, 14, 152, 122],\n[6, 75, 47, 34, 76, 48],\n[46, 54, 24, 10, 55, 25],\n[2, 45, 15, 64, 46, 16],\n[17, 152, 122, 4, 153, 123],\n[29, 74, 46, 14, 75, 47],\n[49, 54, 24, 10, 55, 25],\n[24, 45, 15, 46, 46, 16],\n[4, 152, 122, 18, 153, 123],\n[13, 74, 46, 32, 75, 47],\n[48, 54, 24, 14, 55, 25],\n[42, 45, 15, 32, 46, 16],\n[20, 147, 117, 4, 148, 118],\n[40, 75, 47, 7, 76, 48],\n[43, 54, 24, 22, 55, 25],\n[10, 45, 15, 67, 46, 16],\n[19, 148, 118, 6, 149, 119],\n[18, 75, 47, 31, 76, 48],\n[34, 54, 24, 34, 55, 25],\n[20, 45, 15, 61, 46, 16]\n];\nvar qrRSBlock = function(totalCount, dataCount) {\nvar _this = {};\n_this.totalCount = totalCount;\n_this.dataCount = dataCount;\nreturn _this;\n};\nvar _this = {};\nvar getRsBlockTable = function(typeNumber, errorCorrectionLevel) {\nswitch(errorCorrectionLevel) {\ncase QRErrorCorrectionLevel.L :\nreturn RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 0];\ncase QRErrorCorrectionLevel.M :\nreturn RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 1];\ncase QRErrorCorrectionLevel.Q :\nreturn RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 2];\ncase QRErrorCorrectionLevel.H :\nreturn RS_BLOCK_TABLE[(typeNumber - 1) * 4 + 3];\ndefault :\nreturn undefined;\n}\n};\n_this.getRSBlocks = function(typeNumber, errorCorrectionLevel) {\nvar rsBlock = getRsBlockTable(typeNumber, errorCorrectionLevel);\nif (typeof rsBlock == 'undefined') {\nthrow 'bad rs block @ typeNumber:' + typeNumber +\n'/errorCorrectionLevel:' + errorCorrectionLevel;\n}\nvar length = rsBlock.length / 3;\nvar list = [];\nfor (var i = 0; i < length; i += 1) {\nvar count = rsBlock[i * 3 + 0];\nvar totalCount = rsBlock[i * 3 + 1];\nvar dataCount = rsBlock[i * 3 + 2];\nfor (var j = 0; j < count; j += 1) {\nlist.push(qrRSBlock(totalCount, dataCount) );\n}\n}\nreturn list;\n};\nreturn _this;\n}();\nvar qrBitBuffer = function() {\nvar _buffer = [];\nvar _length = 0;\nvar _this = {};\n_this.getBuffer = function() {\nreturn _buffer;\n};\n_this.getAt = function(index) {\nvar bufIndex = Math.floor(index / 8);\nreturn ( (_buffer[bufIndex] >>> (7 - index % 8) ) & 1) == 1;\n};\n_this.put = function(num, length) {\nfor (var i = 0; i < length; i += 1) {\n_this.putBit( ( (num >>> (length - i - 1) ) & 1) == 1);\n}\n};\n_this.getLengthInBits = function() {\nreturn _length;\n};\n_this.putBit = function(bit) {\nvar bufIndex = Math.floor(_length / 8);\nif (_buffer.length <= bufIndex) {\n_buffer.push(0);\n}\nif (bit) {\n_buffer[bufIndex] |= (0x80 >>> (_length % 8) );\n}\n_length += 1;\n};\nreturn _this;\n};\nvar qrNumber = function(data) {\nvar _mode = QRMode.MODE_NUMBER;\nvar _data = data;\nvar _this = {};\n_this.getMode = function() {\nreturn _mode;\n};\n_this.getLength = function(buffer) {\nreturn _data.length;\n};\n_this.write = function(buffer) {\nvar data = _data;\nvar i = 0;\nwhile (i + 2 < data.length) {\nbuffer.put(strToNum(data.substring(i, i + 3) ), 10);\ni += 3;\n}\nif (i < data.length) {\nif (data.length - i == 1) {\nbuffer.put(strToNum(data.substring(i, i + 1) ), 4);\n} else if (data.length - i == 2) {\nbuffer.put(strToNum(data.substring(i, i + 2) ), 7);\n}\n}\n};\nvar strToNum = function(s) {\nvar num = 0;\nfor (var i = 0; i < s.length; i += 1) {\nnum = num * 10 + chatToNum(s.charAt(i) );\n}\nreturn num;\n};\nvar chatToNum = function(c) {\nif ('0' <= c && c <= '9') {\nreturn c.charCodeAt(0) - '0'.charCodeAt(0);\n}\nthrow 'illegal char :' + c;\n};\nreturn _this;\n};\nvar qrAlphaNum = function(data) {\nvar _mode = QRMode.MODE_ALPHA_NUM;\nvar _data = data;\nvar _this = {};\n_this.getMode = function() {\nreturn _mode;\n};\n_this.getLength = function(buffer) {\nreturn _data.length;\n};\n_this.write = function(buffer) {\nvar s = _data;\nvar i = 0;\nwhile (i + 1 < s.length) {\nbuffer.put(\ngetCode(s.charAt(i) ) * 45 +\ngetCode(s.charAt(i + 1) ), 11);\ni += 2;\n}\nif (i < s.length) {\nbuffer.put(getCode(s.charAt(i) ), 6);\n}\n};\nvar getCode = function(c) {\nif ('0' <= c && c <= '9') {\nreturn c.charCodeAt(0) - '0'.charCodeAt(0);\n} else if ('A' <= c && c <= 'Z') {\nreturn c.charCodeAt(0) - 'A'.charCodeAt(0) + 10;\n} else {\nswitch (c) {\ncase ' ' : return 36;\ncase '$' : return 37;\ncase '%' : return 38;\ncase '*' : return 39;\ncase '+' : return 40;\ncase '-' : return 41;\ncase '.' : return 42;\ncase '/' : return 43;\ncase ':' : return 44;\ndefault :\nthrow 'illegal char :' + c;\n}\n}\n};\nreturn _this;\n};\nvar qr8BitByte = function(data) {\nvar _mode = QRMode.MODE_8BIT_BYTE;\nvar _data = data;\nvar _bytes = qrcode.stringToBytes(data);\nvar _this = {};\n_this.getMode = function() {\nreturn _mode;\n};\n_this.getLength = function(buffer) {\nreturn _bytes.length;\n};\n_this.write = function(buffer) {\nfor (var i = 0; i < _bytes.length; i += 1) {\nbuffer.put(_bytes[i], 8);\n}\n};\nreturn _this;\n};\nvar qrKanji = function(data) {\nvar _mode = QRMode.MODE_KANJI;\nvar _data = data;\nvar stringToBytes = qrcode.stringToBytesFuncs['SJIS'];\nif (!stringToBytes) {\nthrow 'sjis not supported.';\n}\n!function(c, code) {\nvar test = stringToBytes(c);\nif (test.length != 2 || ( (test[0] << 8) | test[1]) != code) {\nthrow 'sjis not supported.';\n}\n}('\\u53cb', 0x9746);\nvar _bytes = stringToBytes(data);\nvar _this = {};\n_this.getMode = function() {\nreturn _mode;\n};\n_this.getLength = function(buffer) {\nreturn ~~(_bytes.length / 2);\n};\n_this.write = function(buffer) {\nvar data = _bytes;\nvar i = 0;\nwhile (i + 1 < data.length) {\nvar c = ( (0xff & data[i]) << 8) | (0xff & data[i + 1]);\nif (0x8140 <= c && c <= 0x9FFC) {\nc -= 0x8140;\n} else if (0xE040 <= c && c <= 0xEBBF) {\nc -= 0xC140;\n} else {\nthrow 'illegal char at ' + (i + 1) + '/' + c;\n}\nc = ( (c >>> 8) & 0xff) * 0xC0 + (c & 0xff);\nbuffer.put(c, 13);\ni += 2;\n}\nif (i < data.length) {\nthrow 'illegal char at ' + (i + 1);\n}\n};\nreturn _this;\n};\nvar byteArrayOutputStream = function() {\nvar _bytes = [];\nvar _this = {};\n_this.writeByte = function(b) {\n_bytes.push(b & 0xff);\n};\n_this.writeShort = function(i) {\n_this.writeByte(i);\n_this.writeByte(i >>> 8);\n};\n_this.writeBytes = function(b, off, len) {\noff = off || 0;\nlen = len || b.length;\nfor (var i = 0; i < len; i += 1) {\n_this.writeByte(b[i + off]);\n}\n};\n_this.writeString = function(s) {\nfor (var i = 0; i < s.length; i += 1) {\n_this.writeByte(s.charCodeAt(i) );\n}\n};\n_this.toByteArray = function() {\nreturn _bytes;\n};\n_this.toString = function() {\nvar s = '';\ns += '[';\nfor (var i = 0; i < _bytes.length; i += 1) {\nif (i > 0) {\ns += ',';\n}\ns += _bytes[i];\n}\ns += ']';\nreturn s;\n};\nreturn _this;\n};\nvar base64EncodeOutputStream = function() {\nvar _buffer = 0;\nvar _buflen = 0;\nvar _length = 0;\nvar _base64 = '';\nvar _this = {};\nvar writeEncoded = function(b) {\n_base64 += String.fromCharCode(encode(b & 0x3f) );\n};\nvar encode = function(n) {\nif (n < 0) {\n} else if (n < 26) {\nreturn 0x41 + n;\n} else if (n < 52) {\nreturn 0x61 + (n - 26);\n} else if (n < 62) {\nreturn 0x30 + (n - 52);\n} else if (n == 62) {\nreturn 0x2b;\n} else if (n == 63) {\nreturn 0x2f;\n}\nthrow 'n:' + n;\n};\n_this.writeByte = function(n) {\n_buffer = (_buffer << 8) | (n & 0xff);\n_buflen += 8;\n_length += 1;\nwhile (_buflen >= 6) {\nwriteEncoded(_buffer >>> (_buflen - 6) );\n_buflen -= 6;\n}\n};\n_this.flush = function() {\nif (_buflen > 0) {\nwriteEncoded(_buffer << (6 - _buflen) );\n_buffer = 0;\n_buflen = 0;\n}\nif (_length % 3 != 0) {\nvar padlen = 3 - _length % 3;\nfor (var i = 0; i < padlen; i += 1) {\n_base64 += '=';\n}\n}\n};\n_this.toString = function() {\nreturn _base64;\n};\nreturn _this;\n};\nvar base64DecodeInputStream = function(str) {\nvar _str = str;\nvar _pos = 0;\nvar _buffer = 0;\nvar _buflen = 0;\nvar _this = {};\n_this.read = function() {\nwhile (_buflen < 8) {\nif (_pos >= _str.length) {\nif (_buflen == 0) {\nreturn -1;\n}\nthrow 'unexpected end of file./' + _buflen;\n}\nvar c = _str.charAt(_pos);\n_pos += 1;\nif (c == '=') {\n_buflen = 0;\nreturn -1;\n} else if (c.match(/^\\s$/) ) {\ncontinue;\n}\n_buffer = (_buffer << 6) | decode(c.charCodeAt(0) );\n_buflen += 6;\n}\nvar n = (_buffer >>> (_buflen - 8) ) & 0xff;\n_buflen -= 8;\nreturn n;\n};\nvar decode = function(c) {\nif (0x41 <= c && c <= 0x5a) {\nreturn c - 0x41;\n} else if (0x61 <= c && c <= 0x7a) {\nreturn c - 0x61 + 26;\n} else if (0x30 <= c && c <= 0x39) {\nreturn c - 0x30 + 52;\n} else if (c == 0x2b) {\nreturn 62;\n} else if (c == 0x2f) {\nreturn 63;\n} else {\nthrow 'c:' + c;\n}\n};\nreturn _this;\n};\nvar gifImage = function(width, height) {\nvar _width = width;\nvar _height = height;\nvar _data = new Array(width * height);\nvar _this = {};\n_this.setPixel = function(x, y, pixel) {\n_data[y * _width + x] = pixel;\n};\n_this.write = function(out) {\nout.writeString('GIF87a');\nout.writeShort(_width);\nout.writeShort(_height);\nout.writeByte(0x80); // 2bit\nout.writeByte(0);\nout.writeByte(0);\nout.writeByte(0x00);\nout.writeByte(0x00);\nout.writeByte(0x00);\nout.writeByte(0xff);\nout.writeByte(0xff);\nout.writeByte(0xff);\nout.writeString(',');\nout.writeShort(0);\nout.writeShort(0);\nout.writeShort(_width);\nout.writeShort(_height);\nout.writeByte(0);\nvar lzwMinCodeSize = 2;\nvar raster = getLZWRaster(lzwMinCodeSize);\nout.writeByte(lzwMinCodeSize);\nvar offset = 0;\nwhile (raster.length - offset > 255) {\nout.writeByte(255);\nout.writeBytes(raster, offset, 255);\noffset += 255;\n}\nout.writeByte(raster.length - offset);\nout.writeBytes(raster, offset, raster.length - offset);\nout.writeByte(0x00);\nout.writeString(';');\n};\nvar bitOutputStream = function(out) {\nvar _out = out;\nvar _bitLength = 0;\nvar _bitBuffer = 0;\nvar _this = {};\n_this.write = function(data, length) {\nif ( (data >>> length) != 0) {\nthrow 'length over';\n}\nwhile (_bitLength + length >= 8) {\n_out.writeByte(0xff & ( (data << _bitLength) | _bitBuffer) );\nlength -= (8 - _bitLength);\ndata >>>= (8 - _bitLength);\n_bitBuffer = 0;\n_bitLength = 0;\n}\n_bitBuffer = (data << _bitLength) | _bitBuffer;\n_bitLength = _bitLength + length;\n};\n_this.flush = function() {\nif (_bitLength > 0) {\n_out.writeByte(_bitBuffer);\n}\n};\nreturn _this;\n};\nvar getLZWRaster = function(lzwMinCodeSize) {\nvar clearCode = 1 << lzwMinCodeSize;\nvar endCode = (1 << lzwMinCodeSize) + 1;\nvar bitLength = lzwMinCodeSize + 1;\nvar table = lzwTable();\nfor (var i = 0; i < clearCode; i += 1) {\ntable.add(String.fromCharCode(i) );\n}\ntable.add(String.fromCharCode(clearCode) );\ntable.add(String.fromCharCode(endCode) );\nvar byteOut = byteArrayOutputStream();\nvar bitOut = bitOutputStream(byteOut);\nbitOut.write(clearCode, bitLength);\nvar dataIndex = 0;\nvar s = String.fromCharCode(_data[dataIndex]);\ndataIndex += 1;\nwhile (dataIndex < _data.length) {\nvar c = String.fromCharCode(_data[dataIndex]);\ndataIndex += 1;\nif (table.contains(s + c) ) {\ns = s + c;\n} else {\nbitOut.write(table.indexOf(s), bitLength);\nif (table.size() < 0xfff) {\nif (table.size() == (1 << bitLength) ) {\nbitLength += 1;\n}\ntable.add(s + c);\n}\ns = c;\n}\n}\nbitOut.write(table.indexOf(s), bitLength);\nbitOut.write(endCode, bitLength);\nbitOut.flush();\nreturn byteOut.toByteArray();\n};\nvar lzwTable = function() {\nvar _map = {};\nvar _size = 0;\nvar _this = {};\n_this.add = function(key) {\nif (_this.contains(key) ) {\nthrow 'dup key:' + key;\n}\n_map[key] = _size;\n_size += 1;\n};\n_this.size = function() {\nreturn _size;\n};\n_this.indexOf = function(key) {\nreturn _map[key];\n};\n_this.contains = function(key) {\nreturn typeof _map[key] != 'undefined';\n};\nreturn _this;\n};\nreturn _this;\n};\nvar createDataURL = function(width, height, getPixel) {\nvar gif = gifImage(width, height);\nfor (var y = 0; y < height; y += 1) {\nfor (var x = 0; x < width; x += 1) {\ngif.setPixel(x, y, getPixel(x, y) );\n}\n}\nvar b = byteArrayOutputStream();\ngif.write(b);\nvar base64 = base64EncodeOutputStream();\nvar bytes = b.toByteArray();\nfor (var i = 0; i < bytes.length; i += 1) {\nbase64.writeByte(bytes[i]);\n}\nbase64.flush();\nreturn 'data:image/gif;base64,' + base64;\n};\nreturn qrcode;\n}();\n!function() {\nqrcode.stringToBytesFuncs['UTF-8'] = function(s) {\n// http://stackoverflow.com/questions/18729405/how-to-convert-utf8-string-to-byte-array\nfunction toUTF8Array(str) {\nvar utf8 = [];\nfor (var i=0; i < str.length; i++) {\nvar charcode = str.charCodeAt(i);\nif (charcode < 0x80) utf8.push(charcode);\nelse if (charcode < 0x800) {\nutf8.push(0xc0 | (charcode >> 6),\n0x80 | (charcode & 0x3f));\n}\nelse if (charcode < 0xd800 || charcode >= 0xe000) {\nutf8.push(0xe0 | (charcode >> 12),\n0x80 | ((charcode>>6) & 0x3f),\n0x80 | (charcode & 0x3f));\n}\nelse {\ni++;\ncharcode = 0x10000 + (((charcode & 0x3ff)<<10)\n| (str.charCodeAt(i) & 0x3ff));\nutf8.push(0xf0 | (charcode >>18),\n0x80 | ((charcode>>12) & 0x3f),\n0x80 | ((charcode>>6) & 0x3f),\n0x80 | (charcode & 0x3f));\n}\n}\nreturn utf8;\n}\nreturn toUTF8Array(s);\n};\n}();\n(function (factory) {\nif (typeof define === 'function' && define.amd) {\ndefine([], factory);\n} else if (typeof exports === 'object') {\nmodule.exports = factory();\n}\n}(function () {\nreturn qrcode;\n}));\n</script>\n<script>\n(function () {\n  \"use strict\";\n  var $ = function (id) { return document.getElementById(id); };\n  var params = new URLSearchParams(location.search);\n  var urlToken = params.get(\"token\") || \"\";           // device link opened in a browser (older relays)\n  var hashToken = new URLSearchParams(location.hash.slice(1)).get(\"connect\") || \"\";   // from the QR code\n  var token = \"\";                                       // device token, given to signed-in browsers\n  var state = null, liveTimer = null;\n  var VIEWS = [\"vLoading\", \"vSetup\", \"vLogin\", \"vNewPw\", \"vConnect\", \"vLocked\", \"vMain\"];\n\n  function show(view) { VIEWS.forEach(function (v) { $(v).classList.toggle(\"hidden\", v !== view); }); }\n  function toast(msg) {\n    var t = $(\"toast\"); t.textContent = msg; t.classList.add(\"show\");\n    clearTimeout(t._h); t._h = setTimeout(function () { t.classList.remove(\"show\"); }, 2200);\n  }\n  function chip(colour, text) { $(\"chipDot\").style.background = colour; $(\"chipText\").textContent = text; }\n  function css(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }\n  function hm(iso) { return iso ? new Date(iso).toLocaleTimeString(\"en-GB\", { hour: \"2-digit\", minute: \"2-digit\" }) : \"--:--\"; }\n  function dur(min) { return min < 60 ? min + \" min\" : (min % 60 ? Math.floor(min / 60) + \" h \" + (min % 60) + \" min\" : (min / 60) + \" h\"); }\n  function cleanUrl() { history.replaceState(null, \"\", \"/\"); }\n\n  async function api(path, body) {\n    var opts = { headers: { \"Content-Type\": \"application/json\" }, cache: \"no-store\", credentials: \"same-origin\" };\n    if (urlToken) opts.headers[\"X-Relay-Token\"] = urlToken;\n    if (body) { opts.method = \"POST\"; opts.body = JSON.stringify(body); }\n    var r = await fetch(path, opts);\n    var j = {};\n    try { j = await r.json(); } catch (e) {}\n    if (!r.ok) { var err = new Error(j.status || j.error || (\"Error \" + r.status)); err.code = r.status; throw err; }\n    return j;\n  }\n  function busy(btn, text) { var old = btn.textContent; btn.disabled = true; btn.textContent = text; return function () { btn.disabled = false; btn.textContent = old; }; }\n  function deviceLink(t) { return location.origin + \"/?token=\" + encodeURIComponent(t); }\n  function appLink(t) { return \"octopuslive://connect?relay=\" + encodeURIComponent(deviceLink(t)); }\n\n  // ---------- first-time setup\n  $(\"setupForm\").addEventListener(\"submit\", async function (e) {\n    e.preventDefault();\n    $(\"setupErr\").textContent = \"\";\n    if ($(\"setupPw\").value !== $(\"setupPw2\").value) { $(\"setupErr\").textContent = \"The passwords don't match\"; return; }\n    var done = busy($(\"setupBtn\"), \"Checking your key\u2026\");\n    try {\n      var j = await api(\"/api/setup\", { apiKey: $(\"setupKey\").value.trim(), account: $(\"setupAccount\").value.trim(), username: $(\"setupUser\").value.trim(), password: $(\"setupPw\").value });\n      cleanUrl();\n      await load(\"You're connected\" + (j.account ? \" to \" + j.account : \"\") + \". Now add your devices below.\");\n    } catch (err) { $(\"setupErr\").textContent = err.message; }\n    finally { done(); }\n  });\n\n  // ---------- sign in / create password\n  $(\"loginForm\").addEventListener(\"submit\", async function (e) {\n    e.preventDefault();\n    $(\"loginErr\").textContent = \"\";\n    var done = busy($(\"loginBtn\"), \"Signing in\u2026\");\n    try { await api(\"/api/login\", { username: $(\"loginUser\").value.trim(), password: $(\"loginPw\").value }); $(\"loginPw\").value = \"\"; cleanUrl(); urlToken = \"\"; await load(); }\n    catch (err) { $(\"loginErr\").textContent = err.message; }\n    finally { done(); }\n  });\n  $(\"newPwForm\").addEventListener(\"submit\", async function (e) {\n    e.preventDefault();\n    $(\"newPwErr\").textContent = \"\";\n    if ($(\"newPw1\").value !== $(\"newPw2\").value) { $(\"newPwErr\").textContent = \"The passwords don't match\"; return; }\n    var done = busy($(\"newPwBtn\"), \"Saving\u2026\");\n    try { await api(\"/api/password\", { username: $(\"newUser\").value.trim(), password: $(\"newPw1\").value }); cleanUrl(); urlToken = \"\"; await load(\"Saved. Next time, sign in with your username and password.\"); }\n    catch (err) { $(\"newPwErr\").textContent = err.message; }\n    finally { done(); }\n  });\n\n  // ---------- dashboard\n  function fillConnect() {\n    $(\"link\").textContent = deviceLink(token);\n    $(\"rainmeter\").textContent = \"RelayUrl=\" + deviceLink(token);\n    $(\"openApp\").href = appLink(token);\n    try {\n      // The code opens a small page on the phone with just the \"Open in Octopus Live\" button.\n      // The token is after \"#\", so it never reaches the server's logs.\n      var q = qrcode(0, \"M\"); q.addData(location.origin + \"/#connect=\" + token); q.make();\n      $(\"qr\").innerHTML = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true });\n      $(\"qrRow\").classList.remove(\"hidden\");\n    } catch (e) { $(\"qrRow\").classList.add(\"hidden\"); }\n    if (/Android|iPhone|iPad/i.test(navigator.userAgent)) $(\"qrRow\").classList.add(\"hidden\");\n  }\n\n  function renderLive(d) {\n    var w = d.demandW;\n    var colour = w == null ? css(\"--muted\") : w < 500 ? css(\"--green\") : w < 2000 ? css(\"--amber\") : css(\"--red\");\n    $(\"watts\").textContent = w == null ? \"\u2014\" : (w >= 1000 ? (w / 1000).toFixed(2) + \" kW\" : w + \" W\");\n    $(\"bar\").style.width = Math.min(100, (w || 0) / 30) + \"%\";\n    $(\"bar\").style.background = colour;\n    $(\"wattsSub\").textContent = d.ok ? \"Meter reading at \" + hm(d.readAt) : d.status || \"\";\n    $(\"wattsSub\").style.color = d.ok ? \"\" : css(\"--red\");\n    $(\"elecToday\").textContent = d.todayKwh == null ? \"\u2014\" : d.todayKwh.toFixed(2) + \" kWh\";\n    $(\"gasTile\").classList.toggle(\"hidden\", !d.hasGas);\n    if (d.hasGas) {\n      $(\"gasToday\").textContent = (d.gasKwh || 0).toFixed(2) + \" kWh\";\n      $(\"gasSub\").textContent = d.gasAt ? \"to \" + hm(d.gasAt) : \"\";\n    }\n    var gasOn = d.hasGas && d.gasRunMinutes > 0;\n    $(\"gasBanner\").classList.toggle(\"hidden\", !gasOn);\n    if (gasOn) $(\"gasText\").textContent = \"Gas on for \" + dur(d.gasRunMinutes) + (d.gasRunSince ? \" \u00b7 since \" + hm(d.gasRunSince) : \"\");\n    if (d.ok) chip(css(\"--green\"), \"Live \u00b7 \" + hm(d.readAt));\n    else chip(css(\"--red\"), \"Problem\");\n  }\n\n  async function refreshLive() {\n    try { renderLive(await api(\"/api/live\")); }\n    catch (e) { if (e.code === 401) load(); else chip(css(\"--red\"), \"Not reachable\"); }\n  }\n\n  function renderSettings() {\n    var s = state;\n    $(\"keyStatus\").textContent = s.keyOk === false ? \"Not working: \" + (s.lastError || \"check your key\") :\n      \"Connected\" + (s.account ? \" \u00b7 account \" + s.account : \"\");\n    $(\"keyStatus\").className = s.keyOk === false ? \"err\" : \"\";\n    $(\"whoami\").textContent = s.username || \"(no username yet)\";\n    var warn = $(\"persistWarn\");\n    if (s.persistent === false) {\n      warn.textContent = \"Note: this server has no data folder, so changes made here are lost when it restarts. See the setup guide.\";\n      warn.classList.remove(\"hidden\");\n    } else warn.classList.add(\"hidden\");\n  }\n\n  function toggleForm(form, button, open) {\n    $(form).classList.toggle(\"hidden\", !open); $(button).classList.toggle(\"hidden\", open);\n  }\n  $(\"changeKeyBtn\").addEventListener(\"click\", function () { toggleForm(\"keyForm\", \"changeKeyBtn\", true); $(\"newKey\").focus(); });\n  $(\"cancelKeyBtn\").addEventListener(\"click\", function () { toggleForm(\"keyForm\", \"changeKeyBtn\", false); $(\"keyErr\").textContent = \"\"; });\n  $(\"keyForm\").addEventListener(\"submit\", async function (e) {\n    e.preventDefault();\n    var key = $(\"newKey\").value.trim();\n    if (!key) { $(\"keyErr\").textContent = \"Paste your new API key\"; return; }\n    $(\"keyErr\").textContent = \"\";\n    var done = busy($(\"saveKeyBtn\"), \"Checking\u2026\");\n    try {\n      var j = await api(\"/api/settings\", { apiKey: key, account: $(\"newAccount\").value.trim() });\n      $(\"newKey\").value = \"\"; toggleForm(\"keyForm\", \"changeKeyBtn\", false);\n      toast(\"API key saved\");\n      await load(j.account ? \"New API key saved for \" + j.account + \".\" : \"New API key saved.\");\n    } catch (err) { $(\"keyErr\").textContent = err.message; }\n    finally { done(); }\n  });\n\n  $(\"changePwBtn\").addEventListener(\"click\", function () { toggleForm(\"pwForm\", \"changePwBtn\", true); $(\"pwUser\").value = state.username || \"\"; $(\"pwUser\").focus(); });\n  $(\"cancelPwBtn\").addEventListener(\"click\", function () { toggleForm(\"pwForm\", \"changePwBtn\", false); $(\"pwErr\").textContent = \"\"; });\n  $(\"pwForm\").addEventListener(\"submit\", async function (e) {\n    e.preventDefault();\n    $(\"pwErr\").textContent = \"\";\n    if ($(\"pw1\").value !== $(\"pw2\").value) { $(\"pwErr\").textContent = \"The passwords don't match\"; return; }\n    var done = busy($(\"savePwBtn\"), \"Saving\u2026\");\n    try {\n      var user = $(\"pwUser\").value.trim();\n      if (!user) { $(\"pwErr\").textContent = \"Enter a username\"; return; }\n      await api(\"/api/password\", { username: user, password: $(\"pw1\").value });\n      $(\"pw1\").value = $(\"pw2\").value = \"\"; toggleForm(\"pwForm\", \"changePwBtn\", false);\n      state.username = user; $(\"whoami\").textContent = user;\n      toast(\"Sign-in details saved\");\n    } catch (err) { $(\"pwErr\").textContent = err.message; }\n    finally { done(); }\n  });\n\n  $(\"rotateBtn\").addEventListener(\"click\", async function () {\n    if (!confirm(\"Make a new device link? The old one stops working straight away, so you'll need to update your phone, PC and anything else that uses it.\")) return;\n    $(\"rotateErr\").textContent = \"\";\n    try {\n      await api(\"/api/settings\", { rotateToken: true });\n      await load(\"New device link created. Update your devices with the link below.\");\n      window.scrollTo({ top: 0, behavior: \"smooth\" });\n    } catch (err) { $(\"rotateErr\").textContent = err.message; }\n  });\n\n  $(\"logoutBtn\").addEventListener(\"click\", async function () {\n    try { await api(\"/api/logout\", {}); } catch (e) {}\n    token = \"\"; await load();\n  });\n\n  document.addEventListener(\"click\", function (e) {\n    var id = e.target.getAttribute && e.target.getAttribute(\"data-copy\");\n    if (!id) return;\n    var text = $(id).textContent;\n    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(\n      function () { toast(\"Copied\"); },\n      function () {\n        var r = document.createRange(); r.selectNodeContents($(id));\n        var sel = getSelection(); sel.removeAllRanges(); sel.addRange(r); toast(\"Selected - copy it now\");\n      });\n  });\n\n  // ---------- start\n  async function load(notice) {\n    clearInterval(liveTimer);\n    if (hashToken) {   // opened from the QR code: only the connect button, no sign-in needed\n      show(\"vConnect\"); chip(css(\"--muted\"), \"Connect\"); $(\"ver\").textContent = \"Octopus Live\";\n      $(\"connectBtn\").href = appLink(hashToken);\n      return;\n    }\n    try { state = await api(\"/api/setup/state\"); }\n    catch (e) { show(\"vLocked\"); chip(css(\"--red\"), \"Not reachable\"); return; }\n    $(\"ver\").textContent = (state.kind === \"cloud\" ? \"Cloudflare relay \" : \"Home relay \") + (state.version || \"\");\n    document.querySelectorAll(\".kind\").forEach(function (el) {\n      el.textContent = state.kind === \"cloud\" ? \"Cloudflare Worker (your account)\" : \"home server\";\n    });\n    if (!state.configured) { show(\"vSetup\"); chip(css(\"--muted\"), \"Not set up\"); return; }\n    if (!state.signedIn) {\n      if (state.canReset) { show(\"vNewPw\"); chip(css(\"--muted\"), \"Create sign-in\"); return; }\n      if (state.hasPassword) { show(\"vLogin\"); chip(css(\"--muted\"), \"Signed out\"); setTimeout(function () { $(\"loginUser\").focus(); }, 50); return; }\n      show(\"vLocked\"); chip(css(\"--muted\"), \"Private\"); return;\n    }\n    token = state.token;\n    show(\"vMain\");\n    var n = $(\"notice\");\n    if (notice) { n.textContent = notice; n.classList.remove(\"hidden\"); } else n.classList.add(\"hidden\");\n    fillConnect();\n    renderSettings();\n    refreshLive();\n    liveTimer = setInterval(refreshLive, 15000);\n  }\n  load();\n})();\n</script>\n</body>\n</html>\n";
// END PAGE
