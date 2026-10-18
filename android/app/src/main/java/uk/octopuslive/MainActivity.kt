package uk.octopuslive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var account: EditText
    private lateinit var relayUrl: EditText
    private lateinit var interval: EditText
    private lateinit var boot: CheckBox
    private lateinit var widgetOpensApp: CheckBox
    private lateinit var gasAlert: CheckBox
    private lateinit var bgStatus: TextView
    private lateinit var insightsText: TextView
    private lateinit var weekText: TextView
    private lateinit var chartToday: BarChartView
    private lateinit var chartWeek: BarChartView
    private lateinit var highAlert: CheckBox
    private lateinit var highKw: EditText
    private lateinit var highMinutes: EditText
    private lateinit var offlineAlert: CheckBox
    private lateinit var cheapAlert: CheckBox
    private lateinit var cheapPence: EditText
    private lateinit var summaryAlert: CheckBox
    private lateinit var summaryHour: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        apiKey = findViewById(R.id.apiKey)
        account = findViewById(R.id.account)
        relayUrl = findViewById(R.id.relayUrl)
        interval = findViewById(R.id.interval)
        boot = findViewById(R.id.boot)
        widgetOpensApp = findViewById(R.id.widgetOpensApp)
        gasAlert = findViewById(R.id.gasAlert)
        bgStatus = findViewById(R.id.bgStatus)
        insightsText = findViewById(R.id.insightsText)
        weekText = findViewById(R.id.weekText)
        chartToday = findViewById(R.id.chartToday)
        chartWeek = findViewById(R.id.chartWeek)
        highAlert = findViewById(R.id.highAlert)
        highKw = findViewById(R.id.highKw)
        highMinutes = findViewById(R.id.highMinutes)
        offlineAlert = findViewById(R.id.offlineAlert)
        cheapAlert = findViewById(R.id.cheapAlert)
        cheapPence = findViewById(R.id.cheapPence)
        summaryAlert = findViewById(R.id.summaryAlert)
        summaryHour = findViewById(R.id.summaryHour)

        apiKey.setText(prefs.apiKey.orEmpty())
        account.setText(prefs.accountNumber.orEmpty())
        relayUrl.setText(prefs.relayUrl.orEmpty())
        interval.setText(prefs.intervalSeconds.toString())
        boot.isChecked = prefs.startOnBoot
        widgetOpensApp.isChecked = prefs.widgetOpensApp
        gasAlert.isChecked = prefs.gasAlert
        highAlert.isChecked = prefs.highAlert
        highKw.setText(trim(prefs.highKw.toDouble()))
        highMinutes.setText(prefs.highMinutes.toString())
        offlineAlert.isChecked = prefs.offlineAlert
        cheapAlert.isChecked = prefs.cheapAlert
        cheapPence.setText(trim(prefs.cheapPence.toDouble()))
        summaryAlert.isChecked = prefs.summaryAlert
        summaryHour.setText(prefs.summaryHour.toString())
        findViewById<Button>(R.id.bgBattery).setOnClickListener { requestUnrestrictedBattery() }
        findViewById<Button>(R.id.bgUnused).setOnClickListener { openUnusedAppSettings() }

        findViewById<Button>(R.id.start).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            LiveService.start(this, LiveService.ACTION_STOP)
            status.text = getString(R.string.stopped)
        }
        findViewById<TextView>(R.id.getKey).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEY_URL)))
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = prefs.lastSummary ?: getString(R.string.not_started)
        OctopusWidget.updateAll(this)   // keep widgets in step with the installed version
        LiveService.listener = { text -> runOnUiThread { status.text = text; showInsights() } }
        updateBackgroundStatus()
        showInsights()
    }

    // ---------- costs & history ----------

    private fun showInsights() {
        val d = Insights(this).load()
        if (prefs.apiKey.isNullOrBlank()) {
            insightsText.text = getString(R.string.insights_need_key)
        } else if (d == null) {
            insightsText.text = getString(R.string.insights_waiting)
        } else {
            val lines = mutableListOf<String>()
            val today = (d.elecCostToday ?: 0.0) + (d.gasCostToday ?: 0.0)
            if (d.elecCostToday != null) {
                lines += String.format(Locale.UK, "Today so far: £%.2f", today) +
                    (if (d.gasCostToday != null) String.format(Locale.UK, " (electricity £%.2f, gas £%.2f)", d.elecCostToday, d.gasCostToday) else "")
            }
            d.priceNow?.let { p ->
                var line = String.format(Locale.UK, "Electricity price now: %.1fp/kWh", p)
                if (d.priceNext != null && d.priceNextAt != null) {
                    line += String.format(Locale.UK, ", %.1fp from %s", d.priceNext, Insights.formatTime(d.priceNextAt))
                }
                lines += line
            }
            d.baseW?.let { lines += "Always-on today: $it W (your lowest half-hour)" }
            d.tariff?.let { lines += "Tariff: " + (tariffName(it)?.let { n -> "$n ($it)" } ?: it) }
            if (d.elecCostToday == null && d.tariff != null) lines += "Costs aren't available for this tariff yet (e.g. Economy 7)."
            d.error?.let { lines += "Last update problem: $it" }
            insightsText.text = if (lines.isEmpty()) getString(R.string.insights_waiting) else lines.joinToString("\n")
        }

        val pink = getColor(R.color.brand_pink)
        val gasColor = getColor(R.color.gas_orange)
        // Today: 48 half-hours, labelled every 6 hours
        val slots = d?.todaySlots ?: emptyList()
        val labels = (0 until 48).map { if (it % 12 == 0) String.format(Locale.UK, "%02d", it / 2) else "" }
        chartToday.setData(listOf(slots), listOf(pink), labels)

        // Last 7 days, electricity and gas side by side
        val days = (7 downTo 1).map { LocalDate.now().minusDays(it.toLong()) }
        val byDate = d?.history?.associateBy { it.date } ?: emptyMap()
        val elec = days.map { byDate[it.toString()]?.elecKwh ?: 0.0 }
        val gas = days.map { byDate[it.toString()]?.gasKwh ?: 0.0 }
        val dayLabels = days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK) }
        val hasGas = d?.history?.any { it.gasKwh != null } == true
        chartWeek.setData(
            if (hasGas) listOf(elec, gas) else listOf(elec),
            if (hasGas) listOf(pink, gasColor) else listOf(pink),
            dayLabels, highlightIndex = 6, emptyText = "Collecting the last 7 days…",
        )
        // Only days that actually have readings (not days before the Home Mini was set up)
        val known = d?.history.orEmpty().filter { it.elecKwh > 0 }
        weekText.text = if (known.isEmpty()) "" else {
            val elecTotal = known.sumOf { it.elecKwh }
            val gasTotal = known.sumOf { it.gasKwh ?: 0.0 }
            val cost = known.sumOf { (it.elecCost ?: 0.0) + (it.gasCost ?: 0.0) }
            val base = known.mapNotNull { it.baseW }.minOrNull()
            buildString {
                append(String.format(Locale.UK, "Last %s: electricity %.1f kWh", if (known.size == 1) "day" else "${known.size} days", elecTotal))
                if (hasGas) append(String.format(Locale.UK, ", gas %.1f kWh", gasTotal))
                if (cost > 0) append(String.format(Locale.UK, ", £%.2f", cost))
                base?.let { append(".\nLowest always-on: $it W — leave it running all year and that's about ").append(
                    String.format(Locale.UK, "%.0f kWh", it * 24 * 365 / 1000.0)).append(" a year.") }
                if (known.size < 7) append("\n(History builds up from the day your Home Mini started sending readings.)")
            }
        }
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

    // ---------- background running ----------

    private fun isBatteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** True when Android won't pause the app or remove its permissions for not being opened. */
    private fun isExemptFromUnusedPause(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) packageManager.isAutoRevokeWhitelisted else true

    private fun updateBackgroundStatus() {
        bgStatus.text = getString(if (isBatteryUnrestricted()) R.string.bg_battery_ok else R.string.bg_battery_no) +
            "\n" + getString(if (isExemptFromUnusedPause()) R.string.bg_unused_ok else R.string.bg_unused_no)
    }

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

    private fun tryStart(intent: Intent, fallback: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            try { startActivity(fallback) } catch (e2: Exception) {}
        }
    }

    override fun onPause() {
        LiveService.listener = null
        super.onPause()
    }

    private fun saveAndStart() {
        val key = apiKey.text.toString().trim()
        val relay = relayUrl.text.toString().trim()
        if (key.isEmpty() && relay.isEmpty()) {
            apiKey.error = getString(R.string.key_required)
            return
        }
        val acct = account.text.toString().trim().uppercase()
        val minSecs = if (relay.isEmpty()) 30 else 10
        val secs = interval.text.toString().toIntOrNull()?.coerceIn(minSecs, 3600) ?: 60
        prefs.relayUrl = relay

        if (key != prefs.apiKey || acct != prefs.accountNumber.orEmpty()) prefs.clearCachedApiState()
        prefs.apiKey = key
        prefs.accountNumber = acct
        prefs.intervalSeconds = secs
        prefs.startOnBoot = boot.isChecked
        prefs.widgetOpensApp = widgetOpensApp.isChecked
        prefs.gasAlert = gasAlert.isChecked
        prefs.highAlert = highAlert.isChecked
        prefs.highKw = highKw.text.toString().toFloatOrNull()?.coerceIn(0.5f, 20f) ?: 3f
        prefs.highMinutes = highMinutes.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 45
        prefs.offlineAlert = offlineAlert.isChecked
        prefs.cheapAlert = cheapAlert.isChecked
        prefs.cheapPence = cheapPence.text.toString().toFloatOrNull()?.coerceIn(-50f, 100f) ?: 0f
        prefs.summaryAlert = summaryAlert.isChecked
        prefs.summaryHour = summaryHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 8
        OctopusWidget.updateAll(this)   // apply the widget tap setting straight away
        highKw.setText(trim(prefs.highKw.toDouble()))
        highMinutes.setText(prefs.highMinutes.toString())
        cheapPence.setText(trim(prefs.cheapPence.toDouble()))
        summaryHour.setText(prefs.summaryHour.toString())
        interval.setText(secs.toString())
        currentFocus?.clearFocus()

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
        LiveService.start(this, LiveService.ACTION_REFRESH)
        status.text = getString(R.string.connecting)
        Toast.makeText(this, if (wasRunning) R.string.saved_restarted else R.string.saved_started, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_NOTIFY = 1
        const val API_KEY_URL = "https://octopus.energy/dashboard/new/accounts/personal-details/api-access"
    }
}
