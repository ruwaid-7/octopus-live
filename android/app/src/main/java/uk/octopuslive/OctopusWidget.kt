package uk.octopuslive

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * The original home-screen widget: live watts, today's kWh, electricity and gas bars and the
 * meter-reading time. Drawing and refreshing are shared with the other widgets in [Widgets].
 */
class OctopusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) =
        Widgets.onUpdate(context, this)

    companion object {
        /** Redraws every widget (all kinds). */
        fun updateAll(context: Context) = Widgets.updateAll(context)
    }
}
