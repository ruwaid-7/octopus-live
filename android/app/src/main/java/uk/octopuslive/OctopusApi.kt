package uk.octopuslive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One snapshot from the Home Mini. */
data class Reading(
    val demandWatts: Int?,
    val todayKwh: Double,
    val readAt: String,      // local HH:mm of the meter reading
    val fetchedAt: Long,
    val gasTodayKwh: Double? = null,   // null = no smart gas meter / not known yet
    val gasReadAt: String? = null,     // local HH:mm of the latest half-hourly gas reading
    val viaServer: Boolean = false,    // true = read from your own relay server
    val gasKw: Double? = null,         // average gas power over the latest half-hour
    val gasRunMinutes: Int = 0,        // how long the gas has been on (consecutive half-hours)
    val gasRunSince: String? = null,   // local HH:mm when that run started
    val readAtMs: Long? = null,        // meter reading time (epoch ms), for the "Home Mini offline" warning
)

class ApiException(message: String, val code: String? = null) : Exception(message)

/**
 * Minimal client for the Octopus Energy GraphQL API (Kraken).
 * Blocking calls - run them off the main thread.
 */
class OctopusApi(context: Context) {

    private val prefs = Prefs(context)

    fun fetch(): Reading {
        val relay = prefs.relayUrl?.trim().orEmpty()
        if (relay.isNotEmpty()) {
            try {
                return fetchFromServer(relay)
            } catch (e: Exception) {
                // Server unreachable (e.g. away from home): fall back to Octopus, at most once a minute
                val hasKey = !prefs.apiKey.isNullOrBlank()
                val sinceDirect = System.currentTimeMillis() - prefs.lastDirectAt
                if (!hasKey) throw ApiException("Relay offline")
                if (sinceDirect < 60_000L) throw ApiException("Relay offline - using Octopus once a minute")
            }
        }
        if (prefs.apiKey.isNullOrBlank()) throw ApiException("Add your API key in the app")
        prefs.lastDirectAt = System.currentTimeMillis()
        return fetchDirect()
    }

