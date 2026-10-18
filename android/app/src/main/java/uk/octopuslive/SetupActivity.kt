package uk.octopuslive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/**
 * First-run setup, shown once on a new install:
 *   Three intro cards (swipe or Next; Skip jumps to setup) -> How do you want to connect? (this phone / I have a relay / make a free relay)
 *   -> API key or relay address -> choose alerts -> notifications and background running -> Home.
 * Permissions are asked on the last step, next to the reason for them, rather than at launch.
 * Every setup step has a small Skip: the app opens and can be set up later in Settings.
 */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private val history = ArrayDeque<Int>()
    private var step = WELCOME
    private var relayBefore: String? = null   // to notice a relay arriving from its web page
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        step = savedInstanceState?.getInt(K_STEP) ?: intent.getIntExtra(EXTRA_STEP, WELCOME)
        savedInstanceState?.getIntArray(K_HISTORY)?.forEach { history.addLast(it) }
        show(step, remember = false)
    }

    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val i = INTRO.indexOf(step)
                if (i < 0 || e1 == null) return false
                val dx = e2.x - e1.x
                if (Math.abs(dx) < 80 * dp || Math.abs(dx) < Math.abs(e2.y - e1.y)) return false
                if (dx < 0) show(if (i < INTRO.size - 1) INTRO[i + 1] else CHOOSE)
                else if (history.isNotEmpty()) @Suppress("DEPRECATION") onBackPressed()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (step in INTRO && swipe.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_STEP, step)
        outState.putIntArray(K_HISTORY, history.toIntArray())
    }

    override fun onResume() {
        super.onResume()
        // Came back after tapping "Open in Octopus Live" on the relay page: the relay is already saved
        val relayNow = prefs.relayUrl.orEmpty()
        if ((step == MAKE_RELAY || step == RELAY) && relayNow.isNotBlank() && relayNow != relayBefore) show(ALERTS)
        else if (step == FINISH) show(FINISH, remember = false)   // refresh the permission ticks
    }

    @Deprecated("Back goes to the previous step")
    override fun onBackPressed() {
        if (history.isEmpty()) @Suppress("DEPRECATION") super.onBackPressed()
        else show(history.removeLast(), remember = false)
    }

    private fun show(next: Int, remember: Boolean = true) {
        if (remember && next != step) history.addLast(step)
        step = next
        if (next == MAKE_RELAY || next == RELAY) relayBefore = prefs.relayUrl.orEmpty()
        hideKeyboard()
        val content = page()
        when (next) {
            WELCOME, INTRO_WIDGET, INTRO_ALERTS -> intro(content, INTRO.indexOf(next))
            CHOOSE -> choose(content)
            KEY -> key(content)
            RELAY -> relay(content)
            MAKE_RELAY -> makeRelay(content)
            ALERTS -> alerts(content)
            else -> finishStep(content)
        }
    }

    // ================================================================= steps

    /** One intro card per screen, with page dots. */
    private fun intro(c: LinearLayout, index: Int) {
        val (icon, title, body) = listOf(
            Triple(R.drawable.ic_bolt, R.string.ob_intro1_title, R.string.ob_intro1_body),
            Triple(R.drawable.ic_widgets, R.string.ob_intro2_title, R.string.ob_intro2_body),
            Triple(R.drawable.ic_nav_alerts, R.string.ob_intro3_title, R.string.ob_intro3_body),
        )[index]
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val card = card(top = 0).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(px(24), px(36), px(24), px(32)) }
        card.addView(if (index == 0) logo() else iconBadge(icon, size = 88), LinearLayout.LayoutParams(px(88), px(88)))
        card.addView(TextView(this, null, 0, R.style.PageTitle).apply { setText(title); gravity = Gravity.CENTER; textSize = 24f }, full(top = 24))
        card.addView(text(getString(body), 15f, R.color.text_secondary).apply { gravity = Gravity.CENTER }, full(top = 10))
        c.addView(card)
        // Page dots
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        INTRO.indices.forEach { i ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 4 * dp
                    setColor(getColor(if (i == index) R.color.brand_pink else R.color.card_stroke))
                }
            }, LinearLayout.LayoutParams(px(if (i == index) 20 else 8), px(8)).apply { marginStart = px(3); marginEnd = px(3) })
        }
        c.addView(dots, full(top = 24))
        c.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val last = index == INTRO.size - 1
        c.addView(primary(getString(if (last) R.string.ob_get_started else R.string.ob_next)) {
            show(if (last) CHOOSE else INTRO[index + 1])
        }, full(top = 28))
        c.addView(agreement(), full(top = 14))
    }

    /** "By continuing you agree to the Terms of use and Privacy policy", both tappable. */
    private fun agreement(): TextView {
        val terms = getString(R.string.about_terms)
        val privacy = getString(R.string.about_privacy)
        val full = getString(R.string.ob_agree, terms, privacy)
        val span = SpannableString(full)
        fun link(word: String, doc: String) {
            val at = full.indexOf(word)
            if (at < 0) return
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = AboutActivity.start(this@SetupActivity, doc)
                override fun updateDrawState(ds: TextPaint) { ds.color = getColor(R.color.text_secondary); ds.isUnderlineText = true }
            }, at, at + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        link(terms, AboutActivity.DOC_TERMS)
        link(privacy, AboutActivity.DOC_PRIVACY)
        return text("", 12f, R.color.text_muted).apply {
            text = span
            gravity = Gravity.CENTER
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0
        }
    }

    private fun choose(c: LinearLayout) {
        header(c, R.string.ob_choose_title, R.string.ob_choose_body)
        val card = card(top = 24, padded = false)
        option(card, R.drawable.ic_phone, getString(R.string.ob_opt_phone), getString(R.string.ob_opt_phone_sub), getString(R.string.ob_recommended)) { show(KEY) }
        card.addView(divider())
        option(card, R.drawable.ic_link, getString(R.string.ob_opt_relay), getString(R.string.ob_opt_relay_sub)) { show(RELAY) }
        card.addView(divider())
        option(card, R.drawable.ic_cloud, getString(R.string.ob_opt_make), getString(R.string.ob_opt_make_sub)) { show(MAKE_RELAY) }
        c.addView(card)
        c.addView(text(getString(R.string.ob_choose_note), 13f, R.color.text_muted), full(top = 14).apply { marginStart = px(4); marginEnd = px(4) })
    }

    private fun key(c: LinearLayout) {
        header(c, R.string.ob_key_title, R.string.ob_key_body)
        val card = card(top = 24)
        card.addView(secondary(getString(R.string.ob_key_open)) { open(MainActivity.API_KEY_URL) }, full())
        card.addView(label(getString(R.string.api_key)), full(top = 18))
        val key = input("sk_live_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            typeface = Typeface.MONOSPACE
            setText(prefs.apiKey.orEmpty())
        }
        card.addView(key, full(top = 6))
        card.addView(text(getString(R.string.ob_key_private), 12f, R.color.text_muted), full(top = 6))
        card.addView(label(getString(R.string.ob_account_label)), full(top = 16))
        val account = input("A-1234ABCD", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).apply {
            setText(prefs.accountNumber.orEmpty())
        }
        card.addView(account, full(top = 6))
        c.addView(card)
        c.addView(primary(getString(R.string.ob_continue)) {
            val k = key.text.toString().trim()
            if (!k.startsWith("sk_")) { key.error = getString(R.string.ob_key_invalid); key.requestFocus(); return@primary }
            val acct = account.text.toString().trim().uppercase(Locale.UK)
            if (k != prefs.apiKey.orEmpty() || acct != prefs.accountNumber.orEmpty()) prefs.clearCachedApiState()
            prefs.apiKey = k
            prefs.accountNumber = acct
            prefs.relayUrl = ""
            if (prefs.intervalSeconds < 30) prefs.intervalSeconds = 60
            show(ALERTS)
        }, full(top = 24))
    }

    private fun relay(c: LinearLayout) {
        header(c, R.string.ob_relay_title, R.string.ob_relay_body)
        val card = card(top = 24)
        card.addView(label(getString(R.string.ob_relay_label)), full())
        val url = input("https://…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(prefs.relayUrl.orEmpty())
        }
        card.addView(url, full(top = 6))
        card.addView(text(getString(R.string.ob_relay_help), 12f, R.color.text_muted), full(top = 6))
        c.addView(card)
        c.addView(primary(getString(R.string.ob_continue)) {
            val u = url.text.toString().trim()
            val parsed = Uri.parse(u)
            if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
                url.error = getString(R.string.ob_relay_invalid); url.requestFocus(); return@primary
            }
            if (u != prefs.relayUrl.orEmpty()) prefs.clearCachedApiState()
            prefs.relayUrl = u
            if (prefs.intervalSeconds > 15) prefs.intervalSeconds = 15
            show(ALERTS)
        }, full(top = 24))
    }

    private fun makeRelay(c: LinearLayout) {
        header(c, R.string.ob_make_title, R.string.ob_make_body)
        val card = card(top = 24)
        listOf(R.string.ob_make_step1, R.string.ob_make_step2, R.string.ob_make_step3).forEachIndexed { i, s ->
            numbered(card, i + 1, getString(s), top = if (i == 0) 0 else 16)
        }
        c.addView(card)
        c.addView(primary(getString(R.string.ob_make_open)) { open(DEPLOY_URL) }, full(top = 24))
        c.addView(secondary(getString(R.string.ob_make_have_link)) { show(RELAY) }, full(top = 10))
        c.addView(link(getString(R.string.ob_make_later)) { show(KEY) }, wrap(top = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun alerts(c: LinearLayout) {
        header(c, R.string.ob_alerts_title, R.string.ob_alerts_body)
        val card = card(top = 24, padded = false)
        val rows = listOf(
            Triple(R.string.gas_alert, R.string.gas_alert_sub, prefs.gasAlert) to { on: Boolean -> prefs.gasAlert = on },
            Triple(R.string.high_alert, R.string.ob_alert_high_sub, prefs.highAlert) to { on: Boolean -> prefs.highAlert = on },
            Triple(R.string.sticky_alert, R.string.sticky_alert_sub, prefs.stickyWarnings) to { on: Boolean -> prefs.stickyWarnings = on },
            Triple(R.string.cheap_alert, R.string.cheap_alert_sub, prefs.cheapAlert) to { on: Boolean -> prefs.cheapAlert = on },
            Triple(R.string.summary_alert, R.string.summary_alert_sub, prefs.summaryAlert) to { on: Boolean -> prefs.summaryAlert = on },
            Triple(R.string.widget_opens_app, R.string.widget_opens_app_sub, prefs.widgetOpensApp) to { on: Boolean -> prefs.widgetOpensApp = on },
        )
        rows.forEachIndexed { i, (t, save) ->
            if (i > 0) card.addView(divider())
            switchRow(card, getString(t.first), getString(t.second), t.third, save)
        }
        c.addView(card)
        c.addView(text(getString(R.string.ob_alerts_note), 13f, R.color.text_muted), full(top = 14).apply { marginStart = px(4) })
        c.addView(primary(getString(R.string.ob_continue)) { show(FINISH) }, full(top = 24))
    }

    private fun finishStep(c: LinearLayout) {
        header(c, R.string.ob_finish_title, R.string.ob_finish_body)
        val card = card(top = 24, padded = false)
        val notifyOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        option(card, R.drawable.ic_nav_alerts, getString(R.string.ob_notify_title),
            getString(if (notifyOk) R.string.ob_allowed else R.string.ob_notify_sub), done = notifyOk) {
            if (!notifyOk && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        card.addView(divider())
        option(card, R.drawable.ic_bolt, getString(R.string.ob_battery_title),
            getString(if (batteryOk) R.string.ob_allowed else R.string.ob_battery_sub), done = batteryOk) {
            if (!batteryOk) open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        c.addView(card)
        c.addView(text(getString(R.string.ob_finish_note), 13f, R.color.text_muted), full(top = 14).apply { marginStart = px(4) })
        c.addView(primary(getString(R.string.ob_start)) { complete() }, full(top = 24))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) show(FINISH, remember = false)
    }

    private fun complete() {
        prefs.onboarded = true
        if (prefs.isConfigured) {
            prefs.running = true
            LiveService.start(this, LiveService.ACTION_REFRESH)
        }
        OctopusWidget.updateAll(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // ================================================================= building blocks

    /** A fresh scrolling page; returns the column to add views to. */
    private fun page(): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(28))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (history.isNotEmpty()) {
            bar.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
                contentDescription = getString(R.string.ob_back)
                setPadding(px(10), px(10), px(10), px(10))
                background = getDrawable(R.drawable.bg_row)
                setOnClickListener { @Suppress("DEPRECATION") onBackPressed() }
            }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = -px(10) })
        }
        bar.addView(View(this), LinearLayout.LayoutParams(0, px(44), 1f))
        if (step != FINISH) {
            // Intro cards: skip to setup. Setup steps: skip setup, open the app (Settings has it all).
            bar.addView(TextView(this).apply {
                setText(R.string.ob_skip)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.text_secondary))
                setPadding(px(12), px(10), px(12), px(10))
                background = getDrawable(R.drawable.bg_row)
                setOnClickListener { if (step in INTRO) show(CHOOSE) else complete() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = -px(12) })
        }
        column.addView(bar)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.app_bg))
            fitsSystemWindows = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        return column
    }

    private fun header(c: LinearLayout, title: Int, body: Int) {
        c.addView(TextView(this, null, 0, R.style.PageTitle).apply { setText(title) }, wrap(top = 8))
        c.addView(text(getString(body), 15f, R.color.text_secondary), full(top = 8))
    }

    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.ic_bolt)
        setPadding(px(22), px(22), px(22), px(22))
        background = GradientDrawable().apply { cornerRadius = 24 * dp; setColor(getColor(R.color.brand_pink)) }
    }

    private fun card(top: Int = 0, padded: Boolean = true) =
        LinearLayout(this, null, 0, if (padded) R.style.Card else R.style.ListCard).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = full(top = top)
        }

    private fun iconBadge(icon: Int, done: Boolean = false, size: Int = 40) = ImageView(this).apply {
        setImageResource(if (done) R.drawable.ic_check else icon)
        imageTintList = ColorStateList.valueOf(getColor(if (done) R.color.elec_low else R.color.brand_pink))
        val pad = px(size * 9 / 40)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(if (done) R.color.elec_low else R.color.brand_pink) and 0x00FFFFFF or 0x1F000000)
        }
    }

    private fun option(card: LinearLayout, icon: Int, title: String, sub: String, tag: String? = null,
                       done: Boolean = false, onClick: () -> Unit) {
        val row = LinearLayout(this, null, 0, R.style.Row).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(76)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(iconBadge(icon, done), LinearLayout.LayoutParams(px(40), px(40)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this, null, 0, R.style.RowTitle).apply { text = title; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (tag != null) {
            titleRow.addView(TextView(this).apply {
                text = tag
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.brand_pink))
                setPadding(px(8), px(2), px(8), px(2))
                background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(getColor(R.color.brand_pink) and 0x00FFFFFF or 0x1F000000) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        }
        texts.addView(titleRow)
        texts.addView(TextView(this, null, 0, R.style.RowSubtitle).apply {
            text = sub
            if (done) setTextColor(getColor(R.color.elec_low))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14); marginEnd = px(8) })
        if (!done) row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_muted))
        }, LinearLayout.LayoutParams(px(20), px(20)))
        card.addView(row)
    }

    private fun switchRow(card: LinearLayout, title: String, sub: String, checked: Boolean, save: (Boolean) -> Unit) {
        val row = LinearLayout(this, null, 0, R.style.Row).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this, null, 0, R.style.RowTitle).apply { text = title })
        texts.addView(TextView(this, null, 0, R.style.RowSubtitle).apply { text = sub })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(12) })
        val sw = Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> save(on) } }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        card.addView(row)
    }

    private fun numbered(card: LinearLayout, n: Int, body: String, top: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = n.toString()
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.btn_primary_text))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.brand_pink)) }
        }, LinearLayout.LayoutParams(px(26), px(26)))
        row.addView(text(body, 15f, R.color.text_primary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
        card.addView(row, full(top = top))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.divider_subtle))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun text(s: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(getColor(colour))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    private fun label(s: String) = TextView(this, null, 0, R.style.FieldLabel).apply { text = s }

    private fun input(hint: String, type: Int) = EditText(this, null, 0, R.style.InputField).apply {
        this.hint = hint
        inputType = type
        maxLines = 1
        isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun primary(s: String, onClick: () -> Unit) = Button(this, null, 0, R.style.PrimaryButton).apply {
        text = s
        minHeight = px(52)
        setOnClickListener { onClick() }
    }

    private fun secondary(s: String, onClick: () -> Unit) = Button(this, null, 0, R.style.SecondaryButton).apply {
        text = s
        minHeight = px(48)
        setOnClickListener { onClick() }
    }

    private fun link(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(getColor(R.color.brand_pink))
        setPadding(px(8), px(8), px(8), px(8))
        setOnClickListener { onClick() }
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun px(v: Int) = (v * dp).toInt()

    private fun open(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.ob_cant_open, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        const val EXTRA_STEP = "step"
        private const val K_STEP = "step"
        private const val K_HISTORY = "history"
        private const val REQ_NOTIFY = 1
        const val WELCOME = 0
        const val CHOOSE = 1
        const val KEY = 2
        const val RELAY = 3
        const val MAKE_RELAY = 4
        const val ALERTS = 5
        const val FINISH = 6
        const val INTRO_WIDGET = 7
        const val INTRO_ALERTS = 8
        private val INTRO = listOf(WELCOME, INTRO_WIDGET, INTRO_ALERTS)
        const val DEPLOY_URL = "https://deploy.workers.cloudflare.com/?url=https://github.com/ruwaid-7/octopus-live/tree/main/worker"

        /** True when this install still needs the first-run setup. */
        fun needed(prefs: Prefs) = !prefs.onboarded && !prefs.isConfigured
    }
}
