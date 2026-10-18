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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        LiveService.listener = { _ -> runOnUiThread { renderAll() } }
        loadControls()
        renderAll()
    }

    override fun onPause() {
        LiveService.listener = null
        saveAlertNumbers()
        super.onPause()
    }

    @Deprecated("Back goes to Home first")
    override fun onBackPressed() {
        if (tab != TAB_HOME) showTab(TAB_HOME) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun renderAll() {
        renderHome()
        renderUsage()
        renderSettingsStatus()
    }

    // ================================================================= tabs

    private val pages by lazy {
        listOf(R.id.pageHome, R.id.pageUsage, R.id.pageAlerts, R.id.pageSettings).map { findViewById<View>(it) }
    }
    private val navIcons by lazy {
        listOf(R.id.navHomeIcon, R.id.navUsageIcon, R.id.navAlertsIcon, R.id.navSettingsIcon).map { findViewById<ImageView>(it) }
    }
    private val navLabels by lazy {
        listOf(R.id.navHomeLabel, R.id.navUsageLabel, R.id.navAlertsLabel, R.id.navSettingsLabel).map { findViewById<TextView>(it) }
    }

    private fun setUpNav() {
        listOf(R.id.navHome, R.id.navUsage, R.id.navAlerts, R.id.navSettings).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { showTab(i) }
        }
    }

    private fun showTab(index: Int) {
        if (tab != index) {
            saveAlertNumbers()
            hideKeyboard()
        }
        tab = index
        val active = ColorStateList.valueOf(getColor(R.color.text_primary))
        val inactive = ColorStateList.valueOf(getColor(R.color.nav_inactive))
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        navIcons.forEachIndexed { i, icon -> icon.imageTintList = if (i == index) active else inactive }
        navLabels.forEachIndexed { i, label ->
            label.setTextColor(if (i == index) active else inactive)
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

        // Hero: live watts
        val watts = r?.demandWatts
        findViewById<TextView>(R.id.homeNow).text = watts?.let { LiveService.formatWatts(it) } ?: "—"
        findViewById<TextView>(R.id.homeNowSub).text = when {
            watts != null && ins?.priceNow != null -> "About ${money(watts / 1000.0 * ins.priceNow)} an hour"
            r != null -> "Meter reading at ${r.readAt}"
            else -> ""
        }
        findViewById<ProgressBar>(R.id.homeLevel).apply {
            progress = ((watts ?: 0) / LEVEL_FULL_W * max).toInt().coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(getColor(levelColour(watts)))
        }

        // Banners
        val gasOn = r?.gasTodayKwh != null && (r.gasRunMinutes) > 0
        findViewById<View>(R.id.homeGasBanner).visibility = if (gasOn) View.VISIBLE else View.GONE
        if (gasOn && r != null) {
            findViewById<TextView>(R.id.homeGasBannerText).text =
                "Gas on for ${duration(r.gasRunMinutes)}" + (r.gasRunSince?.let { " · since $it" } ?: "")
        }
        val highSince = prefs.long(LiveService.K_HIGH_SINCE)
        val highMins = if (highSince > 0) (now - highSince) / 60_000 else 0
        val highOn = prefs.highAlert && watts != null && watts >= prefs.highKw * 1000 && highSince > 0 && highMins >= 1
        findViewById<View>(R.id.homeHighBanner).visibility = if (highOn) View.VISIBLE else View.GONE
        if (highOn) {
            findViewById<TextView>(R.id.homeHighBannerText).text =
                "Above ${trim(prefs.highKw.toDouble())} kW for ${duration(highMins.toInt())} · since ${Insights.formatTime(highSince)}"
        }

        // Today tiles
        findViewById<TextView>(R.id.homeElecKwh).text = r?.let { String.format(Locale.UK, "%.2f kWh", it.todayKwh) } ?: "—"
        findViewById<TextView>(R.id.homeElecSub).text = ins?.elecCostToday?.let { money(it * 100) } ?: costHint()
        val gasTile = findViewById<View>(R.id.homeGasTile)
        if (r?.gasTodayKwh != null) {
            gasTile.visibility = View.VISIBLE
            findViewById<TextView>(R.id.homeGasKwh).text = String.format(Locale.UK, "%.2f kWh", r.gasTodayKwh)
            findViewById<TextView>(R.id.homeGasSub).text = listOfNotNull(
                ins?.gasCostToday?.let { money(it * 100) },
                r.gasReadAt?.let { "to $it" },
            ).joinToString(" · ").ifEmpty { costHint() }
        } else {
            gasTile.visibility = View.GONE
        }

        // Today chart
        val total = listOfNotNull(ins?.elecCostToday, ins?.gasCostToday).sum()
        findViewById<TextView>(R.id.homeTodayTotal).text = if (ins?.elecCostToday != null) "${money(total * 100)} so far" else ""
        val labels = (0 until 48).map { if (it % 12 == 0) String.format(Locale.UK, "%02d:00", it / 2) else "" }
        findViewById<BarChartView>(R.id.chartToday).setData(
            listOf(ins?.todaySlots ?: emptyList()), listOf(getColor(R.color.brand_pink)), labels,
            emptyText = if (prefs.apiKey.isNullOrBlank()) "Add your API key in Settings" else "No readings yet today",
        )

        // Price and always-on
        findViewById<TextView>(R.id.homePrice).text = ins?.priceNow?.let { String.format(Locale.UK, "%.1fp", it) } ?: "—"
        findViewById<TextView>(R.id.homePriceSub).text = if (ins?.priceNext != null && ins.priceNextAt != null) {
            String.format(Locale.UK, "%.1fp from %s", ins.priceNext, Insights.formatTime(ins.priceNextAt))
        } else "per kWh"
        findViewById<TextView>(R.id.homeBase).text = ins?.baseW?.let { "$it W" } ?: "—"

        findViewById<TextView>(R.id.homeError).apply {
            visibility = if (err != null && prefs.running) View.VISIBLE else View.GONE
            text = "Couldn't update: $err"
        }
    }

    private fun costHint(): String = if (prefs.apiKey.isNullOrBlank()) "Add API key for costs" else ""

    // ================================================================= usage

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

        // Only days with readings (not days before the Home Mini was set up)
        val known = d.history.filter { it.elecKwh > 0 }
        val hasGas = d.history.any { it.gasKwh != null }
        val cost = known.sumOf { (it.elecCost ?: 0.0) + (it.gasCost ?: 0.0) }
        findViewById<TextView>(R.id.usageWeekCost).text = if (known.isEmpty()) "—" else money(cost * 100)
        findViewById<TextView>(R.id.usageWeekSub).text = if (known.isEmpty()) "Collecting your first full day" else buildString {
            append(String.format(Locale.UK, "Electricity %.1f kWh", known.sumOf { it.elecKwh }))
            if (hasGas) append(String.format(Locale.UK, " · Gas %.1f kWh", known.sumOf { it.gasKwh ?: 0.0 }))
            if (known.size < 7) append(" · ${known.size} ${if (known.size == 1) "day" else "days"}")
        }

        val days = (7 downTo 1).map { LocalDate.now().minusDays(it.toLong()) }
        val byDate = d.history.associateBy { it.date }
        val elec = days.map { byDate[it.toString()]?.elecKwh ?: 0.0 }
        val gas = days.map { byDate[it.toString()]?.gasKwh ?: 0.0 }
        val dayLabels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK) }
        val pink = getColor(R.color.brand_pink)
        val gasColour = getColor(R.color.gas_accent)
        findViewById<BarChartView>(R.id.chartWeek).setData(
            if (hasGas) listOf(elec, gas) else listOf(elec),
            if (hasGas) listOf(pink, gasColour) else listOf(pink),
            dayLabels, highlightIndex = 6, emptyText = "Collecting the last 7 days…",
        )
        findViewById<View>(R.id.legendGas).visibility = if (hasGas) View.VISIBLE else View.GONE
        findViewById<View>(R.id.legendGasDot).visibility = if (hasGas) View.VISIBLE else View.GONE

        // Day-by-day list, newest first
        val list = findViewById<LinearLayout>(R.id.usageDays)
        list.removeAllViews()
        val rows = known.sortedByDescending { it.date }
        if (rows.isEmpty()) {
            list.addView(dayRow("No full days yet", "Check back tomorrow", ""))
        } else {
            rows.forEachIndexed { i, day ->
                if (i > 0) list.addView(divider())
                val date = LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
                val sub = buildString {
                    append(String.format(Locale.UK, "%.1f kWh electricity", day.elecKwh))
                    day.gasKwh?.let { append(String.format(Locale.UK, " · %.1f kWh gas", it)) }
                }
                val dayCost = (day.elecCost ?: 0.0) + (day.gasCost ?: 0.0)
                list.addView(dayRow(date, sub, if (dayCost > 0) money(dayCost * 100) else ""))
            }
        }
        findViewById<View>(R.id.usageNote).visibility = if (known.size < 7) View.VISIBLE else View.GONE

        // Tariff
        val code = d.tariff
        findViewById<TextView>(R.id.usageTariff).text = code?.let { tariffName(it) ?: "Your tariff" } ?: "Looking up your tariff…"
        findViewById<TextView>(R.id.usageTariffCode).apply {
            text = code.orEmpty()
            visibility = if (code == null) View.GONE else View.VISIBLE
        }
        val details = mutableListOf<String>()
        d.priceNow?.let { p ->
            var line = String.format(Locale.UK, "%.1fp/kWh now", p)
            if (d.priceNext != null && d.priceNextAt != null) {
                line += String.format(Locale.UK, ", %.1fp from %s", d.priceNext, Insights.formatTime(d.priceNextAt))
            }
            details += line
        }
        if (d.elecCostToday == null && code != null) details += "Costs aren't available for this tariff yet (e.g. Economy 7)."
        known.mapNotNull { it.baseW }.minOrNull()?.let {
            details += String.format(Locale.UK, "Always on: %d W, about %.0f kWh a year", it, it * 24 * 365 / 1000.0)
        }
        d.error?.let { details += "Last update problem: $it" }
        findViewById<TextView>(R.id.usageTariffDetail).apply {
            text = details.joinToString("\n")
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun dayRow(title: String, subtitle: String, value: String): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (60 * dp).toInt()
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.text_primary))
        })
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.divider_subtle))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = (16 * resources.displayMetrics.density).toInt()
        }
    }

    // ================================================================= alerts

    private val swGas by lazy { findViewById<Switch>(R.id.swGas) }
    private val swHigh by lazy { findViewById<Switch>(R.id.swHigh) }
    private val swOffline by lazy { findViewById<Switch>(R.id.swOffline) }
    private val swSticky by lazy { findViewById<Switch>(R.id.swSticky) }
    private val swCheap by lazy { findViewById<Switch>(R.id.swCheap) }
    private val swSummary by lazy { findViewById<Switch>(R.id.swSummary) }
    private val highKw by lazy { findViewById<EditText>(R.id.highKw) }
    private val highMinutes by lazy { findViewById<EditText>(R.id.highMinutes) }
    private val cheapPence by lazy { findViewById<EditText>(R.id.cheapPence) }
    private val summaryHour by lazy { findViewById<EditText>(R.id.summaryHour) }

    private fun setUpAlerts() {
        swGas.setOnCheckedChangeListener { _, on -> if (!loading) prefs.gasAlert = on }
        swHigh.setOnCheckedChangeListener { _, on -> if (!loading) prefs.highAlert = on; updateOptionRows() }
        swOffline.setOnCheckedChangeListener { _, on -> if (!loading) prefs.offlineAlert = on }
        swSticky.setOnCheckedChangeListener { _, on -> if (!loading) prefs.stickyWarnings = on }
        swCheap.setOnCheckedChangeListener { _, on -> if (!loading) prefs.cheapAlert = on; updateOptionRows() }
        swSummary.setOnCheckedChangeListener { _, on -> if (!loading) prefs.summaryAlert = on; updateOptionRows() }
        for (field in listOf(highKw, highMinutes, cheapPence, summaryHour)) {
            field.imeOptions = EditorInfo.IME_ACTION_DONE
            field.setOnEditorActionListener { v, _, _ -> saveAlertNumbers(); hideKeyboard(); v.clearFocus(); true }
            field.setOnFocusChangeListener { _, focused -> if (!focused) saveAlertNumbers() }
        }
    }

    private fun updateOptionRows() {
        findViewById<View>(R.id.highOptions).visibility = if (swHigh.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cheapOptions).visibility = if (swCheap.isChecked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.summaryOptions).visibility = if (swSummary.isChecked) View.VISIBLE else View.GONE
    }

    private fun saveAlertNumbers() {
        if (loading) return
        prefs.highKw = highKw.text.toString().toFloatOrNull()?.coerceIn(0.5f, 20f) ?: prefs.highKw
        prefs.highMinutes = highMinutes.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: prefs.highMinutes
        prefs.cheapPence = cheapPence.text.toString().toFloatOrNull()?.coerceIn(-50f, 100f) ?: prefs.cheapPence
        prefs.summaryHour = summaryHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: prefs.summaryHour
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
        if (!highKw.hasFocus()) highKw.setText(trim(prefs.highKw.toDouble()))
        if (!highMinutes.hasFocus()) highMinutes.setText(prefs.highMinutes.toString())
        if (!cheapPence.hasFocus()) cheapPence.setText(trim(prefs.cheapPence.toDouble()))
        if (!summaryHour.hasFocus()) summaryHour.setText(prefs.summaryHour.toString())
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

    private fun renderSettingsStatus() {
        loading = true
        swLive.isChecked = prefs.running
        loading = false
        val r = prefs.loadReading()
        findViewById<TextView>(R.id.liveStatus).text = when {
            !prefs.isConfigured -> "Add an API key or relay below"
            !prefs.running -> "Off"
            prefs.lastError != null -> "Running · problem: ${prefs.lastError}"
            else -> "Running · " + (if (r?.viaServer == true) "via relay" else "direct from Octopus") +
                " · every ${prefs.intervalSeconds} s"
        }
        findViewById<TextView>(R.id.batteryState).setText(if (isBatteryUnrestricted()) R.string.bg_battery_ok else R.string.bg_battery_no)
        findViewById<TextView>(R.id.unusedState).setText(if (isExemptFromUnusedPause()) R.string.bg_unused_ok else R.string.bg_unused_no)
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
        saveAlertNumbers()
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

    private fun levelColour(w: Int?): Int = when {
        w == null || w < 500 -> R.color.elec_low
        w < 2000 -> R.color.elec_mid
        else -> R.color.elec_high
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
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_USAGE = 1
        const val TAB_ALERTS = 2
        const val TAB_SETTINGS = 3
        private const val LEVEL_FULL_W = 3000.0   // the bar under the watts is full at 3 kW
        const val API_KEY_URL = "https://octopus.energy/dashboard/new/accounts/personal-details/api-access"
        const val SOURCE_URL = "https://github.com/ruwaid-7/octopus-live"
    }
}
