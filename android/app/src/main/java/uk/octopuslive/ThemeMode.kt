package uk.octopuslive

import android.app.Activity
import android.content.res.Configuration

/**
 * The app's Device / Light / Dark choice (Settings → Preferences). Light and Dark pick the app's
 * day or night colours whatever the phone is set to. Widgets and notifications are drawn by the
 * home screen and the system, so they keep following the phone.
 */
object ThemeMode {
    const val DEVICE = 0
    const val LIGHT = 1
    const val DARK = 2

    /** Call from an activity's attachBaseContext, after super, before anything reads resources. */
    fun apply(activity: Activity) {
        val night = when (Prefs(activity).theme) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> return
        }
        // Only the night bits are set; everything else in this override is "unchanged"
        activity.applyOverrideConfiguration(Configuration().apply { uiMode = night })
    }
}
