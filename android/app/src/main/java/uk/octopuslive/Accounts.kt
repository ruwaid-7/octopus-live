package uk.octopuslive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Octopus accounts an API key can see, and their nicknames.
 *
 * When the key is first used, the app asks Octopus which accounts it can see (one request).
 * With one account nothing changes. With two or more, the app asks which one to show, and the
 * person can give each a nickname ("Home", "Mum's flat"), shown on Home in place of the number.
 *
 * Each account keeps its own prices, costs and history ([storageKey]). The account in use when a
 * second one was first found keeps the original storage, so nobody's existing history is lost.
 * A relay is tied to one account, so switching only applies when the app talks to Octopus itself.
 */
data class OctopusAccount(val number: String, val postcode: String?)

object Accounts {
    private const val K_LIST = "accounts"
    private const val K_NAMES = "accountNames"
    private const val K_CHOSEN = "accountChosen"
    private const val K_LEGACY = "accountLegacy"
    private const val K_CHECKED = "accountsChecked"

    fun list(prefs: Prefs): List<OctopusAccount> = try {
        val a = JSONArray(prefs.str(K_LIST) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            OctopusAccount(it.getString("n"), it.optString("p").takeIf { p -> p.isNotBlank() })
        }
    } catch (e: Exception) { emptyList() }

    /** Saves what Octopus returned. When a second account appears, remembers whose data is stored so far. */
    fun setList(prefs: Prefs, accounts: List<OctopusAccount>) {
        if (accounts.size > 1 && prefs.str(K_LEGACY) == null) {
            prefs.setStr(K_LEGACY, prefs.accountNumber?.takeIf { it.isNotBlank() } ?: prefs.foundAccount ?: accounts.first().number)
        }
        prefs.setStr(K_LIST, JSONArray().apply {
            accounts.forEach { put(JSONObject().put("n", it.number).put("p", it.postcode ?: "")) }
        }.toString())
        prefs.setStr(K_CHECKED, "1")
    }

    /** True once we've asked Octopus for this key's accounts. */
    fun checked(prefs: Prefs) = prefs.str(K_CHECKED) == "1"

    /** More than one account, and the app talks to Octopus itself (not a relay). */
    fun canSwitch(prefs: Prefs) = list(prefs).size > 1 && prefs.relayUrl.isNullOrBlank()

    /** Several accounts and the person hasn't picked one yet. */
    fun needsChoice(prefs: Prefs) = canSwitch(prefs) && prefs.str(K_CHOSEN) != "1"

    /** The account being shown. */
    fun current(prefs: Prefs): String? =
        prefs.accountNumber?.takeIf { it.isNotBlank() } ?: prefs.foundAccount ?: list(prefs).firstOrNull()?.number

    fun nickname(prefs: Prefs, number: String): String? = names(prefs).optString(number).takeIf { it.isNotBlank() }

    fun setNickname(prefs: Prefs, number: String, name: String?) {
        val n = names(prefs)
        if (name.isNullOrBlank()) n.remove(number) else n.put(number, name.trim().take(MAX_NAME))
        prefs.setStr(K_NAMES, n.toString())
    }

    /** "Home", or the account number if it has no nickname. */
    fun label(prefs: Prefs, number: String) = nickname(prefs, number) ?: number

    /** Shows [number] from now on: new readings, and its own prices and history. */
    fun switchTo(context: Context, number: String) {
        val prefs = Prefs(context)
        prefs.setStr(K_CHOSEN, "1")
        if (number == current(prefs) && !prefs.accountNumber.isNullOrBlank()) return
        prefs.accountNumber = number
        prefs.clearCachedApiState()
        prefs.clearReading()
    }

    /** Marks the choice as made (e.g. the person closed the question), so we don't ask again. */
    fun markChosen(prefs: Prefs) = prefs.setStr(K_CHOSEN, "1")

    /** A new API key: forget its accounts and the choice (nicknames stay, they're per account number). */
    fun reset(prefs: Prefs) {
        prefs.setStr(K_LIST, null); prefs.setStr(K_CHOSEN, null); prefs.setStr(K_CHECKED, null)
        prefs.accountNumber = null
    }

    /** Where Insights keeps [key] for the current account. */
    fun storageKey(prefs: Prefs, key: String): String {
        val legacy = prefs.str(K_LEGACY) ?: return key          // only one account has ever been seen
        val acc = current(prefs) ?: return key
        return if (acc == legacy) key else "$key@$acc"
    }

    private fun names(prefs: Prefs): JSONObject = try { JSONObject(prefs.str(K_NAMES) ?: "{}") } catch (e: Exception) { JSONObject() }

    const val MAX_NAME = 20
}
