package uk.octopuslive

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Dev build only: type in any values (or pick a scenario) and the whole app - Home, Usage,
 * Settings, the widget, the tile and the notifications - shows them instead of real data.
 */
class DevActivity : Activity() {

    private lateinit var prefs: Prefs
    private val dp by lazy { resources.displayMetrics.density }
    private val fields = mutableMapOf<String, EditText>()
    private lateinit var useTest: Switch
    private lateinit var hasGas: Switch

    /** Key, label, default, decimal? */
    private val numberFields = listOf(
        Triple(DevData.WATTS, "Electricity now (W)", "420"),
        Triple(DevData.ELEC_KWH, "Electricity today (kWh)", "6.2"),
        Triple(DevData.ELEC_COST, "Electricity cost today (£, -1 = unknown)", "1.94"),
        Triple(DevData.GAS_KW, "Gas 30-min average (kW)", "8.4"),
        Triple(DevData.GAS_ON_MIN, "Gas on for (minutes, 0 = off)", "0"),
        Triple(DevData.GAS_KWH, "Gas today (kWh)", "18.4"),
        Triple(DevData.GAS_COST, "Gas cost today (£, -1 = unknown)", "1.32"),
        Triple(DevData.PRICE, "Price now (p/kWh, -1 = unknown)", "25.6"),
        Triple(DevData.NEXT_PRICE, "Next price (p/kWh, -1 = none)", "-1"),
        Triple(DevData.NEXT_IN_MIN, "Next price starts in (minutes)", "45"),
        Triple(DevData.BASE_W, "Always-on (W)", "120"),
        Triple(DevData.AGE_MIN, "Reading age (minutes, 30+ = delayed)", "0"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(32))
        }

