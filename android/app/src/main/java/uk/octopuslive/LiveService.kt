package uk.octopuslive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that polls the Octopus API and keeps an ongoing notification
 * showing live demand (also drawn into the status-bar icon) and today's usage.
 * While the screen is on it refreshes at the chosen interval. While it's off it keeps going
 * every few minutes (using allow-while-idle alarms, so it survives Doze), which keeps the
 * widget current and lets the gas warning fire even when the phone is in a pocket.
 */
class LiveService : Service() {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var nextPoll: ScheduledFuture<*>? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var api: OctopusApi
    private var last: Reading? = null
    private var lastError: String? = null
    private var failures = 0
    private var screenOn = true
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    nextPoll?.cancel(false)
                    scheduleBackground()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    cancelBackground()
                    // Refresh on unlock, but never sooner than the interval since the last fetch
                    val sinceLast = (System.currentTimeMillis() - (last?.fetchedAt ?: 0L)) / 1000
                    schedule((prefs.intervalSeconds - sinceLast).coerceAtLeast(0L))
                }
                ACTION_BG_POLL -> {
                    // Alarm while the screen is off: keep the CPU awake until this refresh is done
                    acquireWakeLock()
                    schedule(0)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = OctopusApi(this)
        last = prefs.loadReading()   // show the previous value straight away
        createChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ACTION_BG_POLL)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground promptly every time we're started.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.running = false
                cancelBackground()
                OctopusWidget.updateAll(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {   // start or refresh
                prefs.running = true
                schedule(0)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {}
        cancelBackground()
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun schedule(delaySeconds: Long) {
        nextPoll?.cancel(false)
        if (executor.isShutdown) return
        nextPoll = executor.schedule({ poll() }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun poll() {
        try {
            val r = api.fetch()
            last = r
            lastError = null
            failures = 0
            prefs.saveReading(r)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            failures++
        }
        prefs.lastError = lastError
        main.post { onPollFinished() }
    }

    private fun onPollFinished() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
        OctopusWidget.updateAll(this)
        prefs.lastSummary = summaryText()
        listener?.invoke(summaryText())
        if (lastError == null) last?.let { checkGasAlert(it) }

        if (!screenOn) {
            scheduleBackground()
            releaseWakeLock()
            return
        }
        // Back off after failures (doubling, max 10 min) so rate limits recover.
        val minSecs = if (prefs.relayUrl.isNullOrBlank()) 30 else 10   // home server can be polled often
        val base = prefs.intervalSeconds.coerceAtLeast(minSecs).toLong()
        val delay = if (failures == 0) base else (base shl (failures - 1).coerceAtMost(4)).coerceAtMost(600L)
        schedule(delay)
    }

    // ---------- background (screen off) ----------

    private fun bgPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 3, Intent(ACTION_BG_POLL).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Next refresh while the screen is off. Allow-while-idle alarms still fire in Doze. */
    private fun scheduleBackground() {
        if (!prefs.running) return
        val am = getSystemService(AlarmManager::class.java)
        val delayMs = BG_INTERVAL_MS.coerceAtLeast(prefs.intervalSeconds * 1000L)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, bgPendingIntent())
    }

    private fun cancelBackground() {
        getSystemService(AlarmManager::class.java).cancel(bgPendingIntent())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OctopusLive:poll")
            .apply { acquire(60_000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) {}
        wakeLock = null
    }

    // ---------- gas warning ----------

    /** Warn once per run when gas has been on for [GAS_ALERT_MINUTES] or more. */
    private fun checkGasAlert(r: Reading) {
        if (!prefs.gasAlert || r.gasTodayKwh == null) return
        if (r.gasRunMinutes < GAS_ALERT_MINUTES) return
        val runKey = "${LocalDate.now()}@${r.gasRunSince}"
        if (prefs.gasAlertSentFor == runKey) return
        prefs.gasAlertSentFor = runKey

        val hours = r.gasRunMinutes / 60
        val mins = r.gasRunMinutes % 60
        val duration = if (mins == 0) "$hours h" else "$hours h $mins min"
        val since = r.gasRunSince?.let { " since $it" } ?: ""
        val openApp = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = "Your gas has been in use for about $duration$since " +
            String.format(Locale.UK, "(%.1f kW in the latest half-hour). ", r.gasKw ?: 0.0) +
            "Gas readings arrive about an hour late, so it may already be off."
        val n = Notification.Builder(this, GAS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle("🔥 Gas on for $duration")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(Color.rgb(255, 138, 61))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(GAS_NOTIF_ID, n)
    }

    // ---------- notification ----------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        val gas = NotificationChannel(GAS_CHANNEL_ID, getString(R.string.gas_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = getString(R.string.gas_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, gas))
    }

    private fun buildNotification(): Notification {
        val r = last
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val refresh = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            r?.demandWatts != null -> "⚡ ${formatWatts(r.demandWatts)} now"
            lastError != null -> "Octopus Live"
            else -> "Connecting to Octopus…"
        }
        val text = buildString {
            if (r != null) {
                append(String.format(Locale.UK, "Today %.2f kWh", r.todayKwh))
                r.gasTodayKwh?.let { append(String.format(Locale.UK, "  ·  Gas %.2f kWh", it)) }
                r.gasKw?.let { if (r.gasTodayKwh != null) append(String.format(Locale.UK, " (%.1f kW)", it)) }
                append("  ·  meter ").append(r.readAt)
            }
            if (lastError != null) {
                if (isNotEmpty()) append("  ·  ")
                append("⚠ ").append(lastError)
            }
            if (isEmpty()) append("Waiting for first reading")
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setColor(levelColor(r?.demandWatts))
            .addAction(Notification.Action.Builder(null, "Refresh", refresh).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val watts = r?.demandWatts
        if (watts != null) builder.setSmallIcon(statusBarIcon(watts)) else builder.setSmallIcon(R.drawable.ic_bolt)
        return builder.build()
    }

    /** Draws the current demand as text so it shows in the status bar, e.g. "850 W" or "1.2 kW". */
    private fun statusBarIcon(watts: Int): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val (value, unit) = if (watts >= 1000) {
            val kw = watts / 1000.0
            (if (kw >= 10) String.format(Locale.UK, "%.0f", kw) else String.format(Locale.UK, "%.1f", kw)) to "kW"
        } else {
            watts.toString() to "W"
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        // Fit the number to the icon width
        paint.textSize = 64f
        val maxWidth = size * 0.98f
        val w = paint.measureText(value)
        if (w > maxWidth) paint.textSize *= maxWidth / w
        canvas.drawText(value, size / 2f, 60f, paint)
        paint.textSize = 34f
        canvas.drawText(unit, size / 2f, 94f, paint)
        return Icon.createWithBitmap(bmp)
    }

    private fun summaryText(): String {
        val r = last
        val sb = StringBuilder()
        if (r != null) {
            sb.append(r.demandWatts?.let { formatWatts(it) } ?: "-- W")
            sb.append(String.format(Locale.UK, "\nToday: %.2f kWh", r.todayKwh))
            r.gasTodayKwh?.let { gas ->
                sb.append(String.format(Locale.UK, "\nGas today: %.2f kWh", gas))
                r.gasReadAt?.let { sb.append(" (to ").append(it).append(")") }
                r.gasKw?.let { sb.append(String.format(Locale.UK, "\nGas rate: %.1f kW (last half-hour)", it)) }
            }
            sb.append("\nMeter reading at ").append(r.readAt)
            if (r.viaServer) sb.append("  ·  via relay")
        }
        if (lastError != null) sb.append(if (sb.isEmpty()) "" else "\n").append("Problem: ").append(lastError)
        r?.let { if (it.gasTodayKwh != null && it.gasRunMinutes >= 30) sb.append("\nGas on for ${it.gasRunMinutes} min") }
        if (!screenOn) sb.append("\n(screen off: updating every few minutes)")
        return sb.toString()
    }

    companion object {
        const val CHANNEL_ID = "live"
        const val NOTIF_ID = 1
        const val ACTION_START = "uk.octopuslive.START"
        const val ACTION_REFRESH = "uk.octopuslive.REFRESH"
        const val ACTION_STOP = "uk.octopuslive.STOP"
        const val ACTION_BG_POLL = "uk.octopuslive.BG_POLL"
        const val GAS_CHANNEL_ID = "gas_alerts"
        const val GAS_NOTIF_ID = 2
        const val GAS_ALERT_MINUTES = 90
        const val BG_INTERVAL_MS = 5 * 60 * 1000L   // refresh every 5 min while the screen is off

        /** Set by MainActivity while visible, to show the latest result. */
        @Volatile var listener: ((String) -> Unit)? = null

        fun formatWatts(w: Int): String =
            if (w >= 1000) String.format(Locale.UK, "%.2f kW", w / 1000.0) else "$w W"

        fun levelColor(w: Int?): Int = when {
            w == null -> Color.rgb(120, 120, 140)
            w < 500 -> Color.rgb(40, 180, 110)
            w < 2000 -> Color.rgb(240, 160, 30)
            else -> Color.rgb(230, 70, 75)
        }

        fun start(context: Context, action: String = ACTION_START) {
            context.startForegroundService(Intent(context, LiveService::class.java).setAction(action))
        }
    }
}
