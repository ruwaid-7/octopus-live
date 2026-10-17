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

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var apiKey: EditText
    private lateinit var account: EditText
    private lateinit var relayUrl: EditText
    private lateinit var interval: EditText
    private lateinit var boot: CheckBox
    private lateinit var gasAlert: CheckBox
    private lateinit var bgStatus: TextView

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
        gasAlert = findViewById(R.id.gasAlert)
        bgStatus = findViewById(R.id.bgStatus)

        apiKey.setText(prefs.apiKey.orEmpty())
        account.setText(prefs.accountNumber.orEmpty())
        relayUrl.setText(prefs.relayUrl.orEmpty())
        interval.setText(prefs.intervalSeconds.toString())
        boot.isChecked = prefs.startOnBoot
        gasAlert.isChecked = prefs.gasAlert
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
        LiveService.listener = { text -> runOnUiThread { status.text = text } }
        updateBackgroundStatus()
    }

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
        prefs.gasAlert = gasAlert.isChecked
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
