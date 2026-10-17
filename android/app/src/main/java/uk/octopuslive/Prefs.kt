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

    var accountNumber: String?
        get() = p.getString("account", null)
        set(v) = p.edit().putString("account", v).apply()

    var intervalSeconds: Int
        get() = p.getInt("interval", 60)
        set(v) = p.edit().putInt("interval", v).apply()

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

    var gasAt: String?
        get() = p.getString("gasAt", null)
        set(v) = p.edit().putString("gasAt", v).apply()

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
            .apply()
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
        )
    }

    /** True when there's an API key or a home server to read from. */
    val isConfigured: Boolean
        get() = !apiKey.isNullOrBlank() || !relayUrl.isNullOrBlank()

    fun clearCachedApiState() {
        p.edit().remove("token").remove("tokenExpires").remove("deviceId")
            .remove("gasDeviceId").remove("gasKwh").remove("gasAt").remove("gasDay").remove("gasFetched").apply()
    }
}
