package uk.octopuslive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * Home-screen widget: live watts, today's kWh and the meter-reading time.
 *
 * While the live notification is running, the widget is refreshed after every fetch
 * (about once a minute while the screen is on). When it isn't running, Android asks the
 * widget to update every 30 minutes and it fetches a reading itself.
 * Tapping the widget starts live updates and refreshes immediately.
 */
class OctopusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, manager, appWidgetIds)

        val prefs = Prefs(context)
        val stale = System.currentTimeMillis() - (prefs.loadReading()?.fetchedAt ?: 0L) > 10 * 60 * 1000L
        if (!prefs.running && stale && prefs.isConfigured) {
            // Fetch in the background without the live service, then redraw.
            val pending = goAsync()
            Thread {
                try {
                    val r = OctopusApi(context).fetch()
                    prefs.saveReading(r)
                    prefs.lastError = null
                } catch (e: Exception) {
                    prefs.lastError = e.message ?: e.javaClass.simpleName
                } finally {
                    updateAll(context)
                    pending.finish()
                }
            }.start()
        }
    }

    companion object {

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OctopusWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = Prefs(context)
            val r = prefs.loadReading()
            val error = prefs.lastError
            val hasKey = prefs.isConfigured

            val views = RemoteViews(context.packageName, R.layout.widget_live)

            val watts = r?.demandWatts
            views.setTextViewText(R.id.widgetDemand, if (watts != null) LiveService.formatWatts(watts) else "-- W")
            val colorRes = when {
                watts == null -> R.color.octopus_text_primary
                watts < 500 -> R.color.power_Low_Green
                watts <= 2000 -> R.color.power_Normal_Amber
                else -> R.color.power_High_Red
            }
            views.setTextColor(R.id.widgetDemand, context.getColor(colorRes))
            views.setProgressBar(R.id.widgetBar, BAR_MAX_WATTS, (watts ?: 0).coerceIn(0, BAR_MAX_WATTS), false)
            views.setTextViewText(
                R.id.widgetToday,
                if (r != null) String.format(Locale.UK, "%.2f kWh", r.todayKwh) else "-- kWh",
            )

            val ageMin = if (r != null) (System.currentTimeMillis() - r.fetchedAt) / 60_000 else -1L
            val readAt = r?.readAt ?: "--:--"
            val footer = when {
                !hasKey -> "Tap to add your API key"
                error != null && (r == null || ageMin > 5) -> "⚠ $error"
                r == null -> "Tap to load"
                !prefs.running && ageMin > 30 -> "Meter $readAt · tap for live"
                prefs.running -> "● Live · meter $readAt"
                else -> "Meter $readAt"
            }
            views.setTextViewText(R.id.widgetFooter, footer)

            // Gas: today's total (updates every 30 min); hidden when there's no smart gas meter
            val gas = r?.gasTodayKwh
            if (gas != null) {
                views.setViewVisibility(R.id.widgetGas, View.VISIBLE)
                views.setTextViewText(R.id.widgetGas, String.format(Locale.UK, "Gas %.2f kWh", gas))
            } else {
                views.setViewVisibility(R.id.widgetGas, View.GONE)
            }

            // Tap: start/refresh live updates, or open settings if there's no key yet.
            val tap = if (hasKey) {
                PendingIntent.getForegroundService(
                    context, 10,
                    Intent(context, LiveService::class.java).setAction(LiveService.ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getActivity(
                    context, 11, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            views.setOnClickPendingIntent(R.id.widgetRoot, tap)

            for (id in ids) manager.updateAppWidget(id, views)
        }

        private const val BAR_MAX_WATTS = 4000
    }
}
