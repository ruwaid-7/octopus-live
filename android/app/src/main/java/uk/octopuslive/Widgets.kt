package uk.octopuslive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Home-screen widgets, all in the app's design (card surface, light and dark):
 *  - OctopusWidget: live watts, today's kWh, electricity and gas bars (the original widget)
 *  - DialWidget: the Home dial with live power and the cost per hour
 *  - EnergyWidget: electricity and gas right now, side by side
 *  - CostWidget: today's cost so far, and the price now and next
 *  - ChartWidget: today's 48 half-hours with the peak
 *
 * They all redraw together after every fetch ([updateAll]). While the live notification is
 * running that's about once a minute; otherwise Android asks them to update every 30 minutes and
 * they fetch a reading themselves. Tapping any widget starts live updates and refreshes
 * (or opens the app, if chosen in settings).
 *
 * The dial and the chart are pictures drawn with the app's own ArcGaugeView and SlotBarsView,
 * because widgets can't show custom views. They use the light or dark colours in force when drawn.
 */
object Widgets {

    private val PROVIDERS = listOf(
        OctopusWidget::class.java, DialWidget::class.java, EnergyWidget::class.java,
        CostWidget::class.java, ChartWidget::class.java,
    )

    /** Redraws every placed widget. Call on the main thread (the dial and chart draw views). */
    fun updateAll(context: Context) {
        val c = context.applicationContext
        val manager = AppWidgetManager.getInstance(c)
        val placed = PROVIDERS.associateWith { manager.getAppWidgetIds(ComponentName(c, it)) }
        if (placed.values.all { it.isEmpty() }) return
        val s = Snapshot(c)
        for ((cls, ids) in placed) for (id in ids) {
            val views = when (cls) {
                DialWidget::class.java -> dial(s)
                EnergyWidget::class.java -> energy(s)
                CostWidget::class.java -> cost(s)
                ChartWidget::class.java -> chart(s, manager.getAppWidgetOptions(id))
                else -> live(s)
            }
            manager.updateAppWidget(id, views)
        }
    }

