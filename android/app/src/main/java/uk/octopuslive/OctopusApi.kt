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
                if (!hasKey) throw ApiException("Home server offline")
                if (sinceDirect < 60_000L) throw ApiException("Home server offline - using Octopus once a minute")
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
            if (code == 401) throw ApiException("Home server needs ?token=… in its address")
            if (code !in 200..299) throw ApiException("Home server error $code")
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

        // One request: latest reading (live) + today's half-hourly totals (today)
        val query = """
            query {
              live: smartMeterTelemetry(deviceId: "$id") { readAt demand consumption }
              today: smartMeterTelemetry(deviceId: "$id", grouping: HALF_HOURLY,
                start: "${start.withNano(0).format(fmt)}", end: "${now.withNano(0).format(fmt)}") { readAt consumptionDelta }
            }
        """.trimIndent()
        val data = graphql(query, token)

        val live = data.optJSONArray("live").objects()
            .maxByOrNull { it.optString("readAt") }
            ?: throw ApiException("No live reading - is the Home Mini online?")

        var todayWh = 0.0
        for (row in data.optJSONArray("today").objects()) {
            if (!row.isNull("consumptionDelta")) todayWh += row.optDouble("consumptionDelta", 0.0)
        }

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
            gasTodayKwh = prefs.gasKwh, gasReadAt = prefs.gasAt,
        )
    }

    /**
     * Today's gas usage. Gas meters only send a reading every 30 minutes, so this runs at most
     * every [GAS_EVERY_MS] (and at midnight). Failures are ignored: electricity keeps working.
     */
    private fun updateGas(token: String, start: ZonedDateTime, now: ZonedDateTime) {
        val gasId = prefs.gasDeviceId
        if (gasId == null || gasId == NO_GAS) {
            prefs.gasKwh = null; prefs.gasAt = null
            return
        }
        val today = start.toLocalDate().toString()
        val fresh = prefs.gasDay == today && System.currentTimeMillis() - prefs.gasFetched < GAS_EVERY_MS
        if (fresh) return

        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        try {
            val d = graphql(
                """query { gas: smartMeterTelemetry(deviceId: "${clean(gasId)}", grouping: HALF_HOURLY,
                     start: "${start.withNano(0).format(fmt)}", end: "${now.withNano(0).format(fmt)}") { readAt consumptionDelta } }""",
                token,
            )
            var wh = 0.0
            var last: String? = null
            for (row in d.optJSONArray("gas").objects()) {
                if (row.isNull("consumptionDelta")) continue
                val v = row.optDouble("consumptionDelta", 0.0)
                if (v.isNaN()) continue
                wh += v
                if (v > 0) last = row.optString("readAt")
            }
            prefs.gasKwh = wh / 1000.0
            prefs.gasAt = last?.let {
                try {
                    OffsetDateTime.parse(it).atZoneSameInstant(now.zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                } catch (e: Exception) { null }
            }
            prefs.gasDay = today
            prefs.gasFetched = System.currentTimeMillis()
        } catch (e: Exception) {
            // Keep yesterday's figure out of today's display
            if (prefs.gasDay != today) { prefs.gasKwh = 0.0; prefs.gasAt = null }
        }
    }

    private fun token(): String {
        val cached = prefs.token
        if (cached != null && prefs.tokenExpires > System.currentTimeMillis()) return cached

        val key = prefs.apiKey ?: throw ApiException("Add your API key in the app")
        val data = graphql("""mutation { obtainKrakenToken(input: { APIKey: "${clean(key)}" }) { token } }""", null)
        val token = data.getJSONObject("obtainKrakenToken").getString("token")
        prefs.token = token
        prefs.tokenExpires = System.currentTimeMillis() + 50 * 60 * 1000L   // tokens last 60 min
        return token
    }

    private fun deviceId(token: String): String {
        val cachedId = prefs.deviceId
        if (cachedId != null && prefs.gasDeviceId != null) return cachedId

        val account = prefs.accountNumber?.takeIf { it.isNotBlank() } ?: run {
            val d = graphql("query { viewer { accounts { number } } }", token)
            d.getJSONObject("viewer").optJSONArray("accounts").objects().firstOrNull()?.optString("number")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("No account found - enter your account number")
        }

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

    private fun graphql(query: String, token: String?): JSONObject {
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

    private fun clean(s: String) = s.replace(Regex("[\"\\\\\\s]"), "")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        const val ENDPOINT = "https://api.octopus.energy/v1/graphql/"
        const val NO_GAS = "none"
        const val GAS_EVERY_MS = 30 * 60 * 1000L
    }
}