    /** Reads the latest values from your Octopus Live relay server (see /server in the repo). */
    private fun fetchFromServer(base: String): Reading {
        val withScheme = if (base.contains("://")) base else "http://$base"
        val uri = android.net.Uri.parse(withScheme)
        val url = if (uri.path.isNullOrEmpty() || uri.path == "/") uri.buildUpon().path("/api/live").build() else uri
        val conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 6_000
            setRequestProperty("User-Agent", "OctopusLive-Android")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw ApiException("Relay needs ?token=… in its address")
            if (code !in 200..299) throw ApiException("Relay error $code")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (!j.optBoolean("ok", false)) throw ApiException("Server: " + j.optString("status", "not ready"))
            val zone = ZoneId.systemDefault()
            fun hhmm(s: String?): String? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
                try { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } catch (e: Exception) { null }
            }
            val demand = if (j.isNull("demandW")) null else j.optDouble("demandW").let { if (it.isNaN()) null else Math.round(it).toInt() }
            val hasGas = j.optBoolean("hasGas", false)
            return Reading(
                demandWatts = demand,
                todayKwh = j.optDouble("todayKwh", 0.0).let { if (it.isNaN()) 0.0 else it },
                readAt = hhmm(j.optString("readAt", null)) ?: "--:--",
                fetchedAt = System.currentTimeMillis(),
                gasTodayKwh = if (hasGas) j.optDouble("gasKwh", 0.0).let { if (it.isNaN()) 0.0 else it } else null,
                gasReadAt = if (hasGas) hhmm(j.optString("gasAt", null)) else null,
                viaServer = true,
                gasKw = if (hasGas) j.optDouble("gasKw", 0.0).let { if (it.isNaN()) 0.0 else it } else null,
                gasRunMinutes = if (hasGas) j.optInt("gasRunMinutes", 0) else 0,
                gasRunSince = if (hasGas) hhmm(j.optString("gasRunSince", null)) else null,
                readAtMs = epoch(j.optString("readAt", null)),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchDirect(): Reading {
        try {
            return fetchOnce()
        } catch (e: ApiException) {
            // Token expired or invalidated: clear it and try once more with a fresh one.
            if (e.code?.startsWith("KT-CT-11") == true && prefs.token != null) {
                prefs.token = null
                return fetchOnce()
            }
            throw e
        }
    }

    private fun fetchOnce(): Reading {
        val token = token()
        val deviceId = deviceId(token)

        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val start = now.truncatedTo(ChronoUnit.DAYS)
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val id = clean(deviceId)

        // Octopus counts each telemetry series against its rate limit, so usually only the live
        // reading is requested. Today's half-hourly total is added every 30 minutes (and at
        // midnight); in between, "today" is kept up to date from the meter's running total.
        val todayKey = start.toLocalDate().toString()
        val needSync = prefs.syncDay != todayKey || prefs.syncMeterWh == null ||
            System.currentTimeMillis() - prefs.syncTime >= SYNC_EVERY_MS
        val todayPart = if (needSync) """
              today: smartMeterTelemetry(deviceId: "$id", grouping: HALF_HOURLY,
                start: "${start.withNano(0).format(fmt)}", end: "${now.withNano(0).format(fmt)}") { readAt consumptionDelta }""" else ""
        val query = """query { live: smartMeterTelemetry(deviceId: "$id") { readAt demand consumption } $todayPart }"""
        val data = graphql(query, token)

        val live = data.optJSONArray("live").objects()
            .maxByOrNull { it.optString("readAt") }
            ?: throw ApiException("No live reading - is the Home Mini online?")

        val meterWh = if (live.isNull("consumption")) null else live.optDouble("consumption").takeUnless { it.isNaN() }
        val syncMeter = prefs.syncMeterWh
        val todayWh: Double
        if (needSync) {
            var sum = 0.0
            for (row in data.optJSONArray("today").objects()) {
                if (!row.isNull("consumptionDelta")) sum += row.optDouble("consumptionDelta", 0.0).takeUnless { it.isNaN() } ?: 0.0
            }
            todayWh = sum
            prefs.syncDay = todayKey
            prefs.syncTime = System.currentTimeMillis()
            prefs.syncTodayWh = sum
            prefs.syncMeterWh = meterWh
        } else if (meterWh != null && syncMeter != null && meterWh >= syncMeter) {
            todayWh = prefs.syncTodayWh + (meterWh - syncMeter)
        } else {
            // Meter total missing or went backwards: keep the last figure, re-check next time
            todayWh = prefs.lastTodayWh
            prefs.syncMeterWh = null
        }
        prefs.lastTodayWh = todayWh

        val demand = if (live.isNull("demand")) null else live.optDouble("demand").let {
            if (it.isNaN()) null else Math.round(it).toInt()
        }
        val readAt = try {
            OffsetDateTime.parse(live.getString("readAt"))
                .atZoneSameInstant(zone)
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            live.optString("readAt")
        }

        updateGas(token, start, now)

        return Reading(
            demand, todayWh / 1000.0, readAt, System.currentTimeMillis(),
            gasTodayKwh = prefs.gasKwh, gasReadAt = prefs.gasAt, gasKw = prefs.gasKw,
            gasRunMinutes = prefs.gasRunMinutes, gasRunSince = prefs.gasRunSince,
            readAtMs = epoch(live.optString("readAt", null)),
        )
    }

    /**
     * Today's gas usage. Gas meters only send a reading every 30 minutes, so this runs at most
     * every [GAS_EVERY_MS] (and at midnight). Failures are ignored: electricity keeps working.
     */
    private fun updateGas(token: String, start: ZonedDateTime, now: ZonedDateTime) {
        val gasId = prefs.gasDeviceId
        if (gasId == null || gasId == NO_GAS) {
            prefs.gasKwh = null; prefs.gasAt = null; prefs.gasKw = null
            prefs.gasRunMinutes = 0; prefs.gasRunSince = null
            return
        }
        val today = start.toLocalDate().toString()
        val fresh = prefs.gasDay == today && System.currentTimeMillis() - prefs.gasFetched < GAS_EVERY_MS
        if (fresh) return

        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val from = start.minusHours(3)   // a little of yesterday, so a run over midnight is counted
        try {
            val d = graphql(
                """query { gas: smartMeterTelemetry(deviceId: "${clean(gasId)}", grouping: HALF_HOURLY,
                     start: "${from.withNano(0).format(fmt)}", end: "${now.withNano(0).format(fmt)}") { readAt consumptionDelta } }""",
                token,
            )
            val rows = d.optJSONArray("gas").objects().mapNotNull { row ->
                if (row.isNull("consumptionDelta")) return@mapNotNull null
                val v = row.optDouble("consumptionDelta", Double.NaN)
                val t = try { OffsetDateTime.parse(row.optString("readAt")) } catch (e: Exception) { null }
                if (v.isNaN() || t == null) null else t to v
            }.sortedBy { it.first }

            val midnight = start.toOffsetDateTime()
            val todayRows = rows.filter { !it.first.isBefore(midnight) }
            prefs.gasKwh = todayRows.sumOf { it.second } / 1000.0
            val hhmm = DateTimeFormatter.ofPattern("HH:mm")
            prefs.gasAt = todayRows.lastOrNull { it.second > 0 }?.first?.atZoneSameInstant(now.zone)?.format(hhmm)
            prefs.gasKw = (rows.lastOrNull()?.second ?: 0.0) / 500.0   // half-hour Wh -> average kW

            // How long the gas has been on: consecutive latest half-hours with real use
            var n = 0
            for (k in rows.indices.reversed()) { if (rows[k].second >= GAS_ON_WH) n++ else break }
            prefs.gasRunMinutes = n * 30
            prefs.gasRunSince = if (n > 0) rows[rows.size - n].first.atZoneSameInstant(now.zone).format(hhmm) else null

            prefs.gasDay = today
            prefs.gasFetched = System.currentTimeMillis()
        } catch (e: Exception) {
            // Keep yesterday's figure out of today's display
            if (prefs.gasDay != today) {
                prefs.gasKwh = 0.0; prefs.gasAt = null; prefs.gasKw = 0.0
                prefs.gasRunMinutes = 0; prefs.gasRunSince = null
            }
        }
    }

    internal fun token(): String {
        val cached = prefs.token
        if (cached != null && prefs.tokenExpires > System.currentTimeMillis()) return cached

        val key = prefs.apiKey ?: throw ApiException("Add your API key in the app")
        val data = graphql("""mutation { obtainKrakenToken(input: { APIKey: "${clean(key)}" }) { token } }""", null)
        val token = data.getJSONObject("obtainKrakenToken").getString("token")
        prefs.token = token
        prefs.tokenExpires = System.currentTimeMillis() + 50 * 60 * 1000L   // tokens last 60 min
        return token
    }

    internal fun deviceId(token: String): String {
        val cachedId = prefs.deviceId
        if (cachedId != null && prefs.gasDeviceId != null) return cachedId

        val account = prefs.accountNumber?.takeIf { it.isNotBlank() } ?: run {
            val d = graphql("query { viewer { accounts { number } } }", token)
            d.getJSONObject("viewer").optJSONArray("accounts").objects().firstOrNull()?.optString("number")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("No account found - enter your account number")
        }
        prefs.foundAccount = account

        val d = graphql(
            """query { account(accountNumber: "${clean(account)}") { electricityAgreements(active: true) {
                 meterPoint { direction meters(includeInactive: false) { smartImportElectricityMeter { deviceId } } } }
               gasAgreements(active: true) { meterPoint { meters(includeInactive: false) { smartGasMeter { deviceId } } } } } }""",
            token,
        )

        // Gas meter is optional
        prefs.gasDeviceId = d.getJSONObject("account").optJSONArray("gasAgreements").objects()
            .flatMap { it.optJSONObject("meterPoint")?.optJSONArray("meters").objects() }
            .mapNotNull { it.optJSONObject("smartGasMeter")?.optString("deviceId")?.takeIf { id -> id.isNotBlank() } }
            .firstOrNull() ?: NO_GAS
        for (ag in d.getJSONObject("account").optJSONArray("electricityAgreements").objects()) {
            val mp = ag.optJSONObject("meterPoint") ?: continue
            if (mp.optString("direction") == "EXPORT") continue
            for (m in mp.optJSONArray("meters").objects()) {
                val dev = m.optJSONObject("smartImportElectricityMeter")?.optString("deviceId")
                if (!dev.isNullOrBlank()) {
                    prefs.deviceId = dev
                    return dev
                }
            }
        }
        throw ApiException("No smart electricity meter found on this account")
    }

    internal fun graphql(query: String, token: String?): JSONObject {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "OctopusLive-Android/1.0")
            if (token != null) setRequestProperty("Authorization", token)
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("query", query).toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429) throw ApiException("Rate limited by Octopus - slowing down", "RATE")
            val json = try { JSONObject(body) } catch (e: Exception) {
                throw ApiException("HTTP $code from Octopus")
            }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val e = errors.getJSONObject(0)
                val errCode = e.optJSONObject("extensions")?.optString("errorCode")
                val msg = when (errCode) {
                    "KT-CT-1139", "KT-CT-1111", "KT-CT-1143" -> "API key rejected - check it in the app"
                    else -> e.optString("message", "Octopus API error")
                }
                throw ApiException(msg, errCode)
            }
            if (code !in 200..299) throw ApiException("HTTP $code from Octopus")
            return json.getJSONObject("data")
        } finally {
            conn.disconnect()
        }
    }

    internal fun clean(s: String) = s.replace(Regex("[\"\\\\\\s]"), "")

    /** Half-hourly usage (start epoch ms -> Wh) between two times, for one meter. */
    internal fun halfHourly(token: String, deviceId: String, from: ZonedDateTime, to: ZonedDateTime): List<Pair<Long, Double>> {
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val d = graphql(
            """query { s: smartMeterTelemetry(deviceId: "${clean(deviceId)}", grouping: HALF_HOURLY,
                 start: "${from.withNano(0).format(fmt)}", end: "${to.withNano(0).format(fmt)}") { readAt consumptionDelta } }""",
            token,
        )
        return d.optJSONArray("s").objects().mapNotNull { row ->
            if (row.isNull("consumptionDelta")) return@mapNotNull null
            val v = row.optDouble("consumptionDelta", Double.NaN)
            val t = epoch(row.optString("readAt", null))
            if (v.isNaN() || t == null) null else t to v
        }.sortedBy { it.first }
    }

    /** GET a public Octopus REST URL (tariff rates). Sends the API key as basic auth when we have one. */
    internal fun restGet(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "OctopusLive-Android")
            prefs.apiKey?.takeIf { it.isNotBlank() }?.let {
                val auth = android.util.Base64.encodeToString("$it:".toByteArray(), android.util.Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $auth")
            }
        }
        try {
            val code = conn.responseCode
            if (code == 429) throw ApiException("Rate limited by Octopus", "RATE")
            if (code !in 200..299) throw ApiException("HTTP $code from Octopus")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        internal fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /** ISO date-time (with offset or Z) -> epoch ms, or null. */
        fun epoch(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
            try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (e: Exception) { null }
        }

        const val ENDPOINT = "https://api.octopus.energy/v1/graphql/"
        const val NO_GAS = "none"
        const val GAS_EVERY_MS = 30 * 60 * 1000L
        const val SYNC_EVERY_MS = 30 * 60 * 1000L
        const val GAS_ON_WH = 100.0   // a half-hour with at least this much gas (0.2 kW average) counts as "on"
    }
}
