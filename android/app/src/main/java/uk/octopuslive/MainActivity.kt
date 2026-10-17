package uk.octopuslive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private lateinit var interval: EditText
    private lateinit var boot: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        apiKey = findViewById(R.id.apiKey)
        account = findViewById(R.id.account)
        interval = findViewById(R.id.interval)
        boot = findViewById(R.id.boot)

        apiKey.setText(prefs.apiKey.orEmpty())
        account.setText(prefs.accountNumber.orEmpty())
        interval.setText(prefs.intervalSeconds.toString())
        boot.isChecked = prefs.startOnBoot

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
        LiveService.listener = { text -> runOnUiThread { status.text = text } }
    }

    override fun onPause() {
        LiveService.listener = null
        super.onPause()
    }

    private fun saveAndStart() {
        val key = apiKey.text.toString().trim()
        if (key.isEmpty()) {
            apiKey.error = getString(R.string.key_required)
            return
        }
        val acct = account.text.toString().trim().uppercase()
        val secs = interval.text.toString().toIntOrNull()?.coerceIn(30, 3600) ?: 60

        if (key != prefs.apiKey || acct != prefs.accountNumber.orEmpty()) prefs.clearCachedApiState()
        prefs.apiKey = key
        prefs.accountNumber = acct
        prefs.intervalSeconds = secs
        prefs.startOnBoot = boot.isChecked
        interval.setText(secs.toString())

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
        LiveService.start(this, LiveService.ACTION_REFRESH)
        status.text = getString(R.string.connecting)
    }

    companion object {
        private const val REQ_NOTIFY = 1
        const val API_KEY_URL = "https://octopus.energy/dashboard/new/accounts/personal-details/api-access"
    }
}