        // Header
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            setPadding(px(8), px(8), px(8), px(8))
            background = getDrawable(R.drawable.bg_row)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(40), px(40)).apply { marginStart = -px(8); marginEnd = px(4) })
        top.addView(TextView(this, null, 0, R.style.TabHeader).apply { text = "Test data" },
            LinearLayout.LayoutParams(0, px(54), 1f))
        c.addView(top)

        // On / off
        val main = card(c, null)
        useTest = switchRow(main, "Use test data", "Show these values instead of real data from Octopus", prefs.devMode) { on ->
            prefs.devMode = on
            refresh()
        }

        // Scenarios
        val sc = card(c, "Scenarios")
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(
            listOf("Quiet night" to ::quietNight, "Evening peak" to ::eveningPeak),
            listOf("Gas on" to ::gasOn, "No gas meter" to ::noGas),
            listOf("Delayed reading" to ::delayed, "Update failed" to ::failed),
            listOf("Cheaper next" to ::cheaperNext, "No API key costs" to ::noCosts),
        ).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, fn) ->
                row.addView(button(label, primary = false) { fn(); load(); apply() },
                    LinearLayout.LayoutParams(0, px(44), 1f).apply { if (i == 1) marginStart = px(8) })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        }
        sc.addView(grid)

        // Values
        val v = card(c, "Values")
        hasGas = switchRow(v, "Has a gas meter", null, prefs.str(DevData.HAS_GAS) != "0") { }
        numberFields.forEach { (key, label, _) ->
            v.addView(TextView(this, null, 0, R.style.FieldLabel).apply { text = label },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
            val e = EditText(this, null, 0, R.style.InputField).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            fields[key] = e
            v.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }
        listOf(DevData.ERROR to "Error message (empty = working)", DevData.TARIFF to "Tariff code").forEach { (key, label) ->
            v.addView(TextView(this, null, 0, R.style.FieldLabel).apply { text = label },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
            val e = EditText(this, null, 0, R.style.InputField).apply { inputType = InputType.TYPE_CLASS_TEXT }
            fields[key] = e
            v.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }
        v.addView(button("Apply", primary = true) { apply() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50)).apply { topMargin = px(16) })

        // History, notifications, other screens
        val more = card(c, "History and notifications")
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        h.addView(button("Fill 1 month", false) { DevData.fillHistory(this, 31); apply() }, LinearLayout.LayoutParams(0, px(44), 1f))
        h.addView(button("Fill 3 months", false) { DevData.fillHistory(this, Insights.HISTORY_DAYS); apply() },
            LinearLayout.LayoutParams(0, px(44), 1f).apply { marginStart = px(8) })
        more.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        more.addView(TextView(this, null, 0, R.style.FieldLabel).apply { text = "Send a notification now" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16) })
        listOf(listOf("Gas on" to "gas", "High use" to "high", "Offline" to "offline"),
               listOf("Cheap price" to "cheap", "Daily summary" to "summary")).forEach { group ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEachIndexed { i, (label, kind) ->
                row.addView(button(label, false) { sendAlert(kind) },
                    LinearLayout.LayoutParams(0, px(44), 1f).apply { if (i > 0) marginStart = px(8) })
            }
            more.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        }
        more.addView(button("Clear recent alerts", false) {
            prefs.setStr("alertLog", null); toast("Cleared")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply { topMargin = px(8) })
        more.addView(button("Open the first-run setup", false) {
            startActivity(Intent(this, SetupActivity::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)).apply { topMargin = px(8) })

        load()
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.app_bg))
            addView(c)
        })
    }

    // ---------------------------------------------------------------- values

    private fun load() {
        numberFields.forEach { (key, _, def) -> fields[key]?.setText(prefs.str(key) ?: def) }
        fields[DevData.ERROR]?.setText(prefs.str(DevData.ERROR).orEmpty())
        fields[DevData.TARIFF]?.setText(prefs.str(DevData.TARIFF) ?: "E-1R-VAR-22-11-01-M")
        hasGas.isChecked = prefs.str(DevData.HAS_GAS) != "0"
    }

    private fun apply() {
        fields.forEach { (key, e) -> prefs.setStr(key, e.text.toString().trim()) }
        prefs.setStr(DevData.HAS_GAS, if (hasGas.isChecked) "1" else "0")
        if (!prefs.devMode) { prefs.devMode = true; useTest.isChecked = true }
        refresh()
        toast("Applied")
    }

    private fun refresh() {
        prefs.running = true
        LiveService.start(this, LiveService.ACTION_REFRESH)
    }

    private fun sendAlert(kind: String) {
        if (!prefs.devMode) { toast("Turn on “Use test data” first"); return }
        startForegroundService(Intent(this, LiveService::class.java).setAction(LiveService.ACTION_DEV_ALERT).putExtra("kind", kind))
    }

    private fun set(vararg pairs: Pair<String, String>) {
        load()
        pairs.forEach { (k, v) -> if (k == DevData.HAS_GAS) hasGas.isChecked = v == "1" else fields[k]?.setText(v) }
        fields.forEach { (key, e) -> prefs.setStr(key, e.text.toString().trim()) }
        prefs.setStr(DevData.HAS_GAS, if (hasGas.isChecked) "1" else "0")
    }

    // Scenarios: only the values that matter change; the rest stay as typed
    private fun quietNight() = set(DevData.WATTS to "36", DevData.GAS_ON_MIN to "0", DevData.AGE_MIN to "0", DevData.ERROR to "", DevData.HAS_GAS to "1")
    private fun eveningPeak() = set(DevData.WATTS to "3420", DevData.ELEC_KWH to "9.8", DevData.ELEC_COST to "3.12", DevData.AGE_MIN to "0", DevData.ERROR to "")
    private fun gasOn() = set(DevData.HAS_GAS to "1", DevData.GAS_ON_MIN to "130", DevData.GAS_KW to "8.4", DevData.ERROR to "")
    private fun noGas() = set(DevData.HAS_GAS to "0", DevData.GAS_ON_MIN to "0")
    private fun delayed() = set(DevData.AGE_MIN to "45", DevData.ERROR to "")
    private fun failed() = set(DevData.ERROR to "No internet connection. It will update when you're back online.")
    private fun cheaperNext() = set(DevData.PRICE to "28.4", DevData.NEXT_PRICE to "4.2", DevData.NEXT_IN_MIN to "35")
    private fun noCosts() = set(DevData.ELEC_COST to "-1", DevData.GAS_COST to "-1", DevData.PRICE to "-1", DevData.NEXT_PRICE to "-1")

    // ---------------------------------------------------------------- building blocks

    private fun card(parent: LinearLayout, title: String?): LinearLayout {
        val card = LinearLayout(this, null, 0, R.style.HCard).apply { orientation = LinearLayout.VERTICAL }
        if (title != null) card.addView(TextView(this, null, 0, R.style.Eyebrow).apply { text = title })
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
        return card
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = px(52) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(getColor(R.color.text_primary)) })
        if (sub != null) texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(getColor(R.color.text_muted)) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(12) })
        val sw = Switch(this, null, 0, R.style.PinkSwitch).apply { isChecked = checked; setOnCheckedChangeListener { _, on -> onChange(on) } }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) =
        Button(this, null, 0, if (primary) R.style.PrimaryButton else R.style.SecondaryButton).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { onClick() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun px(v: Int) = (v * dp).toInt()
}
