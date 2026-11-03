package uk.octopuslive

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub once a day for a newer release, if "Check for updates" is on.
 * It only reads the latest release's version number and page link; it never downloads or
 * installs anything. Tapping "Update available" opens the release page in the browser.
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/ruwaid-7/octopus-live/releases/latest"
    private const val EVERY_MS = 24 * 3600_000L

    /** The newer version's tag (e.g. "v1.9.3"), or null if this is the latest we know of. */
    fun available(c: Context): String? {
        val p = Prefs(c)
        if (!p.updateCheck || BuildConfig.DEV_TOOLS) return null
        val tag = p.updateLatest ?: return null
        return tag.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }
    }

    /** The release page for [available]. */
    fun url(c: Context): String = Prefs(c).updateUrl ?: "https://github.com/ruwaid-7/octopus-live/releases/latest"

    /** Asks GitHub in the background if a day has passed since the last check; [done] runs on that thread. */
    fun checkIfDue(c: Context, done: () -> Unit) {
        val p = Prefs(c.applicationContext)
        if (!p.updateCheck || BuildConfig.DEV_TOOLS) return
        val now = System.currentTimeMillis()
        if (now - p.updateCheckedAt in 0 until EVERY_MS) return
        p.updateCheckedAt = now
        Thread {
            try {
                val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "OctopusLive-Android")
                }
                try {
                    if (conn.responseCode == 200) {
                        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val tag = j.optString("tag_name")
                        if (tag.isNotBlank() && !j.optBoolean("draft") && !j.optBoolean("prerelease")) {
                            p.updateLatest = tag
                            p.updateUrl = j.optString("html_url").takeIf { it.startsWith("https://github.com/") }
                            done()
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // No connection or GitHub unreachable: try again tomorrow
            }
        }.start()
    }

    /** "v1.10.0" is newer than "1.9.2". Anything after a "-" (like "-dev") is ignored. */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
