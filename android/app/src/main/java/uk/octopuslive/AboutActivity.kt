package uk.octopuslive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * About the app (version, links, legal) and, with EXTRA_DOC, one of the documents in res/raw:
 * the privacy policy, terms of use or open-source licences.
 * The documents use a tiny Markdown subset: "# " title, "## " heading, "- " bullet, blank line between paragraphs.
 */
class AboutActivity : Activity() {

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(32))
        }
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_primary))
            contentDescription = getString(R.string.ob_back)
            setPadding(px(10), px(10), px(10), px(10))
            background = getDrawable(R.drawable.bg_row)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(px(44), px(44)).apply { marginStart = -px(10) })

        when (val doc = intent.getStringExtra(EXTRA_DOC)) {
            null -> about(column)
            else -> document(column, doc)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true
            setBackgroundColor(getColor(R.color.app_bg))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    // ================================================================= about

    private fun about(c: LinearLayout) {
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (e: Exception) { "" }

        c.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_bolt)
            setPadding(px(14), px(14), px(14), px(14))
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(getColor(R.color.brand_pink)) }
        }, LinearLayout.LayoutParams(px(56), px(56)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(8) })
        c.addView(TextView(this, null, 0, R.style.PageTitle).apply { text = getString(R.string.app_name); gravity = Gravity.CENTER }, full(top = 14))
        c.addView(text(getString(R.string.about_version, version), 14f, R.color.text_secondary).apply { gravity = Gravity.CENTER }, full(top = 2))
        c.addView(text(getString(R.string.about_summary), 15f, R.color.text_secondary).apply { gravity = Gravity.CENTER }, full(top = 14))

        section(c, R.string.about_section_app)
        list(c,
            Row(R.string.about_whats_new, R.string.about_whats_new_sub) { open("$REPO/releases") },
            Row(R.string.about_source, R.string.about_source_sub) { open(REPO) },
            Row(R.string.about_report, R.string.about_report_sub) { open("$REPO/issues/new") },
        )

        section(c, R.string.about_section_legal)
        list(c,
            Row(R.string.about_privacy, R.string.about_privacy_sub) { start(this, DOC_PRIVACY) },
            Row(R.string.about_terms, R.string.about_terms_sub) { start(this, DOC_TERMS) },
            Row(R.string.about_licences, R.string.about_licences_sub) { start(this, DOC_LICENCES) },
        )

        // Disclaimer: there if you look for it, folded away otherwise
        val card = LinearLayout(this, null, 0, R.style.ListCard).apply { orientation = LinearLayout.VERTICAL }
        val body = text(getString(R.string.about_disclaimer_body), 14f, R.color.text_secondary).apply {
            setPadding(px(16), 0, px(16), px(16))
            visibility = View.GONE
        }
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(getColor(R.color.text_muted))
            rotation = 90f
        }
        val header = LinearLayout(this, null, 0, R.style.Row).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.rotation = if (open) -90f else 90f
            }
        }
        header.addView(TextView(this, null, 0, R.style.RowTitle).apply { setText(R.string.about_disclaimer) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(chevron, LinearLayout.LayoutParams(px(20), px(20)))
        card.addView(header)
        card.addView(body)
        c.addView(card, full(top = 12))

        c.addView(text(getString(R.string.about_footer), 12f, R.color.text_muted).apply { gravity = Gravity.CENTER }, full(top = 20))
    }

    private class Row(val title: Int, val sub: Int, val onClick: () -> Unit)

    private fun list(c: LinearLayout, vararg rows: Row) {
        val card = LinearLayout(this, null, 0, R.style.ListCard).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider_subtle)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = px(16) })
            val row = LinearLayout(this, null, 0, R.style.Row).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { r.onClick() }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this, null, 0, R.style.RowTitle).apply { setText(r.title) })
            texts.addView(TextView(this, null, 0, R.style.RowSubtitle).apply { setText(r.sub) })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(12) })
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_muted))
            }, LinearLayout.LayoutParams(px(20), px(20)))
            card.addView(row)
        }
        c.addView(card)
    }

    private fun section(c: LinearLayout, title: Int) {
        c.addView(TextView(this, null, 0, R.style.SectionTitle).apply { setText(title) },
            full(top = 24).apply { bottomMargin = px(8) })
    }

    // ================================================================= documents

    private fun document(c: LinearLayout, doc: String) {
        val res = when (doc) {
            DOC_PRIVACY -> R.raw.privacy
            DOC_TERMS -> R.raw.terms
            else -> R.raw.licences
        }
        val lines = resources.openRawResource(res).bufferedReader().use { it.readText() }.lines()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) c.addView(linked(para.toString().trim(), 15f, R.color.text_primary), full(top = 10))
            para.clear()
        }
        for (line in lines) {
            when {
                line.startsWith("# ") -> { flush(); c.addView(TextView(this, null, 0, R.style.PageTitle).apply { text = line.drop(2) }, full(top = 8)) }
                line.startsWith("## ") -> { flush(); c.addView(text(line.drop(3), 17f, R.color.text_primary, medium = true), full(top = 22)) }
                line.startsWith("- ") -> {
                    flush()
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(text("•", 15f, R.color.text_secondary), LinearLayout.LayoutParams(px(16), ViewGroup.LayoutParams.WRAP_CONTENT))
                    row.addView(linked(line.drop(2), 15f, R.color.text_primary), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    c.addView(row, full(top = 8))
                }
                line.startsWith("Last updated") -> { flush(); c.addView(text(line, 13f, R.color.text_muted), full(top = 6)) }
                line.isBlank() -> flush()
                else -> para.append(line).append(' ')
            }
        }
        flush()
    }

    // ================================================================= helpers

    private fun text(s: String, size: Float, colour: Int, medium: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(getColor(colour))
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.2f)
    }

    private fun linked(s: String, size: Float, colour: Int) = text(s, size, colour).apply {
        Linkify.addLinks(this, Linkify.WEB_URLS)
        setLinkTextColor(getColor(R.color.brand_pink))
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun full(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) }
    private fun px(v: Int) = (v * dp).toInt()

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
    }

    companion object {
        const val EXTRA_DOC = "doc"
        const val DOC_PRIVACY = "privacy"
        const val DOC_TERMS = "terms"
        const val DOC_LICENCES = "licences"
        private const val REPO = "https://github.com/ruwaid-7/octopus-live"

        /** Opens the About screen, or one of its documents. */
        fun start(context: Context, doc: String? = null) {
            context.startActivity(Intent(context, AboutActivity::class.java).apply { if (doc != null) putExtra(EXTRA_DOC, doc) })
        }
    }
}
