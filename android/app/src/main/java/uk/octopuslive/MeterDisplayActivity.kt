package uk.octopuslive

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Meter display: the layout of the in-home display that comes with a smart meter.
 *  - A big dial: live electricity (Now), or spend so far (Today, Week) against your budget or a typical day.
 *  - Three lights: green, amber, red.
 *  - Now / Today / Week buttons; tap the dial to switch between £ and kWh.
 *  - Gas beside it, the price now and next, and a daily budget.
 * The screen stays on while it's open, so an old phone or tablet on a stand works like the real thing.
 */
class MeterDisplayActivity : Activity() {

    private lateinit var prefs: Prefs
    private val dp by lazy { resources.displayMetrics.density }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 30_000) }
    }

    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var dial: DialView
    private lateinit var lights: List<View>
    private lateinit var periodButtons: List<TextView>
    private lateinit var gasValue: TextView
    private lateinit var gasSub: TextView
    private lateinit var gasCard: View
    private lateinit var priceNow: TextView
    private lateinit var priceNext: TextView
    private lateinit var budgetValue: TextView
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        build()
        render()
    }

    override fun onResume() {
        super.onResume()
        LiveService.listener = { _ -> runOnUiThread { render() } }
        handler.post(tick)
    }

    override fun onPause() {
        LiveService.listener = null
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ================================================================= layout

    private fun build() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Top bar: close, clock, live status
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconButton(R.drawable.ic_back, R.string.ob_back) { finish() }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = -px(10) })
        clock = text("", 22f, R.color.text_primary, medium = true)
        top.addView(clock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(6) })
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(6), px(12), px(6))
            background = getDrawable(R.drawable.bg_pill)
        }
        statusDot = View(this).apply { background = getDrawable(R.drawable.bg_dot) }
        pill.addView(statusDot, LinearLayout.LayoutParams(px(8), px(8)))
        status = text("", 13f, R.color.text_secondary, medium = true)
        pill.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        top.addView(pill)

        // Traffic lights
        val lightRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        lights = listOf(R.color.elec_low, R.color.elec_mid, R.color.elec_high).map { c ->
            View(this).apply { tag = c }.also {
                lightRow.addView(it, LinearLayout.LayoutParams(px(14), px(14)).apply { marginStart = px(7); marginEnd = px(7) })
            }
        }

        dial = DialView(this).apply {
            isClickable = true
            contentDescription = getString(R.string.md_dial_desc)
            setOnClickListener { prefs.setStr(K_UNITS, if (showCost()) "kwh" else "cost"); render() }
        }

        // Now / Today / Week
        val periods = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(R.color.card_surface, 14f, stroke = R.color.card_stroke)
        }
        periodButtons = listOf(R.string.md_now, R.string.md_today, R.string.md_week).mapIndexed { i, label ->
            text(getString(label), 15f, R.color.text_secondary, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
                setOnClickListener { prefs.setLong(K_PERIOD, i.toLong()); render() }
            }.also { periods.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        // Gas card
        val gas = LinearLayout(this, null, 0, R.style.Card).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gas.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_flame)
            imageTintList = ColorStateList.valueOf(getColor(R.color.gas_accent))
        }, LinearLayout.LayoutParams(px(28), px(28)))
        val gasTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gasTexts.addView(text(getString(R.string.legend_gas), 13f, R.color.text_secondary))
        gasValue = text("—", 20f, R.color.text_primary, medium = true)
        gasTexts.addView(gasValue)
        gasSub = text("", 13f, R.color.text_muted)
        gasTexts.addView(gasSub)
        gas.addView(gasTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
        gasCard = gas

        // Price + budget
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val priceCard = LinearLayout(this, null, 0, R.style.Card).apply { orientation = LinearLayout.VERTICAL }
        priceCard.addView(text(getString(R.string.md_price), 13f, R.color.text_secondary))
        priceNow = text("—", 20f, R.color.text_primary, medium = true)
        priceCard.addView(priceNow)
        priceNext = text("", 13f, R.color.text_muted)
        priceCard.addView(priceNext)
        info.addView(priceCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = px(6) })
        val budgetCard = LinearLayout(this, null, 0, R.style.Card).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            foreground = getDrawable(R.drawable.bg_row)
            setOnClickListener { editBudget() }
        }
        budgetCard.addView(text(getString(R.string.md_budget), 13f, R.color.text_secondary))
        budgetValue = text("—", 20f, R.color.text_primary, medium = true)
        budgetCard.addView(budgetValue)
        budgetCard.addView(text(getString(R.string.md_budget_tap), 13f, R.color.text_muted))
        info.addView(budgetCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = px(6) })

        hint = text("", 13f, R.color.text_muted).apply { gravity = Gravity.CENTER }

        // Arrange: one column in portrait, dial on the left in landscape
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(24))
        }
        root.addView(top)
        if (!landscape) {
            root.addView(lightRow, full(top = 18))
            root.addView(dial, full(top = 6))
            root.addView(periods, full(top = 8))
            root.addView(gasCard, full(top = 14))
            root.addView(info, full(top = 12))
            root.addView(hint, full(top = 14))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(lightRow, full(top = 4))
            left.addView(dial, full(top = 4))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(periods, full())
            right.addView(gasCard, full(top = 12))
            right.addView(info, full(top = 12))
            right.addView(hint, full(top = 12))
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            row.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(20) })
            root.addView(row, full(top = 8))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.app_bg))
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    // ================================================================= data

    private fun period() = prefs.long(K_PERIOD).toInt().coerceIn(NOW, WEEK)
    private fun hasCosts(ins: InsightsData?) = ins?.elecCostToday != null
    private fun showCost() = prefs.str(K_UNITS) != "kwh"

    private fun render() {
        if (!::dial.isInitialized) return
        val r = prefs.loadReading()
        val ins = Insights(this).load()
        val period = period()
        val cost = showCost() && hasCosts(ins)
        val budget = prefs.dailyBudget.takeIf { it > 0f }?.toDouble()

        clock.text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
        val (dotColour, label) = when {
            !prefs.isConfigured -> R.color.nav_inactive to getString(R.string.md_not_set_up)
            !prefs.running -> R.color.nav_inactive to getString(R.string.md_paused)
            r == null -> R.color.nav_inactive to getString(R.string.connecting)
            prefs.lastError != null -> R.color.elec_high to getString(R.string.md_last, r.readAt)
            r.readAtMs != null && System.currentTimeMillis() - r.readAtMs > LiveService.OFFLINE_MS -> R.color.elec_mid to getString(R.string.md_last, r.readAt)
            else -> R.color.elec_low to getString(R.string.md_live, r.readAt)
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(dotColour))
        status.text = label

        periodButtons.forEachIndexed { i, b ->
            val on = i == period
            b.setTextColor(getColor(if (on) R.color.btn_primary_text else R.color.text_secondary))
            b.background = if (on) rounded(R.color.brand_pink, 10f) else null
        }

        // ---- dial and lights
        var light: Int? = null
        when (period) {
            NOW -> {
                val w = r?.demandWatts
                dial.scaleLabels = listOf("0", "1", "2", "3", "4")
                dial.fraction = (w ?: 0) / NOW_FULL_W
                dial.marker = null
                light = w?.let { if (it >= HIGH_W) 2 else if (it >= MID_W) 1 else 0 }
                val perHour = if (w != null && ins?.priceNow != null) w / 1000.0 * ins.priceNow else null
                if (cost && perHour != null) {
                    dial.value = money(perHour)
                    dial.unit = getString(R.string.md_per_hour)
                    dial.caption = w?.let { formatKw(it) } ?: ""
                } else {
                    dial.value = w?.let { trimKw(it / 1000.0) } ?: "—"
                    dial.unit = "kW"
                    dial.caption = perHour?.let { getString(R.string.md_costs_per_hour, money(it)) } ?: ""
                }
            }
            else -> {
                // Spend (or electricity used) so far, against your budget or a typical day
                val today = r?.todayKwh
                val past = ins?.history.orEmpty().filter { it.elecKwh > 0 }
                val pastWeek = past.filter { LocalDate.parse(it.date) >= LocalDate.now().minusDays(6) }
                val dayFraction = LocalTime.now().toSecondOfDay() / 86400.0
                val days = if (period == TODAY) 1.0 else 7.0
                val elapsed = if (period == TODAY) dayFraction else (pastWeek.size + dayFraction) / 7.0

                val so: Double?
                val typical: Double?
                if (cost) {
                    val todayCost = (ins?.elecCostToday ?: 0.0) + (ins?.gasCostToday ?: 0.0)
                    so = if (period == TODAY) todayCost else todayCost + pastWeek.sumOf { (it.elecCost ?: 0.0) + (it.gasCost ?: 0.0) }
                    typical = past.mapNotNull { d -> d.elecCost?.let { it + (d.gasCost ?: 0.0) } }.takeIf { it.isNotEmpty() }?.average()
                } else {
                    so = today?.let { if (period == TODAY) it else it + pastWeek.sumOf { d -> d.elecKwh } }
                    typical = past.map { it.elecKwh }.takeIf { it.isNotEmpty() }?.average()
                }
                val target = (if (cost && budget != null) budget else typical)?.times(days)
                val full = (target ?: so ?: 1.0).coerceAtLeast(0.01) * 1.5
                dial.fraction = ((so ?: 0.0) / full).toFloat()
                dial.marker = target?.let { (it / full).toFloat() }
                dial.scaleLabels = emptyList()
                dial.value = so?.let { if (cost) money(it * 100) else String.format(Locale.UK, "%.1f", it) } ?: "—"
                dial.unit = if (cost) getString(if (period == TODAY) R.string.md_spent_today else R.string.md_spent_week)
                    else getString(if (period == TODAY) R.string.md_kwh_today else R.string.md_kwh_week)
                dial.caption = when {
                    target == null -> ""
                    cost && budget != null -> getString(R.string.md_budget_of, money(target * 100))
                    cost -> getString(R.string.md_typical, money(target * 100))
                    else -> getString(R.string.md_typical_kwh, String.format(Locale.UK, "%.1f", target))
                }
                // On track for the target at this point in the day/week?
                if (target != null && so != null && elapsed > 0.02) {
                    val pace = so / (target * elapsed)
                    light = if (pace <= 1.0) 0 else if (pace <= 1.15) 1 else 2
                }
            }
        }
        dial.fillColour = getColor(when (light) { 0 -> R.color.elec_low; 1 -> R.color.elec_mid; 2 -> R.color.elec_high; else -> R.color.brand_pink })
        dial.invalidate()
        lights.forEachIndexed { i, v ->
            val c = getColor(v.tag as Int)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == light) c else (c and 0x00FFFFFF) or 0x33000000)
            }
        }

        // ---- gas
        gasCard.visibility = if (r?.gasTodayKwh != null) View.VISIBLE else View.GONE
        if (r?.gasTodayKwh != null) {
            when (period) {
                NOW -> {
                    val on = r.gasRunMinutes > 0
                    gasValue.text = if (on) getString(R.string.md_gas_on, duration(r.gasRunMinutes)) else getString(R.string.md_gas_off)
                    gasValue.setTextColor(getColor(if (on) R.color.gas_accent else R.color.text_primary))
                    gasSub.text = listOfNotNull(
                        r.gasKw?.takeIf { on }?.let { String.format(Locale.UK, "%.1f kW", it) },
                        r.gasReadAt?.let { getString(R.string.md_gas_to, it) },
                    ).joinToString(" · ")
                }
                else -> {
                    gasValue.setTextColor(getColor(R.color.text_primary))
                    val week = ins?.history.orEmpty().filter { LocalDate.parse(it.date) >= LocalDate.now().minusDays(6) }
                    val kwh = if (period == TODAY) r.gasTodayKwh else r.gasTodayKwh + week.sumOf { it.gasKwh ?: 0.0 }
                    val gasCost = ins?.gasCostToday?.let { if (period == TODAY) it else it + week.sumOf { d -> d.gasCost ?: 0.0 } }
                    gasValue.text = if (cost && gasCost != null) money(gasCost * 100) else String.format(Locale.UK, "%.1f kWh", kwh)
                    gasSub.text = if (cost && gasCost != null) String.format(Locale.UK, "%.1f kWh", kwh)
                        else r.gasReadAt?.let { getString(R.string.md_gas_to, it) } ?: ""
                }
            }
        }

        // ---- price and budget
        priceNow.text = ins?.priceNow?.let { String.format(Locale.UK, "%.1fp", it) } ?: "—"
        priceNext.text = if (ins?.priceNext != null && ins.priceNextAt != null)
            getString(R.string.md_price_next, String.format(Locale.UK, "%.1fp", ins.priceNext), Insights.formatTime(ins.priceNextAt))
            else getString(R.string.md_per_kwh)
        budgetValue.text = budget?.let { getString(R.string.md_budget_day, money(it * 100)) } ?: getString(R.string.md_budget_none)

        hint.text = when {
            !prefs.isConfigured -> getString(R.string.md_hint_setup)
            !prefs.running -> getString(R.string.md_hint_paused)
            !hasCosts(ins) -> getString(R.string.md_hint_costs)
            else -> getString(R.string.md_hint_tap)
        }
    }

    private fun editBudget() {
        val input = EditText(this, null, 0, R.style.InputField).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "5.00"
            prefs.dailyBudget.takeIf { it > 0f }?.let { setText(String.format(Locale.UK, "%.2f", it)) }
        }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.md_budget_title)
            .setMessage(R.string.md_budget_msg)
            .setView(box)
            .setPositiveButton(R.string.md_save) { _, _ ->
                prefs.dailyBudget = input.text.toString().replace("£", "").trim().toFloatOrNull()?.coerceIn(0f, 1000f) ?: 0f
                render()
            }
            .setNeutralButton(R.string.md_remove) { _, _ -> prefs.dailyBudget = 0f; render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ================================================================= helpers

    private fun text(s: String, size: Float, colour: Int, medium: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(getColor(colour))
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun iconButton(icon: Int, desc: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
        contentDescription = getString(desc)
        setPadding(px(10), px(10), px(10), px(10))
        background = getDrawable(R.drawable.bg_row)
        setOnClickListener { onClick() }
    }

    private fun rounded(colour: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = radius * dp
        setColor(getColor(colour))
        stroke?.let { setStroke(px(1), getColor(it)) }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun px(v: Int) = (v * dp).toInt()

    /** Pence to "31p" or "£1.12". */
    private fun money(pence: Double): String =
        if (pence < 100) String.format(Locale.UK, "%.0fp", pence) else String.format(Locale.UK, "£%.2f", pence / 100)

    private fun trimKw(kw: Double) = String.format(Locale.UK, "%.2f", kw)
    private fun formatKw(w: Int) = if (w < 1000) "$w W" else String.format(Locale.UK, "%.2f kW", w / 1000.0)

    private fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    companion object {
        private const val NOW = 0
        private const val TODAY = 1
        private const val WEEK = 2
        private const val K_PERIOD = "displayPeriod"
        private const val K_UNITS = "displayUnits"
        private const val NOW_FULL_W = 4000f   // the dial is full at 4 kW
        // The same levels as the bar on the Home tab
        private const val MID_W = 500
        private const val HIGH_W = 2000
    }
}
