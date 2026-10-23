package uk.octopuslive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The last few alerts the app sent, kept on the phone for "Recent alerts" on the Alerts tab. */
object AlertLog {
    data class Entry(val id: Int, val title: String, val text: String, val at: Long)

    private const val KEY = "alertLog"
    private const val MAX = 8

    fun add(context: Context, id: Int, title: String, text: String) {
        val prefs = Prefs(context)
        val list = load(context).toMutableList()
        list.add(0, Entry(id, title, text, System.currentTimeMillis()))
        val a = JSONArray()
        list.take(MAX).forEach { e ->
            a.put(JSONObject().put("id", e.id).put("title", e.title).put("text", e.text).put("at", e.at))
        }
        prefs.setStr(KEY, a.toString())
    }

    fun load(context: Context): List<Entry> = try {
        val a = JSONArray(Prefs(context).str(KEY) ?: "[]")
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Entry(it.optInt("id"), it.optString("title"), it.optString("text"), it.optLong("at")) }
        }
    } catch (e: Exception) { emptyList() }
}