    /** Every widget's onUpdate: redraw, then fetch in the background if the reading is old. */
    fun onUpdate(context: Context, receiver: BroadcastReceiver) {
        val c = context.applicationContext
        updateAll(c)
        val prefs = Prefs(c)
        val stale = System.currentTimeMillis() - (prefs.loadReading()?.fetchedAt ?: 0L) > 10 * 60 * 1000L
        if (prefs.running || !stale || !prefs.isConfigured) return
        // Fetch without the live service, then redraw on the main thread.
        val pending = receiver.goAsync()
        Thread {
            try {
                prefs.saveReading(if (prefs.devMode) DevData.reading(c) else OctopusApi(c).fetch())
                prefs.lastError = null
            } catch (e: Exception) {
                prefs.lastError = e.message ?: e.javaClass.simpleName
            }
            // Prices, today's cost and half-hours for the cost, dial and chart widgets
            try {
                if (prefs.devMode) Insights(c).save(DevData.insights(c)) else Insights(c).refresh()
            } catch (e: Exception) {}
            Handler(Looper.getMainLooper()).post {
                try { updateAll(c) } finally { pending.finish() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- what the widgets show

    /** One read of everything the widgets need. */
    private class Snapshot(val c: Context) {
        val prefs = Prefs(c)
        val r: Reading? = prefs.loadReading()
        val error: String? = prefs.lastError
        val hasKey = prefs.isConfigured
        /** Costs, prices and half-hours need the Octopus API key (or test data). */
        val canCost = !prefs.apiKey.isNullOrBlank() || prefs.devMode
        val ageMin = if (r != null) (System.currentTimeMillis() - r.fetchedAt) / 60_000 else -1L
        private val loaded = Insights(c).load()
        /** Insights, if they were worked out today (yesterday's "today" figures are no use). */
        val today: InsightsData? = loaded?.takeIf {
            Instant.ofEpochMilli(it.updated).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        }
        /** Price now and next don't depend on the day's figures. */
        val prices: InsightsData? = loaded
        val hasGas = r?.gasTodayKwh != null

        /** True when something is wrong or missing and the status line should say so. */
        val problem get() = !hasKey || r == null || (error != null && ageMin > 5)

        /** The original widget's status line. */
        fun status(): String {
            val readAt = r?.readAt ?: "--:--"
            return when {
                !hasKey -> "Tap to add your API key"
                error != null && (r == null || ageMin > 5) -> "⚠ $error"
                r == null -> "Tap to load"
                !prefs.running && ageMin > 30 -> "Meter $readAt · tap for live"
                prefs.running -> "● Live · meter $readAt"
                else -> "Meter $readAt"
            }
        }

        /** Home's line under the electricity number. */
        fun perHour(): String {
            val watts = r?.demandWatts
            val price = prices?.priceNow
            return when {
                problem -> status()
                watts != null && price != null -> "About ${money(watts / 1000.0 * price)} an hour"
                else -> "Meter reading at ${r?.readAt}"
            }
        }

        /** Start/refresh live updates (or open the app, if chosen); always open the app if there's no key. */
        val tap: PendingIntent = if (hasKey && !prefs.widgetOpensApp) {
            PendingIntent.getForegroundService(
                c, 10, Intent(c, LiveService::class.java).setAction(LiveService.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                c, 11, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    // ---------------------------------------------------------------- the original widget

    private fun live(s: Snapshot): RemoteViews {
        val c = s.c
        val r = s.r
        val views = RemoteViews(c.packageName, R.layout.widget_live)
        val watts = r?.demandWatts
        views.setTextViewText(R.id.widgetDemand, if (watts != null) LiveService.formatWatts(watts) else "-- W")
        views.textColour(c, R.id.widgetDemand, levelColour(watts))
        views.setProgressBar(R.id.widgetBar, BAR_MAX_WATTS, (watts ?: 0).coerceIn(0, BAR_MAX_WATTS), false)
        views.setTextViewText(R.id.widgetToday, if (r != null) String.format(Locale.UK, "%.2f kWh", r.todayKwh) else "-- kWh")
        views.setTextViewText(R.id.widgetFooter, s.status())

        // Gas: today's total (updates every 30 min); hidden when there's no smart gas meter
        val gas = r?.gasTodayKwh
        if (gas != null) {
            views.setViewVisibility(R.id.widgetGas, View.VISIBLE)
            views.setTextViewText(R.id.widgetGas, String.format(Locale.UK, "Gas %.2f kWh", gas))
            // Gas bar: average gas power over the latest half-hour (gas meters report every 30 min)
            val kw = (r.gasKw ?: 0.0).coerceIn(0.0, GAS_BAR_MAX_KW)
            views.setViewVisibility(R.id.widgetGasBarRow, View.VISIBLE)
            views.setProgressBar(R.id.widgetGasBar, 100, (kw / GAS_BAR_MAX_KW * 100).toInt(), false)
        } else {
            views.setViewVisibility(R.id.widgetGas, View.GONE)
            views.setViewVisibility(R.id.widgetGasBarRow, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widgetRoot, s.tap)
        return views
    }

    // ---------------------------------------------------------------- the Home dial

    private fun dial(s: Snapshot): RemoteViews {
        val views = RemoteViews(s.c.packageName, R.layout.widget_dial)
        views.setImageViewBitmap(R.id.dialImage, dialBitmap(s.c, s.r?.demandWatts))
        views.setTextViewText(R.id.dialSub, s.perHour())
        views.setOnClickPendingIntent(R.id.widgetRoot, s.tap)
        return views
    }

    /** ArcGaugeView with the live number in the middle, as on Home. */
    private fun dialBitmap(c: Context, watts: Int?): Bitmap {
        val gauge = ArcGaugeView(c).apply { setWatts(watts) }
        gauge.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        gauge.layout(0, 0, gauge.measuredWidth, gauge.measuredHeight)
        val bmp = Bitmap.createBitmap(gauge.measuredWidth, gauge.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        gauge.draw(canvas)

        val dp = c.resources.displayMetrics.density
        val (num, unit) = splitWatts(watts)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.getColor(R.color.text_primary); textSize = 44 * dp
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c.getColor(R.color.text_secondary); textSize = 18 * dp
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val gap = if (unit.isEmpty()) 0f else 4 * dp
        val width = big.measureText(num) + gap + small.measureText(unit)
        val x = (bmp.width - width) / 2
        val baseline = 118 * dp   // the dial's centre line, level with the "0" and "4 kW" labels
        canvas.drawText(num, x, baseline, big)
        canvas.drawText(unit, x + big.measureText(num) + gap, baseline, small)
        return bmp
    }

    // ---------------------------------------------------------------- electricity + gas

    private fun energy(s: Snapshot): RemoteViews {
        val c = s.c
        val r = s.r
        val views = RemoteViews(c.packageName, R.layout.widget_energy)

        val watts = r?.demandWatts
        val (num, unit) = splitWatts(watts)
        views.setTextViewText(R.id.energyElecValue, num)
        views.setTextViewText(R.id.energyElecUnit, unit)
        views.textColour(c, R.id.energyElecValue, levelColour(watts))
        views.setTextViewText(R.id.energyElecSub, s.perHour())

        if (s.hasGas && r != null) {
            // Gas: average over the latest half-hour; the flame lights up while the gas is on
            val on = r.gasRunMinutes > 0
            views.setImageViewResource(R.id.energyRightIcon, R.drawable.ic_flame)
            views.setInt(R.id.energyRightIcon, "setColorFilter", c.getColor(if (on) R.color.gas_accent else R.color.text_muted))
            views.setTextViewText(R.id.energyRightLabel, "GAS")
            views.setTextViewText(R.id.energyRightValue, String.format(Locale.UK, "%.1f", r.gasKw ?: 0.0))
            views.setTextViewText(R.id.energyRightUnit, "kW")
            views.textColour(c, R.id.energyRightValue, R.color.text_primary)
            views.setTextViewText(R.id.energyRightSub, when {
                on -> "On for ${duration(r.gasRunMinutes)}"
                r.gasReadAt != null -> "Average to ${r.gasReadAt}"
                else -> "30-min average"
            })
        } else {
            // No smart gas meter: today's electricity instead
            views.setImageViewResource(R.id.energyRightIcon, R.drawable.ic_bolt)
            views.setInt(R.id.energyRightIcon, "setColorFilter", c.getColor(R.color.text_muted))
            views.setTextViewText(R.id.energyRightLabel, "TODAY")
            views.setTextViewText(R.id.energyRightValue, r?.let { String.format(Locale.UK, "%.2f", it.todayKwh) } ?: "—")
            views.setTextViewText(R.id.energyRightUnit, if (r != null) "kWh" else "")
            views.textColour(c, R.id.energyRightValue, R.color.text_primary)
            views.setTextViewText(R.id.energyRightSub, s.today?.elecCostToday?.let { "${money(it * 100)} so far" } ?: "")
        }
        views.setOnClickPendingIntent(R.id.widgetRoot, s.tap)
        return views
    }

    // ---------------------------------------------------------------- cost and price

    private fun cost(s: Snapshot): RemoteViews {
        val c = s.c
        val r = s.r
        val d = s.today
        val views = RemoteViews(c.packageName, R.layout.widget_cost)

        // Today so far: money if the costs are known, otherwise kWh (like the Usage tab)
        val elecKwh = r?.todayKwh ?: d?.todaySlots?.sum()
        val gasKwh = r?.gasTodayKwh ?: d?.todayGasSlots?.takeIf { it.isNotEmpty() }?.sum()
        val elecCost = d?.elecCostToday
        val gasCost = d?.gasCostToday
        views.setTextViewText(R.id.costTotal, when {
            elecCost != null -> money((elecCost + (gasCost ?: 0.0)) * 100)
            elecKwh != null -> String.format(Locale.UK, "%.2f kWh", elecKwh + (gasKwh ?: 0.0))
            else -> "—"
        })
        views.setTextViewText(R.id.costElec, elecCost?.let { money(it * 100) }
            ?: elecKwh?.let { String.format(Locale.UK, "%.2f kWh", it) } ?: "—")
        views.setViewVisibility(R.id.costGasRow, if (s.hasGas) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.costGas, gasCost?.let { money(it * 100) }
            ?: gasKwh?.let { String.format(Locale.UK, "%.1f kWh", it) } ?: "—")

        // Price now, and the next price (green with an arrow when it's lower), as on Home
        val p = s.prices
        views.setTextViewText(R.id.costPrice, p?.priceNow?.let { String.format(Locale.UK, "%.1fp", it) } ?: "—")
        val next = if (p?.priceNext != null && p.priceNextAt != null)
            c.getString(R.string.home_next_price, String.format(Locale.UK, "%.1fp", p.priceNext), Insights.formatTime(p.priceNextAt)) else null
        val cheaper = p?.priceNow != null && p.priceNext != null && p.priceNext < p.priceNow
        val note = when {
            !s.canCost -> "Add your API key for costs"
            next != null -> if (cheaper) "↓ $next" else next
            else -> null
        }
        views.setViewVisibility(R.id.costNext, if (note != null) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.costNext, note ?: "")
        views.textColour(c, R.id.costNext, if (cheaper && s.canCost) R.color.elec_low else R.color.text_muted)
        views.setOnClickPendingIntent(R.id.widgetRoot, s.tap)
        return views
    }

    // ---------------------------------------------------------------- today's half-hours

    private fun chart(s: Snapshot, options: Bundle): RemoteViews {
        val c = s.c
        val views = RemoteViews(c.packageName, R.layout.widget_chart)
        val d = s.today
        val slots = d?.todaySlots.orEmpty()

        val kwh = s.r?.todayKwh ?: slots.takeIf { it.isNotEmpty() }?.sum()
        views.setTextViewText(R.id.chartTotal, listOfNotNull(
            kwh?.let { String.format(Locale.UK, "%.2f kWh", it) },
            d?.elecCostToday?.let { money(it * 100) },
        ).joinToString(" · ").ifEmpty { "—" })

        // Size the picture to the widget (portrait size, in dp), less padding, header and footer
        val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
        views.setImageViewBitmap(R.id.chartImage, chartBitmap(c, slots, (wDp - 28).coerceAtLeast(120), (hDp - 92f).coerceIn(28f, 140f)))

        // Peak half-hour, as on the Usage tab
        val peak = slots.indices.maxByOrNull { slots[it] }?.takeIf { slots[it] > 0 }
        views.setTextViewText(R.id.chartPeak, when {
            !s.canCost -> "Add your API key to see half-hours"
            peak == null -> "No half-hours yet today"
            else -> listOfNotNull(
                c.getString(R.string.usage_peak, slotTime(peak), slotTime(peak + 1)),
                String.format(Locale.UK, "%.2f kW avg", slots[peak] * 2),
                d?.todaySlotPence?.getOrNull(peak)?.let { money(it) },
            ).joinToString(" · ")
        })
        views.setOnClickPendingIntent(R.id.widgetRoot, s.tap)
        return views
    }

    private fun chartBitmap(c: Context, slots: List<Double>, widthDp: Int, chartDp: Float): Bitmap {
        val dp = c.resources.displayMetrics.density
        val bars = SlotBarsView(c).apply { this.chartDp = chartDp; setSlots(slots) }
        bars.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * dp).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        bars.layout(0, 0, bars.measuredWidth, bars.measuredHeight)
        val bmp = Bitmap.createBitmap(bars.measuredWidth, bars.measuredHeight, Bitmap.Config.ARGB_8888)
        bars.draw(Canvas(bmp))
        return bmp
    }

    // ---------------------------------------------------------------- helpers

    /** Electricity levels everywhere: green < 0.5 kW, amber < 2 kW, red above. */
    private fun levelColour(watts: Int?) = when {
        watts == null -> R.color.text_primary
        watts < 500 -> R.color.elec_low
        watts < 2000 -> R.color.elec_mid
        else -> R.color.elec_high
    }

    /** "850" + "W" or "3.42" + "kW", as on Home. */
    private fun splitWatts(watts: Int?): Pair<String, String> = when {
        watts == null -> "—" to ""
        watts < 1000 -> "$watts" to "W"
        else -> String.format(Locale.UK, "%.2f", watts / 1000.0) to "kW"
    }

    /** Text colour from a colour resource: follows light/dark straight away on Android 12+. */
    private fun RemoteViews.textColour(c: Context, id: Int, colourRes: Int) {
        if (Build.VERSION.SDK_INT >= 31) setColorStateList(id, "setTextColor", colourRes)
        else setTextColor(id, c.getColor(colourRes))
    }

    /** Pence to a short money string: "5p", "£1.12". */
    private fun money(pence: Double): String =
        if (pence < 100) String.format(Locale.UK, "%.0fp", pence) else String.format(Locale.UK, "£%.2f", pence / 100)

    private fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    private fun slotTime(i: Int) = String.format(Locale.UK, "%02d:%02d", i / 2, if (i % 2 == 0) 0 else 30)

    private const val BAR_MAX_WATTS = 4000
    private const val GAS_BAR_MAX_KW = 10.0
}

/** The Home dial with live power and the cost per hour (2 x 2). */
class DialWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.onUpdate(context, this)
}

/** Electricity and gas right now, side by side (4 x 1). */
class EnergyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.onUpdate(context, this)
}

/** Today's cost so far, and the price now and next (2 x 2). */
class CostWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.onUpdate(context, this)
}

/** Today's half-hours as bars, with the peak (4 x 2). Redrawn when resized, to fit. */
class ChartWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.onUpdate(context, this)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.updateAll(context)
}
