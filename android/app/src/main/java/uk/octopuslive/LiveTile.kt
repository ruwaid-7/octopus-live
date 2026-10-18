package uk.octopuslive

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.Locale

/**
 * Quick Settings tile: live watts as the label, today's usage (and cost) underneath.
 * Tap to refresh (or to open the app if live updates aren't running).
 */
class LiveTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        if (prefs.running) {
            try { startService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_REFRESH)) } catch (e: Exception) {}
            update()
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val r = prefs.loadReading()
        val ins = Insights(this).load()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_bolt)
        tile.label = r?.demandWatts?.let { LiveService.formatWatts(it) } ?: getString(R.string.app_name)
        tile.state = if (prefs.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                r == null -> "Tap to start"
                ins?.elecCostToday != null -> String.format(Locale.UK, "Today %.1f kWh · £%.2f", r.todayKwh, ins.elecCostToday)
                else -> String.format(Locale.UK, "Today %.1f kWh", r.todayKwh)
            }
        }
        tile.updateTile()
    }
}
