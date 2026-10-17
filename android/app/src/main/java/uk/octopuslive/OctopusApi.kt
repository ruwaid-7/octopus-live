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
)

class ApiException(message: String, val code: String? = null) : Exception(message)

/**
 * Minimal client for the Octopus Energy GraphQL API (Kraken).
 * Blocking calls - run them off the main thread.
 */
class OctopusApi(context: Context) {

    private val prefs = Prefs(context)

    fun fetch(): Reading {
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

        return Reading(demand, todayWh / 1000.0, readAt, System.currentTimeMillis())
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
        prefs.deviceId?.let { return it }

        val account = prefs.accountNumber?.takeIf { it.isNotBlank() } ?: run {
            val d = graphql("query { viewer { accounts { number } } }", token)
            d.getJSONObject("viewer").optJSONArray("accounts").objects().firstOrNull()?.optString("number")
                ?.takeIf { it.isNotBlank() }
                ?: throw ApiException("No account found - enter your account number")
        }

        val d = graphql(
            """query { account(accountNumber: "${clean(account)}") { electricityAgreements(active: true) {
                 meterPoint { direction meters(includeInactive: false) { smartImportElectricityMeter { deviceId } } } } } }""",
            token,
        )
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
    }
}
