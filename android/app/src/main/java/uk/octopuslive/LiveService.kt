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
import android.content.ComponentName
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
import android.service.quicksettings.TileService
import java.time.LocalDate
import java.time.LocalTime
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
    private lateinit var insights: Insights
    private var ins: InsightsData? = null
    private var highSince = 0L          // when electricity first went over the warning level
    private var highNotified = false

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
        insights = Insights(this)
        last = prefs.loadReading()   // show the previous value straight away
        highSince = prefs.long(K_HIGH_SINCE)
        ins = insights.load()
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
            ACTION_MUTE_GAS, ACTION_MUTE_HIGH -> {
                val nm = getSystemService(NotificationManager::class.java)
                if (intent?.action == ACTION_MUTE_GAS) {
                    prefs.mutedGasRun = prefs.gasAlertSentFor
                    nm.cancel(GAS_NOTIF_ID)
                } else {
                    prefs.mutedHighSince = highSince
                    nm.cancel(HIGH_NOTIF_ID)
                }
                if (!prefs.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            }
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
        // Slow look-ups (tariff, prices, history): at most once a minute, needs the API key
        try { ins = insights.refresh() } catch (e: Exception) {}
        main.post { onPollFinished() }
    }

    private fun onPollFinished() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
        OctopusWidget.updateAll(this)
        prefs.lastSummary = summaryText()
        listener?.invoke(summaryText())
        if (lastError == null) last?.let { checkGasAlert(it); checkHighUsage(it) }
        checkOffline()
        ins?.let { checkCheapPrices(); checkDailySummary(it) }
        try { TileService.requestListeningState(this, ComponentName(this, LiveTile::class.java)) } catch (e: Exception) {}

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

    // ---------- gas and high-use warnings ----------

    /**
     * Gas on for [GAS_ALERT_MINUTES] or more. With "keep warnings on screen" the notification stays
     * (and is re-shown if swiped away) until the gas goes off or it's muted; otherwise it's sent
     * once per run and can be dismissed.
     */
    private fun checkGasAlert(r: Reading) {
        val nm = getSystemService(NotificationManager::class.java)
        val on = prefs.gasAlert && r.gasTodayKwh != null && r.gasRunMinutes >= GAS_ALERT_MINUTES
        if (!on) {
            if (prefs.stickyWarnings) nm.cancel(GAS_NOTIF_ID)
            return
        }
        val runKey = "${LocalDate.now()}@${r.gasRunSince}"
        if (prefs.mutedGasRun == runKey) return
        val firstTime = prefs.gasAlertSentFor != runKey
        if (!prefs.stickyWarnings && !firstTime) return
        prefs.gasAlertSentFor = runKey

        val since = r.gasRunSince?.let { "Since $it" } ?: "Still on"
        val text = since + String.format(Locale.UK, " · %.1f kW in the latest half-hour. ", r.gasKw ?: 0.0) +
            "Gas readings arrive about an hour late, so it may already be off."
        postWarning(
            GAS_NOTIF_ID, "Gas on for ${duration(r.gasRunMinutes)}", text,
            Color.rgb(240, 122, 42), R.drawable.ic_flame, quiet = !firstTime, muteAction = ACTION_MUTE_GAS,
        )
    }

    /** Electricity above the chosen level for the chosen time (e.g. 3 kW for 45 min). */
    private fun checkHighUsage(r: Reading) {
        val w = r.demandWatts ?: return
        val nm = getSystemService(NotificationManager::class.java)
        if (w < prefs.highKw * 1000) {
            if (highSince != 0L) setHighSince(0L)
            if (prefs.stickyWarnings) nm.cancel(HIGH_NOTIF_ID)
            return
        }
        if (highSince == 0L) setHighSince(System.currentTimeMillis())
        val mins = ((System.currentTimeMillis() - highSince) / 60_000).toInt()
        if (!prefs.highAlert || mins < prefs.highMinutes || prefs.mutedHighSince == highSince) {
            if (!prefs.highAlert && prefs.stickyWarnings) nm.cancel(HIGH_NOTIF_ID)
            return
        }
        val firstTime = !highNotified
        if (!prefs.stickyWarnings && !firstTime) return
        highNotified = true
        postWarning(
            HIGH_NOTIF_ID, "Electricity above ${trimKw(prefs.highKw)} kW for ${duration(mins)}",
            "Since ${Insights.formatTime(highSince)} · ${formatWatts(w)} now. Is something left on?",
            Color.rgb(229, 72, 77), R.drawable.ic_bolt, quiet = !firstTime, muteAction = ACTION_MUTE_HIGH,
        )
    }

    private fun setHighSince(t: Long) {
        highSince = t
        highNotified = false
        prefs.setLong(K_HIGH_SINCE, t)
    }

    /** A warning notification; "sticky" ones stay until usage drops and offer "Mute until off". */
    private fun postWarning(id: Int, title: String, text: String, colour: Int, icon: Int, quiet: Boolean, muteAction: String) {
        val sticky = prefs.stickyWarnings
        val openApp = PendingIntent.getActivity(this, 10 + id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Re-shown warnings (e.g. after being swiped away) use a channel with no sound or pop-up
        val b = Notification.Builder(this, if (quiet) WARN_QUIET_CHANNEL_ID else GAS_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(colour)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
        if (sticky) {
            val mute = PendingIntent.getService(
                this, 20 + id, Intent(this, LiveService::class.java).setAction(muteAction), PendingIntent.FLAG_IMMUTABLE,
            )
            b.setOngoing(true)
                .setAutoCancel(false)
                .addAction(Notification.Action.Builder(null, getString(R.string.mute_until_off), mute).build())
        } else {
            b.setAutoCancel(true)
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    // ---------- other warnings and notices ----------

    private fun notifyAlert(id: Int, channel: String, title: String, text: String, color: Int, icon: Int = R.drawable.ic_bolt) {
        val openApp = PendingIntent.getActivity(this, 10 + id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(color)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    /** No new meter readings (or no contact at all) for 30 minutes. */
    private fun checkOffline() {
        if (!prefs.offlineAlert) return
        val now = System.currentTimeMillis()
        val r = last
        val readAt = r?.readAtMs
        val (stale, key, text) = when {
            lastError != null && r != null && now - r.fetchedAt > OFFLINE_MS ->
                Triple(true, "err@${r.fetchedAt}", "No readings for ${(now - r.fetchedAt) / 60_000} min: $lastError")
            lastError == null && readAt != null && now - readAt > OFFLINE_MS ->
                Triple(true, "mini@$readAt", "The latest meter reading is from ${Insights.formatTime(readAt)}. " +
                    "Your Home Mini may be offline - check its light, or unplug it for 10 seconds.")
            else -> Triple(false, "", "")
        }
        if (!stale || prefs.str(K_OFFLINE_SENT) == key) return
        prefs.setStr(K_OFFLINE_SENT, key)
        notifyAlert(OFFLINE_NOTIF_ID, GAS_CHANNEL_ID, "No new energy readings", text, Color.rgb(255, 179, 0))
    }

    /** Agile / Go etc: a heads-up for upcoming half-hours at or below the chosen price. */
    private fun checkCheapPrices() {
        if (!prefs.cheapAlert) return
        val blocks = insights.cheapBlocks(prefs.cheapPence.toDouble(), 18 * 3600_000L)
        val sent = prefs.str(K_CHEAP_SENT).orEmpty().split(",").filter { it.isNotBlank() }.toMutableList()
        for (b in blocks) {
            val key = b.from.toString()
            if (key in sent) continue
            sent += key
            val day = if (java.time.Instant.ofEpochMilli(b.from).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == LocalDate.now()) "Today" else "Tomorrow"
            notifyAlert(
                CHEAP_NOTIF_ID, INFO_CHANNEL_ID,
                String.format(Locale.UK, "Cheap electricity %s %s–%s", day.lowercase(), Insights.formatTime(b.from), Insights.formatTime(b.to)),
                String.format(Locale.UK, "%s from %s to %s, as low as %.1fp/kWh. A good time for the washing machine, dishwasher or charging.",
                    day, Insights.formatTime(b.from), Insights.formatTime(b.to), b.p),
                Color.rgb(0, 230, 118),
            )
        }
        prefs.setStr(K_CHEAP_SENT, sent.takeLast(50).joinToString(","))
    }

    /** Once a day after the chosen hour: yesterday's usage and cost. */
    private fun checkDailySummary(d: InsightsData) {
        if (!prefs.summaryAlert) return
        val today = LocalDate.now()
        if (LocalTime.now().hour < prefs.summaryHour || prefs.str(K_SUMMARY_SENT) == today.toString()) return
        val y = d.history.firstOrNull { it.date == today.minusDays(1).toString() } ?: return
        if (y.elecKwh <= 0) return   // no readings that day (e.g. Home Mini not set up yet)
        // Wait until yesterday's late gas readings have been collected (after 03:00)
        val complete = today.atStartOfDay(java.time.ZoneId.systemDefault()).plusHours(3).toInstant().toEpochMilli()
        if (y.fetchedAt < complete) return
        prefs.setStr(K_SUMMARY_SENT, today.toString())

        val parts = mutableListOf<String>()
        parts += String.format(Locale.UK, "Electricity %.1f kWh", y.elecKwh) + (y.elecCost?.let { String.format(Locale.UK, " (£%.2f)", it) } ?: "")
        y.gasKwh?.let { g -> parts += String.format(Locale.UK, "gas %.1f kWh", g) + (y.gasCost?.let { String.format(Locale.UK, " (£%.2f)", it) } ?: "") }
        var text = parts.joinToString(", ") + "."
        val total = (y.elecCost ?: 0.0) + (y.gasCost ?: 0.0)
        val before = d.history.firstOrNull { it.date == today.minusDays(2).toString() }
        if (total > 0) {
            text += String.format(Locale.UK, " Total £%.2f", total)
            // Only compare with a full day (a part day has no always-on figure), so % changes are fair
            val prevTotal = before?.takeIf { it.baseW != null }?.let { (it.elecCost ?: 0.0) + (it.gasCost ?: 0.0) }
            if (prevTotal != null && prevTotal > 0) {
                val pct = Math.round((total - prevTotal) / prevTotal * 100)
                text += when {
                    pct > 0 -> " — $pct% more than the day before."
                    pct < 0 -> " — ${-pct}% less than the day before."
                    else -> " — the same as the day before."
                }
            } else text += "."
        }
        y.baseW?.let { text += " Always-on: $it W." }
        notifyAlert(SUMMARY_NOTIF_ID, INFO_CHANNEL_ID, "Yesterday's energy", text, Color.rgb(232, 71, 139))
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
        val info = NotificationChannel(INFO_CHANNEL_ID, getString(R.string.info_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.info_channel_description)
        }
        val quiet = NotificationChannel(WARN_QUIET_CHANNEL_ID, getString(R.string.quiet_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = getString(R.string.quiet_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, gas, info, quiet))
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
            r?.demandWatts != null -> formatWatts(r.demandWatts)
            lastError != null -> "Octopus Live"
            else -> "Connecting…"
        }
        val text = buildString {
            if (r != null) {
                append(String.format(Locale.UK, "Today %.2f kWh", r.todayKwh))
                ins?.elecCostToday?.let { append(String.format(Locale.UK, " · £%.2f", it)) }
                r.gasTodayKwh?.let { append(String.format(Locale.UK, "  ·  Gas %.2f kWh", it)) }
                if (r.gasTodayKwh != null && r.gasRunMinutes > 0) append(" (on)")
            }
            if (lastError != null) {
                if (isNotEmpty()) append("  ·  ")
                append("Couldn't update: ").append(lastError)
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
            .setSubText(r?.let { "Meter ${it.readAt}" })
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
            ins?.elecCostToday?.let { sb.append(String.format(Locale.UK, " · £%.2f", it)) }
            r.gasTodayKwh?.let { gas ->
                sb.append(String.format(Locale.UK, "\nGas today: %.2f kWh", gas))
                ins?.gasCostToday?.let { sb.append(String.format(Locale.UK, " · £%.2f", it)) }
                r.gasReadAt?.let { sb.append(" (to ").append(it).append(")") }
                r.gasKw?.let { sb.append(String.format(Locale.UK, "\nGas rate: %.1f kW (last half-hour)", it)) }
            }
            sb.append("\nMeter reading at ").append(r.readAt)
            if (r.viaServer) sb.append("  ·  via relay")
            ins?.let { i ->
                i.priceNow?.let { p ->
                    sb.append(String.format(Locale.UK, "\nPrice now: %.1fp/kWh", p))
                    if (i.priceNext != null && i.priceNextAt != null) {
                        sb.append(String.format(Locale.UK, " (%.1fp from %s)", i.priceNext, Insights.formatTime(i.priceNextAt)))
                    }
                }
                i.baseW?.let { sb.append("\nAlways-on today: $it W") }
            }
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
        const val ACTION_MUTE_GAS = "uk.octopuslive.MUTE_GAS"
        const val ACTION_MUTE_HIGH = "uk.octopuslive.MUTE_HIGH"
        const val K_HIGH_SINCE = "alert_highSince"
        const val GAS_CHANNEL_ID = "gas_alerts"
        const val WARN_QUIET_CHANNEL_ID = "warnings_ongoing"
        const val GAS_NOTIF_ID = 2
        const val GAS_ALERT_MINUTES = 90
        const val BG_INTERVAL_MS = 5 * 60 * 1000L   // refresh every 5 min while the screen is off
        const val INFO_CHANNEL_ID = "info"
        const val HIGH_NOTIF_ID = 3
        const val OFFLINE_NOTIF_ID = 4
        const val CHEAP_NOTIF_ID = 5
        const val SUMMARY_NOTIF_ID = 6
        const val OFFLINE_MS = 30 * 60_000L
        private const val K_OFFLINE_SENT = "alert_offlineSent"
        private const val K_CHEAP_SENT = "alert_cheapSent"
        private const val K_SUMMARY_SENT = "alert_summarySent"

        /** Set by MainActivity while visible, to show the latest result. */
        @Volatile var listener: ((String) -> Unit)? = null

        fun duration(min: Int): String = when {
            min < 60 -> "$min min"
            min % 60 == 0 -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }

        fun trimKw(v: Float): String =
            if (v == Math.floor(v.toDouble()).toFloat()) v.toLong().toString() else String.format(Locale.UK, "%.1f", v)

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
