package uk.octopuslive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the live notification after a reboot or app update, if it was running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        // Redraw any widgets already on the home screen with the (possibly new) layout
        OctopusWidget.updateAll(context)
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (relevant && prefs.startOnBoot && prefs.running && !prefs.apiKey.isNullOrBlank()) {
            try {
                LiveService.start(context)
            } catch (e: Exception) {
                // Some phones block starts from the background; the user can open the app to restart.
            }
        }
    }
}
