package uk.octopuslive

import android.content.Context

/** Settings and cached values, stored privately on the phone. */
class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("octopus_live", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = p.getString("apiKey", null)
        set(v) = p.edit().putString("apiKey", v).apply()

    /** Optional address of your own relay server, e.g. http://192.168.1.50:8765 */
    var relayUrl: String?
        get() = p.getString("relayUrl", null)
        set(v) = p.edit().putString("relayUrl", v).apply()

    var lastDirectAt: Long
        get() = p.getLong("lastDirectAt", 0)
        set(v) = p.edit().putLong("lastDirectAt", v).apply()

    /** Account number found automatically (used by the insights look-ups). */
    var foundAccount: String?
        get() = p.getString("foundAccount", null)
        set(v) = p.edit().putString("foundAccount", v).apply()

    // Generic storage for the insights cache and alert state
    fun str(key: String): String? = p.getString(key, null)
    fun setStr(key: String, v: String?) = p.edit().putString(key, v).apply()
    fun long(key: String): Long = p.getLong(key, 0L)
    fun setLong(key: String, v: Long) = p.edit().putLong(key, v).apply()

    // Alert settings
    var highAlert: Boolean
        get() = p.getBoolean("highAlert", true)
        set(v) = p.edit().putBoolean("highAlert", v).apply()
    var highKw: Float
        get() = p.getFloat("highKw", 3.0f)
        set(v) = p.edit().putFloat("highKw", v).apply()
    var highMinutes: Int
        get() = p.getInt("highMinutes", 45)
        set(v) = p.edit().putInt("highMinutes", v).apply()
    var offlineAlert: Boolean
        get() = p.getBoolean("offlineAlert", true)
        set(v) = p.edit().putBoolean("offlineAlert", v).apply()
    var cheapAlert: Boolean
        get() = p.getBoolean("cheapAlert", true)
        set(v) = p.edit().putBoolean("cheapAlert", v).apply()
    var cheapPence: Float
        get() = p.getFloat("cheapPence", 0f)
        set(v) = p.edit().putFloat("cheapPence", v).apply()
    /** Gas-on and high-use warnings stay in the notifications until usage drops. */
    var stickyWarnings: Boolean
        get() = p.getBoolean("stickyWarnings", true)
        set(v) = p.edit().putBoolean("stickyWarnings", v).apply()
    /** The gas run / high-use spell the user muted ("Mute until off"). */
    var mutedGasRun: String?
        get() = p.getString("mutedGasRun", null)
        set(v) = p.edit().putString("mutedGasRun", v).apply()
    var mutedHighSince: Long
        get() = p.getLong("mutedHighSince", 0L)
        set(v) = p.edit().putLong("mutedHighSince", v).apply()
    var summaryAlert: Boolean
        get() = p.getBoolean("summaryAlert", true)
        set(v) = p.edit().putBoolean("summaryAlert", v).apply()
    var summaryHour: Int
        get() = p.getInt("summaryHour", 8)
        set(v) = p.edit().putInt("summaryHour", v).apply()

    var accountNumber: String?
        get() = p.getString("account", null)
        set(v) = p.edit().putString("account", v).apply()

    var intervalSeconds: Int
        get() = p.getInt("interval", 60)
        set(v) = p.edit().putInt("interval", v).apply()

    /** Tapping the home-screen widget opens the app instead of refreshing it. */
    var widgetOpensApp: Boolean
        get() = p.getBoolean("widgetOpensApp", false)
        set(v) = p.edit().putBoolean("widgetOpensApp", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("boot", true)
        set(v) = p.edit().putBoolean("boot", v).apply()

    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    // Cached API state
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()

    var tokenExpires: Long
        get() = p.getLong("tokenExpires", 0)
        set(v) = p.edit().putLong("tokenExpires", v).apply()

    var deviceId: String?
        get() = p.getString("deviceId", null)
        set(v) = p.edit().putString("deviceId", v).apply()

    var gasDeviceId: String?
        get() = p.getString("gasDeviceId", null)
        set(v) = p.edit().putString("gasDeviceId", v).apply()

    // Gas: cached today's total (fetched every 30 minutes)
    var gasKwh: Double?
        get() = if (p.contains("gasKwh")) p.getFloat("gasKwh", 0f).toDouble() else null
        set(v) { if (v == null) p.edit().remove("gasKwh").apply() else p.edit().putFloat("gasKwh", v.toFloat()).apply() }

    var gasKw: Double?
        get() = if (p.contains("gasKw")) p.getFloat("gasKw", 0f).toDouble() else null
        set(v) { if (v == null) p.edit().remove("gasKw").apply() else p.edit().putFloat("gasKw", v.toFloat()).apply() }

    var gasRunMinutes: Int
        get() = p.getInt("gasRunMinutes", 0)
        set(v) = p.edit().putInt("gasRunMinutes", v).apply()

    var gasRunSince: String?
        get() = p.getString("gasRunSince", null)
        set(v) = p.edit().putString("gasRunSince", v).apply()

    // Gas alert setting, and which run we've already warned about (so it's sent once per run)
    var gasAlert: Boolean
        get() = p.getBoolean("gasAlert", true)
        set(v) = p.edit().putBoolean("gasAlert", v).apply()

    var gasAlertSentFor: String?
        get() = p.getString("gasAlertSentFor", null)
        set(v) = p.edit().putString("gasAlertSentFor", v).apply()

    var gasAt: String?
        get() = p.getString("gasAt", null)
        set(v) = p.edit().putString("gasAt", v).apply()

    // Electricity "today": baseline from the last half-hourly re-check + meter running total
    var syncDay: String?
        get() = p.getString("syncDay", null)
        set(v) = p.edit().putString("syncDay", v).apply()

    var syncTime: Long
        get() = p.getLong("syncTime", 0)
        set(v) = p.edit().putLong("syncTime", v).apply()

    var syncTodayWh: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("syncTodayWh", 0L))
        set(v) = p.edit().putLong("syncTodayWh", java.lang.Double.doubleToRawLongBits(v)).apply()

    var syncMeterWh: Double?
        get() = if (p.contains("syncMeterWh")) java.lang.Double.longBitsToDouble(p.getLong("syncMeterWh", 0L)) else null
        set(v) { if (v == null) p.edit().remove("syncMeterWh").apply() else p.edit().putLong("syncMeterWh", java.lang.Double.doubleToRawLongBits(v)).apply() }

    var lastTodayWh: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("lastTodayWh", 0L))
        set(v) = p.edit().putLong("lastTodayWh", java.lang.Double.doubleToRawLongBits(v)).apply()

    var gasDay: String?
        get() = p.getString("gasDay", null)
        set(v) = p.edit().putString("gasDay", v).apply()

    var gasFetched: Long
        get() = p.getLong("gasFetched", 0)
        set(v) = p.edit().putLong("gasFetched", v).apply()

    // Last result, shown in the app
    var lastSummary: String?
        get() = p.getString("lastSummary", null)
        set(v) = p.edit().putString("lastSummary", v).apply()

    // Last reading, shared by the notification and the home-screen widget
    var lastError: String?
        get() = p.getString("lastError", null)
        set(v) = p.edit().putString("lastError", v).apply()

    fun saveReading(r: Reading) {
        p.edit()
            .putInt("lastDemand", r.demandWatts ?: -1)
            .putFloat("lastToday", r.todayKwh.toFloat())
            .putString("lastReadAt", r.readAt)
            .putLong("lastFetchedAt", r.fetchedAt)
            .putBoolean("lastViaServer", r.viaServer)
            .putLong("lastReadAtMs", r.readAtMs ?: 0L)
            .apply()
        // Keep gas in step too (in home-server mode it comes from the server, not the cache)
        gasKwh = r.gasTodayKwh
        gasAt = r.gasReadAt
        gasKw = r.gasKw
        gasRunMinutes = r.gasRunMinutes
        gasRunSince = r.gasRunSince
    }

    fun loadReading(): Reading? {
        val fetchedAt = p.getLong("lastFetchedAt", 0)
        if (fetchedAt == 0L) return null
        return Reading(
            demandWatts = p.getInt("lastDemand", -1).takeIf { it >= 0 },
            todayKwh = p.getFloat("lastToday", 0f).toDouble(),
            readAt = p.getString("lastReadAt", "--:--") ?: "--:--",
            fetchedAt = fetchedAt,
            gasTodayKwh = gasKwh,
            gasReadAt = gasAt,
            gasKw = gasKw,
            gasRunMinutes = gasRunMinutes,
            gasRunSince = gasRunSince,
            viaServer = p.getBoolean("lastViaServer", false),
            readAtMs = p.getLong("lastReadAtMs", 0L).takeIf { it > 0 },
        )
    }

    /** True when there's an API key or a home server to read from. */
    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank() || !relayUrl.isNullOrBlank()

    fun clearCachedApiState() {
        p.edit().remove("token").remove("tokenExpires").remove("deviceId")
            .remove("gasDeviceId").remove("gasKwh").remove("gasKw").remove("syncDay").remove("syncMeterWh").remove("gasAt").remove("gasDay").remove("gasFetched").apply()
    }
}
