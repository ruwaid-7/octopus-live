package uk.octopuslive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One screen with four tabs and a bottom bar:
 *  Home (live dashboard), Usage (costs and history), Alerts (warnings), Settings (connection etc.).
 * Switches save straight away; number and text fields save when you leave the field, the tab or the app.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var tab = TAB_HOME
    private var loading = false   // true while filling in controls, so their listeners don't save

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // New install: the welcome steps first (unless we were opened by a relay's "Open in Octopus Live" link)
        val connectLink = intent?.data?.scheme == "octopuslive"
        if (savedInstanceState == null && !connectLink && SetupActivity.needed(prefs)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (prefs.isConfigured) prefs.onboarded = true
        setContentView(R.layout.activity_main)

        setUpNav()
        setUpAlerts()
        setUpUsage()
        setUpSettings()
        tab = savedInstanceState?.getInt(K_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        showTab(tab)
        if (savedInstanceState == null) handleConnectLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectLink(intent)
    }

    /** octopuslive://connect?relay=<address> from the relay's web page: asks, then saves and starts. */
    private fun handleConnectLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "octopuslive" || data.host != "connect") return
        val relay = data.getQueryParameter("relay")?.trim().orEmpty()
        val parsed = Uri.parse(relay)
        if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            Toast.makeText(this, "That link doesn't contain a relay address", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Connect to this relay?")
            .setMessage("Octopus Live will read your live usage from ${parsed.host}.")
            .setPositiveButton("Connect") { _, _ ->
                if (relay != prefs.relayUrl.orEmpty()) prefs.clearCachedApiState()
                prefs.relayUrl = relay
                prefs.onboarded = true
                if (prefs.intervalSeconds > 15) prefs.intervalSeconds = 15
                loadControls()
                showTab(TAB_HOME)
                startWithPermission()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        OctopusWidget.updateAll(this)   // keep widgets in step with the installed version
        resumed = true
        LiveService.listener = { _ -> runOnUiThread { renderAll() } }
        loadControls()
        renderAll()
    }

    private var resumed = false

    override fun onPause() {
        resumed = false
        updateFlameAnimation()
        LiveService.listener = null
        super.onPause()
    }

    @Deprecated("Back closes a Settings section, then goes to Home")
    override fun onBackPressed() {
        when {
            tab == TAB_SETTINGS && settingsSection >= 0 -> showSettingsSection(-1)
            tab != TAB_HOME -> showTab(TAB_HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun renderAll() {
        renderHome()
        renderUsage()
        renderRecentAlerts()
        renderSettingsStatus()
    }

    // ================================================================= tabs

    private val pages by lazy {
        listOf(R.id.pageHome, R.id.pageUsage, R.id.pageAlerts, R.id.pageSettings).map { findViewById<View>(it) }
    }
    private val navIcons by lazy {
        listOf(R.id.navHomeIcon, R.id.navUsageIcon, R.id.navAlertsIcon, R.id.navSettingsIcon).map { findViewById<ImageView>(it) }
    }
    private val navPills by lazy {
        listOf(R.id.navHomePill, R.id.navUsagePill, R.id.navAlertsPill, R.id.navSettingsPill).map { findViewById<View>(it) }
    }
    private val navLabels by lazy {
        listOf(R.id.navHomeLabel, R.id.navUsageLabel, R.id.navAlertsLabel, R.id.navSettingsLabel).map { findViewById<TextView>(it) }
    }

    /** A segment of a small switch: raised and dark when selected, muted otherwise. */
    private fun styleSegment(id: Int, on: Boolean) {
        findViewById<TextView>(id).apply {
            setBackgroundResource(if (on) R.drawable.bg_segment_on else 0)
            setTextColor(getColor(if (on) R.color.text_primary else R.color.text_muted))
            typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun setUpNav() {
        listOf(R.id.navHome, R.id.navUsage, R.id.navAlerts, R.id.navSettings).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { showTab(i) }
        }
    }

    private fun showTab(index: Int) {
        if (tab != index) {
            hideKeyboard()
        }
        if (index == TAB_SETTINGS && (tab == TAB_SETTINGS || tab != index)) showSettingsSection(-1)
        tab = index
        // Selected tab: pink icon and label on a soft pink pill; the others muted
        val active = ColorStateList.valueOf(getColor(R.color.brand_pink))
        val inactive = ColorStateList.valueOf(getColor(R.color.nav_inactive))
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        navIcons.forEachIndexed { i, icon -> icon.imageTintList = if (i == index) active else inactive }
        navPills.forEachIndexed { i, pill -> pill.setBackgroundResource(if (i == index) R.drawable.bg_nav_active else 0) }
        updateFlameAnimation()
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) active else inactive)
            label.typeface = Typeface.create("sans-serif-medium", if (i == index) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ================================================================= home

    private fun renderHome() {
        val prompt = findViewById<View>(R.id.homePrompt)
        val dashboard = findViewById<View>(R.id.homeDashboard)
        val r = prefs.loadReading()
        val ins = Insights(this).load()
        val err = prefs.lastError

        // Status pill
        val dot = findViewById<View>(R.id.homeStatusDot)
        val statusText = findViewById<TextView>(R.id.homeStatusText)
        val now = System.currentTimeMillis()
        val (colour, label) = when {
            !prefs.isConfigured -> R.color.nav_inactive to "Not set up"
            !prefs.running -> R.color.nav_inactive to "Paused"
            err != null && r == null -> R.color.elec_high to "Can't connect"
            r == null -> R.color.nav_inactive to getString(R.string.connecting)
            err != null -> R.color.elec_high to "Last update ${r.readAt}"
            r.readAtMs != null && now - r.readAtMs > LiveService.OFFLINE_MS -> R.color.elec_mid to "Last reading ${r.readAt}"
            else -> R.color.elec_low to "Live · ${r.readAt}"
        }
        dot.backgroundTintList = ColorStateList.valueOf(getColor(colour))
        statusText.text = label
        statusText.setTextColor(getColor(when (colour) {
            R.color.elec_low -> R.color.text_primary
            R.color.nav_inactive -> R.color.text_muted
            else -> colour
        }))
        // An old reading gets an amber outline and tint
        findViewById<View>(R.id.homeStatus).setBackgroundResource(
            if (colour == R.color.elec_mid) R.drawable.bg_status_delayed else R.drawable.bg_pill)

        // Set-up or paused prompt instead of the dashboard
        if (!prefs.isConfigured || (!prefs.running && r == null)) {
            prompt.visibility = View.VISIBLE
            dashboard.visibility = View.GONE
            val setUp = !prefs.isConfigured
            findViewById<TextView>(R.id.homePromptTitle).setText(if (setUp) R.string.setup_title else R.string.paused_title)
            findViewById<TextView>(R.id.homePromptBody).setText(if (setUp) R.string.setup_body else R.string.paused_body)
            findViewById<Button>(R.id.homePromptButton).apply {
                setText(if (setUp) R.string.setup_button else R.string.paused_button)
                setOnClickListener { if (setUp) openSetup(SetupActivity.CHOOSE) else startWithPermission() }
            }
            return
        }
        // Running, or paused with an old reading: show the dashboard (paused shows in the pill)
        prompt.visibility = if (!prefs.running) View.VISIBLE else View.GONE
        if (!prefs.running) {
            findViewById<TextView>(R.id.homePromptTitle).setText(R.string.paused_title)
            findViewById<TextView>(R.id.homePromptBody).setText(R.string.paused_body)
            findViewById<Button>(R.id.homePromptButton).apply {
                setText(R.string.paused_button)
                setOnClickListener { startWithPermission() }
            }
        }
        dashboard.visibility = View.VISIBLE

        // 1. Electricity, live: number, unit, gauge, cost per hour
        val watts = r?.demandWatts
        val (num, unit) = when {
            watts == null -> "—" to ""
            watts < 1000 -> "$watts" to "W"
            else -> String.format(Locale.UK, "%.2f", watts / 1000.0) to "kW"
        }
        setIfChanged(R.id.homeNow, num)
        setIfChanged(R.id.homeNowUnit, unit)
        findViewById<ArcGaugeView>(R.id.homeArc).setWatts(watts)
        setIfChanged(R.id.homeNowSub, when {
            watts != null && ins?.priceNow != null -> "About ${money(watts / 1000.0 * ins.priceNow)} an hour"
            r != null -> "Meter reading at ${r.readAt}"
            else -> ""
        })

        // 2. Gas: average over the latest half-hour (arrives about an hour late)
        val hasGas = r?.gasTodayKwh != null
        findViewById<View>(R.id.homeGasCard).visibility = if (hasGas) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeGasTodayRow).visibility = if (hasGas) View.VISIBLE else View.GONE
        if (hasGas && r != null) {
            setIfChanged(R.id.homeGasAvgLabel, r.gasReadAt?.let { getString(R.string.home_gas_avg, it) } ?: getString(R.string.home_gas_avg_none))
            setIfChanged(R.id.homeGasKw, r.gasKw?.let { String.format(Locale.UK, "%.1f", it) } ?: "0.0")
            setGasFlame(r.gasRunMinutes > 0)
        } else setGasFlame(false)

        // Today so far (shown on Usage → Day)
        val total = listOfNotNull(ins?.elecCostToday, if (hasGas) ins?.gasCostToday else null).sum()
        setIfChanged(R.id.homeTodayTotal, if (ins?.elecCostToday != null) "${money(total * 100)} so far" else "")
        ledger(R.id.homeElecToday, r?.let { String.format(Locale.UK, "%.2f kWh", it.todayKwh) } ?: "—",
            ins?.elecCostToday?.let { money(it * 100) })
        if (hasGas && r != null) {
            ledger(R.id.homeGasToday, String.format(Locale.UK, "%.1f kWh", r.gasTodayKwh),
                ins?.gasCostToday?.let { money(it * 100) })
        }

        // 4. Price now (next price green with an arrow when it's lower) and always-on
        setIfChanged(R.id.homePrice, ins?.priceNow?.let { String.format(Locale.UK, "%.1fp", it) } ?: "—")
        val next = if (ins?.priceNext != null && ins.priceNextAt != null)
            getString(R.string.home_next_price, String.format(Locale.UK, "%.1fp", ins.priceNext), Insights.formatTime(ins.priceNextAt)) else null
        val cheaper = ins?.priceNow != null && ins.priceNext != null && ins.priceNext < ins.priceNow
        findViewById<View>(R.id.homePriceNextRow).visibility = if (next != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homePriceArrow).visibility = if (cheaper) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.homePriceSub).apply {
            setTextColor(getColor(if (cheaper) R.color.elec_low else R.color.text_muted))
            if (next != null && text.toString() != next) text = next
        }
        setIfChanged(R.id.homeBase, ins?.baseW?.let { "$it W" } ?: "—")

        findViewById<TextView>(R.id.homeError).apply {
            visibility = if (err != null && prefs.running) View.VISIBLE else View.GONE
            text = "Couldn't update: $err"
        }
    }

    // ---- Gas flame (design by Gemini): a gently moving flame while the gas is on, an outline when it's off

    private var gasOn = false

    private fun setGasFlame(on: Boolean) {
        val flame = findViewById<ImageView>(R.id.homeGasFlame)
        if (on != gasOn || flame.tag == null) {
            gasOn = on
            flame.tag = on
            flame.setImageResource(if (on) R.drawable.avd_gas_flame_live else R.drawable.ic_gas_flame_off)
            flame.imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.gas_accent else R.color.text_muted))
        }
        updateFlameAnimation()
    }

    /** Animates only while the flame is on screen (Home tab, app open) and the gas is on. */
    private fun updateFlameAnimation() {
        val avd = findViewById<ImageView>(R.id.homeGasFlame).drawable as? android.graphics.drawable.Animatable ?: return
        val run = gasOn && resumed && tab == TAB_HOME
        if (run && !avd.isRunning) avd.start()
        else if (!run && avd.isRunning) avd.stop()
    }

    private fun costHint(): String = if (prefs.apiKey.isNullOrBlank()) "Add API key for costs" else ""

    /** "8.42 kWh · £2.10": the amount bold, the cost in the secondary colour. */
    private fun ledger(id: Int, amount: String, cost: String?) {
        val v = findViewById<TextView>(id)
        val plain = amount + (cost?.let { " · $it" } ?: "")
        if (v.text.toString() == plain) return
        val sb = android.text.SpannableStringBuilder(amount)
        sb.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, amount.length, 0)
        if (cost != null) {
            val start = sb.length
            sb.append(" · ").append(cost)
            sb.setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_secondary)), start, sb.length, 0)
        }
        v.text = sb
    }

    /** Only touches a TextView when its text really changes, so nothing flickers or re-lays out. */
    private fun setIfChanged(id: Int, value: String) {
        val v = findViewById<TextView>(id)
        if (v.text.toString() != value) v.text = value
    }

    // ================================================================= usage

    private fun setUpUsage() {
        findViewById<SlotBarsView>(R.id.usageSlots).chartDp = 96f
        findViewById<View>(R.id.usageElec).setOnClickListener { prefs.setStr(K_USAGE_GAS, "0"); renderUsage() }
        findViewById<View>(R.id.usageGas).setOnClickListener { prefs.setStr(K_USAGE_GAS, "1"); renderUsage() }
        listOf(R.id.usagePeriodDay, R.id.usagePeriodWeek, R.id.usagePeriodMonth).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { prefs.setLong(K_USAGE_PERIOD, i.toLong()); renderUsage() }
        }
    }

    private fun renderUsage() {
        val message = findViewById<TextView>(R.id.usageMessage)
        val content = findViewById<View>(R.id.usageContent)
        val d = Insights(this).load()
        if (prefs.apiKey.isNullOrBlank() || d == null) {
            message.visibility = View.VISIBLE
            message.setText(if (prefs.apiKey.isNullOrBlank()) R.string.insights_need_key else R.string.insights_waiting)
            content.visibility = View.GONE
            return
        }
        message.visibility = View.GONE
        content.visibility = View.VISIBLE
        val r = prefs.loadReading()

        // Day | Week | Month
        val period = prefs.long(K_USAGE_PERIOD).toInt().coerceIn(0, 2)
        listOf(R.id.usagePeriodDay, R.id.usagePeriodWeek, R.id.usagePeriodMonth).forEachIndexed { i, id ->
            findViewById<TextView>(id).apply {
                setBackgroundResource(if (i == period) R.drawable.bg_segment_on_lg else 0)
                setTextColor(getColor(if (i == period) R.color.brand_pink else R.color.text_muted))
                typeface = Typeface.create("sans-serif-medium", if (i == period) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        findViewById<View>(R.id.usageDayBox).visibility = if (period == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.usageRangeBox).visibility = if (period == 0) View.GONE else View.VISIBLE

        // ---- 1. Today's half-hours (Electricity | Gas)
        val gasAvailable = r?.gasTodayKwh != null || d.todayGasSlots.isNotEmpty()
        val showGas = gasAvailable && prefs.str(K_USAGE_GAS) == "1"
        findViewById<View>(R.id.usageSwitch).visibility = if (gasAvailable) View.VISIBLE else View.GONE
        findViewById<View>(R.id.usageChartTitle).visibility = if (gasAvailable) View.GONE else View.VISIBLE
        styleSegment(R.id.usageElec, !showGas)
        styleSegment(R.id.usageGas, showGas)
        setIfChanged(R.id.usageDate, "Today · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)))
        val slots = if (showGas) d.todayGasSlots else d.todaySlots
        findViewById<SlotBarsView>(R.id.usageSlots).setSlots(slots, gas = showGas)

        // Peak half-hour
        val peak = slots.indices.maxByOrNull { slots[it] }?.takeIf { slots[it] > 0 }
        findViewById<View>(R.id.usagePeakRow).visibility = if (peak != null || showGas) View.VISIBLE else View.GONE
        if (peak != null) {
            setIfChanged(R.id.usagePeakTime, getString(R.string.usage_peak, slotTime(peak), slotTime(peak + 1)))
            val kwh = slots[peak]
            val pence = if (showGas) null else d.todaySlotPence.getOrNull(peak)
            setIfChanged(R.id.usagePeakDetail, listOfNotNull(
                String.format(Locale.UK, "%.2f kW avg", kwh * 2),
                String.format(Locale.UK, "%.2f kWh", kwh),
                pence?.let { money(it) },
            ).joinToString(" · "))
        } else if (showGas) {
            setIfChanged(R.id.usagePeakTime, "")
            setIfChanged(R.id.usagePeakDetail, getString(R.string.usage_no_gas_yet))
        }

        // Total, cost (incl. standing charge) and average unit rate
        val total = if (showGas) r?.gasTodayKwh ?: slots.sum() else r?.todayKwh ?: slots.sum()
        val cost = if (showGas) d.gasCostToday else d.elecCostToday
        val standing = if (showGas) d.gasStanding else d.elecStanding
        setIfChanged(R.id.usageTotal, String.format(Locale.UK, if (showGas) "%.1f kWh" else "%.2f kWh", total))
        setIfChanged(R.id.usageCost, cost?.let { money(it * 100) } ?: "—")
        setIfChanged(R.id.usageAvg, if (cost != null && total > 0.05)
            String.format(Locale.UK, "%.1fp/kWh", (cost * 100 - (standing ?: 0.0)).coerceAtLeast(0.0) / total) else "—")

        // ---- Week / Month: daily totals (only days with readings, not before the Home Mini was set up)
        val span = if (period == 2) Insights.HISTORY_DAYS else 7
        setIfChanged(R.id.usageRangeTitle, getString(if (period == 2) R.string.usage_last_month else R.string.usage_last_week))
        val firstDay = LocalDate.now().minusDays(span.toLong()).toString()
        val inRange = d.history.filter { it.date >= firstDay }
        val known = inRange.filter { it.elecKwh > 0 }
        val hasGas = inRange.any { it.gasKwh != null }
        val weekCost = known.sumOf { (it.elecCost ?: 0.0) + (it.gasCost ?: 0.0) }
        setIfChanged(R.id.usageWeekCost, if (known.isEmpty()) "—" else money(weekCost * 100))
        setIfChanged(R.id.usageWeekSub, if (known.isEmpty()) "Collecting your first full day" else buildString {
            append(String.format(Locale.UK, "Electricity %.1f kWh", known.sumOf { it.elecKwh }))
            if (hasGas) append(String.format(Locale.UK, " · Gas %.1f kWh", known.sumOf { it.gasKwh ?: 0.0 }))
            if (known.size < span) append(" · ${known.size} ${if (known.size == 1) "day" else "days"}")
        })
        val days = (span downTo 1).map { LocalDate.now().minusDays(it.toLong()) }
        val byDate = inRange.associateBy { it.date }
        val elec = days.map { byDate[it.toString()]?.elecKwh ?: 0.0 }
        val gas = days.map { byDate[it.toString()]?.gasKwh ?: 0.0 }
        val dayLabels = if (span == 7) days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK) }
            else days.mapIndexed { i, day -> if ((span - 1 - i) % 5 == 0) day.dayOfMonth.toString() else "" }
        val elecColour = getColor(R.color.elec_low)
        val gasColour = getColor(R.color.gas_accent)
        findViewById<BarChartView>(R.id.chartWeek).setData(
            if (hasGas) listOf(elec, gas) else listOf(elec),
            if (hasGas) listOf(elecColour, gasColour) else listOf(elecColour),
            dayLabels, highlightIndex = span - 1,
            emptyText = if (span == 7) "Collecting the last 7 days…" else "Collecting the last 30 days… (fills in over a few hours)",
        )
        findViewById<View>(R.id.legendGas).visibility = if (hasGas) View.VISIBLE else View.GONE
        findViewById<View>(R.id.legendGasDot).visibility = if (hasGas) View.VISIBLE else View.GONE

        val list = findViewById<LinearLayout>(R.id.usageDays)
        list.removeAllViews()
        val rows = known.sortedByDescending { it.date }
        if (rows.isEmpty()) {
            list.addView(listRow("No full days yet", "Check back tomorrow", ""))
        } else {
            rows.forEachIndexed { i, day ->
                if (i > 0) list.addView(rowDivider())
                val date = LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
                val sub = buildString {
                    append(String.format(Locale.UK, "%.1f kWh electricity", day.elecKwh))
                    day.gasKwh?.let { append(String.format(Locale.UK, " · %.1f kWh gas", it)) }
                }
                val dayCost = (day.elecCost ?: 0.0) + (day.gasCost ?: 0.0)
                list.addView(listRow(date, sub, if (dayCost > 0) money(dayCost * 100) else ""))
            }
        }
        findViewById<View>(R.id.usageNote).visibility = if (known.size < span) View.VISIBLE else View.GONE
    }

    /** "21:30" for half-hour number [i] (48 = "24:00"). */
    private fun slotTime(i: Int) = String.format(Locale.UK, "%02d:%02d", i / 2, if (i % 2 == 0) 0 else 30)

    /** A row inside a card: optional colour square, title (and subtitle), value on the right. */
    private fun listRow(title: String, subtitle: String?, value: String, swatch: Int? = null, valueColour: Int = R.color.text_primary): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (50 * dp).toInt()
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        if (swatch != null) {
            row.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_swatch)
                backgroundTintList = ColorStateList.valueOf(getColor(swatch))
            }, LinearLayout.LayoutParams((9 * dp).toInt(), (9 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(if (subtitle == null) R.color.text_secondary else R.color.text_primary))
        })
        if (subtitle != null) texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(getColor(R.color.text_muted))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (12 * dp).toInt() })
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(getColor(valueColour))
        })
        return row
    }

    private fun rowDivider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.divider_subtle))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    // ================================================================= alerts

    private val swGas by lazy { findViewById<Switch>(R.id.swGas) }
    private val swHigh by lazy { findViewById<Switch>(R.id.swHigh) }
    private val swOffline by lazy { findViewById<Switch>(R.id.swOffline) }
    private val swSticky by lazy { findViewById<Switch>(R.id.swSticky) }
    private val swCheap by lazy { findViewById<Switch>(R.id.swCheap) }
    private val swSummary by lazy { findViewById<Switch>(R.id.swSummary) }

    private fun setUpAlerts() {
        swGas.setOnCheckedChangeListener { _, on -> if (!loading) prefs.gasAlert = on; updateOptionRows() }
        swHigh.setOnCheckedChangeListener { _, on -> if (!loading) prefs.highAlert = on; updateOptionRows() }
        swOffline.setOnCheckedChangeListener { _, on -> if (!loading) prefs.offlineAlert = on }
        swSticky.setOnCheckedChangeListener { _, on -> if (!loading) prefs.stickyWarnings = on }
        swCheap.setOnCheckedChangeListener { _, on -> if (!loading) prefs.cheapAlert = on; updateOptionRows() }
        swSummary.setOnCheckedChangeListener { _, on -> if (!loading) prefs.summaryAlert = on; updateOptionRows() }
        // − / + steppers save straight away
        stepper(R.id.highKwMinus, R.id.highKwPlus) { d -> prefs.highKw = (prefs.highKw + d * 0.5f).coerceIn(0.5f, 20f) }
        stepper(R.id.highMinMinus, R.id.highMinPlus) { d -> prefs.highMinutes = (prefs.highMinutes + d * 5).coerceIn(5, 600) }
        stepper(R.id.gasRunMinus, R.id.gasRunPlus) { d -> prefs.gasAlertMinutes = (prefs.gasAlertMinutes + d * 30).coerceIn(30, 360) }
        stepper(R.id.cheapMinus, R.id.cheapPlus) { d -> prefs.cheapPence = (Math.round(prefs.cheapPence) + d).toFloat().coerceIn(-20f, 60f) }
        stepper(R.id.summaryMinus, R.id.summaryPlus) { d -> prefs.summaryHour = (prefs.summaryHour + d + 24) % 24 }
    }

    private fun stepper(minus: Int, plus: Int, change: (Int) -> Unit) {
        findViewById<View>(minus).setOnClickListener { change(-1); renderAlertValues() }
        findViewById<View>(plus).setOnClickListener { change(1); renderAlertValues() }
    }

    private fun renderAlertValues() {
        setIfChanged(R.id.highKwValue, String.format(Locale.UK, "%.1f kW", prefs.highKw))
        setIfChanged(R.id.highMinValue, duration(prefs.highMinutes))
        setIfChanged(R.id.gasRunValue, String.format(Locale.UK, "%s h", trim(prefs.gasAlertMinutes / 60.0)))
        setIfChanged(R.id.cheapValue, "${Math.round(prefs.cheapPence)}p")
        setIfChanged(R.id.summaryValue, String.format(Locale.UK, "%02d:00", prefs.summaryHour))
    }

    private fun updateOptionRows() {
        findViewById<View>(R.id.highOptions).visibility = if (swHigh.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gasOptions).visibility = if (swGas.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cheapOptions).visibility = if (swCheap.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.summaryOptions).visibility = if (swSummary.isChecked) View.VISIBLE else View.GONE
    }

    /** "Recent alerts": what the app actually sent, newest first. */
    private fun renderRecentAlerts() {
        val box = findViewById<LinearLayout>(R.id.alertsRecent)
        box.removeAllViews()
        val log = AlertLog.load(this)
        if (log.isEmpty()) {
            box.addView(listRow(getString(R.string.alerts_none), null, ""))
            return
        }
        val today = LocalDate.now()
        log.forEachIndexed { i, e ->
            if (i > 0) box.addView(rowDivider())
            val at = java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault())
            val time = at.format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
            val day = when (at.toLocalDate()) {
                today -> "Today $time"
                today.minusDays(1) -> "Yesterday $time"
                else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
            }
            val colour = when (e.id) {
                LiveService.GAS_NOTIF_ID -> R.color.gas_accent
                LiveService.HIGH_NOTIF_ID -> R.color.elec_high
                LiveService.OFFLINE_NOTIF_ID -> R.color.elec_mid
                LiveService.CHEAP_NOTIF_ID -> R.color.elec_low
                else -> R.color.brand_pink
            }
            box.addView(alertRow(e.title, e.text, day, colour))
        }
    }

    private fun alertRow(title: String, sub: String, time: String, colour: Int): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }
        row.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_dot)
            backgroundTintList = ColorStateList.valueOf(getColor(colour))
        }, LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply { topMargin = (7 * dp).toInt(); marginEnd = (12 * dp).toInt() })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })
        if (sub.isNotBlank()) texts.addView(TextView(this).apply {
            text = sub
            textSize = 13f
            maxLines = 2
            setTextColor(getColor(R.color.text_muted))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (12 * dp).toInt() })
        row.addView(TextView(this).apply {
            text = time
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
        })
        return row
    }

    // ================================================================= settings

    private val apiKey by lazy { findViewById<EditText>(R.id.apiKey) }
    private val relayUrl by lazy { findViewById<EditText>(R.id.relayUrl) }
    private val account by lazy { findViewById<EditText>(R.id.account) }
    private val interval by lazy { findViewById<EditText>(R.id.interval) }
    private val swLive by lazy { findViewById<Switch>(R.id.swLive) }
    private val swBoot by lazy { findViewById<Switch>(R.id.swBoot) }
    private val swWidgetApp by lazy { findViewById<Switch>(R.id.swWidgetApp) }

    private fun setUpSettings() {
        findViewById<Button>(R.id.save).setOnClickListener { saveConnection() }
        findViewById<TextView>(R.id.getKey).setOnClickListener {
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEY_URL)), null)
        }
        findViewById<TextView>(R.id.showKey).setOnClickListener { v ->
            // The key is hidden by default so it doesn't end up in screenshots
            // Compare the whole variation: VISIBLE_PASSWORD shares bits with PASSWORD
            val hidden = (apiKey.inputType and android.text.InputType.TYPE_MASK_VARIATION) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            apiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (hidden) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            apiKey.typeface = Typeface.MONOSPACE
            apiKey.setSelection(apiKey.text.length)
            (v as TextView).setText(if (hidden) R.string.hide else R.string.show)
        }
        swLive.setOnCheckedChangeListener { _, on ->
            if (loading) return@setOnCheckedChangeListener
            if (on) {
                if (!prefs.isConfigured) {
                    loading = true; swLive.isChecked = false; loading = false
                    apiKey.error = getString(R.string.key_required)
                    return@setOnCheckedChangeListener
                }
                startWithPermission()
            } else {
                LiveService.start(this, LiveService.ACTION_STOP)
                prefs.running = false
                renderAll()
            }
        }
        swBoot.setOnCheckedChangeListener { _, on -> if (!loading) prefs.startOnBoot = on }
        swWidgetApp.setOnCheckedChangeListener { _, on ->
            if (!loading) { prefs.widgetOpensApp = on; OctopusWidget.updateAll(this) }
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener { requestUnrestrictedBattery() }
        findViewById<View>(R.id.rowUnused).setOnClickListener { openUnusedAppSettings() }
        findViewById<View>(R.id.makeRelay).setOnClickListener { openSetup(SetupActivity.MAKE_RELAY) }
        // Menu: each section opens on its own; back returns to the menu
        settingsSections.forEachIndexed { i, (row, _, _) ->
            findViewById<View>(row).setOnClickListener { showSettingsSection(i) }
        }
        findViewById<View>(R.id.settingsBack).setOnClickListener { showSettingsSection(-1) }
        // Dev build only: the test-data panel
        if (BuildConfig.DEV_TOOLS) {
            findViewById<View>(R.id.setDevDivider).visibility = View.VISIBLE
            findViewById<View>(R.id.setOpenDev).apply {
                visibility = View.VISIBLE
                setOnClickListener { startActivity(Intent().setClassName(this@MainActivity, "uk.octopuslive.DevActivity")) }
            }
        }
        findViewById<View>(R.id.setMpanRow).setOnClickListener { showMeters = !showMeters; renderSettingsStatus() }
        findViewById<View>(R.id.setMprnRow).setOnClickListener { showMeters = !showMeters; renderSettingsStatus() }
        findViewById<View>(R.id.rowAbout).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.rowPrivacy).setOnClickListener { AboutActivity.start(this, AboutActivity.DOC_PRIVACY) }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        findViewById<TextView>(R.id.aboutVersion).text = version?.let { getString(R.string.about_version, it) } ?: ""
    }

    /** Puts the saved settings into the switches and fields (without triggering their listeners). */
    private fun loadControls() {
        loading = true
        swGas.isChecked = prefs.gasAlert
        swHigh.isChecked = prefs.highAlert
        swOffline.isChecked = prefs.offlineAlert
        swSticky.isChecked = prefs.stickyWarnings
        swCheap.isChecked = prefs.cheapAlert
        swSummary.isChecked = prefs.summaryAlert
        renderAlertValues()
        if (!apiKey.hasFocus()) apiKey.setText(prefs.apiKey.orEmpty())
        if (!relayUrl.hasFocus()) relayUrl.setText(prefs.relayUrl.orEmpty())
        if (!account.hasFocus()) account.setText(prefs.accountNumber.orEmpty())
        if (!interval.hasFocus()) interval.setText(prefs.intervalSeconds.toString())
        swLive.isChecked = prefs.running
        swBoot.isChecked = prefs.startOnBoot
        swWidgetApp.isChecked = prefs.widgetOpensApp
        updateOptionRows()
        loading = false
    }

    /** Menu row, its section card, the section title. */
    private val settingsSections = listOf(
        Triple(R.id.setOpenMini, R.id.setCardMini, R.string.set_home_mini),
        Triple(R.id.setOpenMeters, R.id.setCardMeters, R.string.set_meters),
        Triple(R.id.setOpenConnection, R.id.setCardConnection, R.string.section_connection),
        Triple(R.id.setOpenPrefs, R.id.setCardPrefs, R.string.set_preferences),
    )
    private var settingsSection = -1   // -1 = the menu

    private fun showSettingsSection(i: Int) {
        if (settingsSection != i) hideKeyboard()
        settingsSection = i
        val menu = i < 0
        findViewById<View>(R.id.setMenu).visibility = if (menu) View.VISIBLE else View.GONE
        findViewById<View>(R.id.setCardAbout).visibility = if (menu) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settingsBack).visibility = if (menu) View.GONE else View.VISIBLE
        settingsSections.forEachIndexed { k, (_, card, _) ->
            findViewById<View>(card).visibility = if (k == i) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.settingsTitle).setText(if (menu) R.string.tab_settings else settingsSections[i].third)
        findViewById<View>(R.id.pageSettings).scrollTo(0, 0)
    }

    private var showMeters = false   // meter numbers are masked until tapped, so they stay out of screenshots

    private fun renderSettingsStatus() {
        loading = true
        swLive.isChecked = prefs.running
        loading = false
        val r = prefs.loadReading()
        val err = prefs.lastError
        val now = System.currentTimeMillis()

        // Octopus Home Mini: status badge, update interval, connection, last reading
        val (badge, colour) = when {
            !prefs.isConfigured -> "Not set up" to R.color.text_muted
            !prefs.running -> "Paused" to R.color.text_muted
            err != null -> "Problem" to R.color.elec_high
            r?.readAtMs != null && now - r.readAtMs > LiveService.OFFLINE_MS -> "Delayed" to R.color.elec_mid
            r == null -> "Connecting" to R.color.text_muted
            else -> "Connected" to R.color.elec_low
        }
        val c = getColor(colour)
        findViewById<View>(R.id.setStatusBadge).background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 100 * resources.displayMetrics.density
            setColor((c and 0x00FFFFFF) or 0x1C000000)
        }
        findViewById<View>(R.id.setStatusDot).backgroundTintList = ColorStateList.valueOf(c)
        findViewById<TextView>(R.id.setStatusText).apply { text = badge; setTextColor(c) }
        setIfChanged(R.id.setOpenMiniSub, badge + if (prefs.relayUrl.isNullOrBlank()) " · direct" else " · relay")
        val relay = prefs.relayUrl?.takeIf { it.isNotBlank() }
        // Octopus allows ~100 requests an hour, so new readings come about once a minute at best
        setIfChanged(R.id.setCadence, if (relay != null) "About every minute" else "Every ${prefs.intervalSeconds}s")
        setIfChanged(R.id.setConnection, relay?.let { "Relay · " + (Uri.parse(it).host ?: "custom") } ?: "Direct to Octopus")
        setIfChanged(R.id.setLastReading, r?.readAt ?: "—")
        findViewById<TextView>(R.id.liveStatus).apply {
            visibility = if (err != null && prefs.running) View.VISIBLE else View.GONE
            text = "Couldn't update: $err"
        }

        // Meters & tariff (needs the API key)
        val ins = Insights(this).load()
        setIfChanged(R.id.setTariff, ins?.tariff?.let { tariffName(it) ?: it } ?: "—")
        findViewById<TextView>(R.id.setTariffCode).apply {
            val code = ins?.tariff
            visibility = if (code != null && tariffName(code) != null) View.VISIBLE else View.GONE
            if (code != null && text.toString() != code) text = code
        }
        setIfChanged(R.id.setOpenMetersSub, ins?.tariff?.let { tariffName(it) ?: it }
            ?: if (prefs.apiKey.isNullOrBlank()) getString(R.string.insights_need_key) else "—")
        setIfChanged(R.id.setOpenConnectionSub, getString(R.string.set_connection_sub))
        setIfChanged(R.id.setOpenPrefsSub, getString(R.string.set_prefs_sub))
        setIfChanged(R.id.setMpan, ins?.mpan?.let { meterNumber(it, intArrayOf(2, 4, 4, 3)) } ?: "—")
        val mprn = ins?.mprn
        findViewById<View>(R.id.setMprnRow).visibility = if (mprn != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.setMprnDivider).visibility = if (mprn != null) View.VISIBLE else View.GONE
        if (mprn != null) setIfChanged(R.id.setMprn, meterNumber(mprn, intArrayOf(2, 4, 4)))
        setIfChanged(R.id.setStanding, listOfNotNull(
            ins?.elecStanding?.let { String.format(Locale.UK, "%.1fp/day (E)", it) },
            ins?.gasStanding?.let { String.format(Locale.UK, "%.1fp (G)", it) },
        ).joinToString(" · ").ifEmpty { "—" })
        findViewById<TextView>(R.id.setMetersHelp).apply {
            when {
                prefs.apiKey.isNullOrBlank() -> { visibility = View.VISIBLE; setText(R.string.insights_need_key) }
                ins?.mpan != null -> { visibility = View.VISIBLE; setText(R.string.set_meters_help) }
                else -> visibility = View.GONE
            }
        }

        findViewById<TextView>(R.id.batteryState).setText(if (isBatteryUnrestricted()) R.string.bg_battery_ok else R.string.bg_battery_no)
        findViewById<TextView>(R.id.unusedState).setText(if (isExemptFromUnusedPause()) R.string.bg_unused_ok else R.string.bg_unused_no)
    }

    /** "20 0005 4921 883", or "20 •••• •••• 883" until tapped. */
    private fun meterNumber(n: String, groups: IntArray): String {
        val digits = n.filter { it.isLetterOrDigit() }
        val parts = mutableListOf<String>()
        var i = 0
        for (g in groups) {
            if (i >= digits.length) break
            parts += digits.substring(i, minOf(digits.length, i + g)); i += g
        }
        if (i < digits.length) parts += digits.substring(i)
        if (showMeters || parts.size < 3) return parts.joinToString(" ")
        return parts.mapIndexed { k, p -> if (k == 0 || k == parts.size - 1) p else "•".repeat(p.length) }.joinToString(" ")
    }

    private fun saveConnection() {
        val key = apiKey.text.toString().trim()
        val relay = relayUrl.text.toString().trim()
        if (key.isEmpty() && relay.isEmpty()) {
            apiKey.error = getString(R.string.key_required)
            return
        }
        val acct = account.text.toString().trim().uppercase(Locale.UK)
        val minSecs = if (relay.isEmpty()) 30 else 10
        val secs = interval.text.toString().toIntOrNull()?.coerceIn(minSecs, 3600) ?: 60
        if (key != prefs.apiKey.orEmpty() || acct != prefs.accountNumber.orEmpty()) prefs.clearCachedApiState()
        prefs.relayUrl = relay
        prefs.apiKey = key
        prefs.accountNumber = acct
        prefs.intervalSeconds = secs
        interval.setText(secs.toString())
        currentFocus?.clearFocus()
        hideKeyboard()
        startWithPermission()
    }

    /** Asks for notification permission if needed (Android 13+), then starts live updates. */
    private fun startWithPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return   // start once the user answers
        }
        startLive()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.notify_denied, Toast.LENGTH_LONG).show()
            }
            startLive()
        }
    }

    private fun startLive() {
        val wasRunning = prefs.running
        prefs.running = true
        LiveService.start(this, LiveService.ACTION_REFRESH)
        Toast.makeText(this, if (wasRunning) R.string.saved_restarted else R.string.saved_started, Toast.LENGTH_SHORT).show()
        renderAll()
    }

    private fun openSetup(step: Int) {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_STEP, step))
    }

    // ================================================================= background running

    private fun isBatteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** True when Android won't pause the app or remove its permissions for not being opened. */
    private fun isExemptFromUnusedPause(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) packageManager.isAutoRevokeWhitelisted else true

    private fun requestUnrestrictedBattery() {
        val intent = if (!isBatteryUnrestricted()) {
            // Shows the system "Let app always run in background?" prompt
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        tryStart(intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openUnusedAppSettings() {
        Toast.makeText(this, R.string.bg_unused_tip, Toast.LENGTH_LONG).show()
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        tryStart(intent, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun tryStart(intent: Intent, fallback: Intent?) {
        try { startActivity(intent) } catch (e: Exception) {
            try { fallback?.let { startActivity(it) } } catch (e2: Exception) {}
        }
    }

    // ================================================================= helpers

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** Pence to a short money string: "5p", "£1.12". */
    private fun money(pence: Double): String =
        if (pence < 100) String.format(Locale.UK, "%.0fp", pence) else String.format(Locale.UK, "£%.2f", pence / 100)

    private fun duration(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    /** A friendly name for common Octopus tariff codes, e.g. E-1R-VAR-22-11-01-M -> Flexible Octopus. */
    private fun tariffName(code: String): String? {
        val c = code.uppercase(Locale.UK)
        return when {
            "INTELLI" in c -> "Intelligent Octopus Go"
            "AGILE" in c -> "Agile Octopus"
            "-GO-" in c -> "Octopus Go"
            "SILVER" in c || "TRACKER" in c -> "Octopus Tracker"
            "COSY" in c -> "Cosy Octopus"
            "FLUX" in c -> "Octopus Flux"
            "FIX" in c -> "Octopus Fixed"
            "VAR" in c -> "Flexible Octopus"
            else -> null
        }
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.UK, "%.1f", v)

    companion object {
        private const val REQ_NOTIFY = 1
        private const val K_TAB = "tab"
        private const val K_USAGE_GAS = "usageChartGas"
        private const val K_USAGE_PERIOD = "usagePeriod"
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_USAGE = 1
        const val TAB_ALERTS = 2
        const val TAB_SETTINGS = 3
        const val API_KEY_URL = "https://octopus.energy/dashboard/new/accounts/personal-details/api-access"
        const val SOURCE_URL = "https://github.com/ruwaid-7/octopus-live"
    }
}
