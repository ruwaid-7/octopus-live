package uk.octopuslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * A small bar chart drawn by hand (the app has no libraries).
 * [series]: one or more lists of values drawn side by side per position; [labels]: optional
 * text under each position (blank = none).
 */
class BarChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var series: List<List<Double>> = emptyList()
    private var colors: List<Int> = emptyList()
    private var labels: List<String> = emptyList()
    private var unit: String = "kWh"
    private var highlightIndex: Int = -1
    private var emptyText: String = "No data yet"

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10 * density
        color = context.getColor(R.color.text_muted)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.divider_subtle)
        strokeWidth = 1 * density
    }
    private val rect = RectF()

    fun setData(series: List<List<Double>>, colors: List<Int>, labels: List<String>, unit: String = "kWh",
                highlightIndex: Int = -1, emptyText: String = "No data yet") {
        this.series = series
        this.colors = colors
        this.labels = labels
        this.unit = unit
        this.highlightIndex = highlightIndex
        this.emptyText = emptyText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = series.maxOfOrNull { it.size } ?: 0
        val max = series.flatten().maxOrNull() ?: 0.0
        if (n == 0 || max <= 0.0) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, text)
            return
        }
        val top = 14 * density
        val labelH = 14 * density
        val chartBottom = height - labelH
        val chartH = chartBottom - top
        val groupW = width.toFloat() / n
        val gap = groupW * 0.18f
        val barW = (groupW - gap) / series.size
        val radius = minOf(barW / 2, 3 * density)

        canvas.drawLine(0f, chartBottom, width.toFloat(), chartBottom, grid)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(String.format(Locale.UK, "max %.2f %s", max, unit), 0f, 10 * density, text)

        for (i in 0 until n) {
            for ((s, values) in series.withIndex()) {
                val v = values.getOrNull(i) ?: continue
                if (v <= 0) continue
                val h = (v / max * chartH).toFloat().coerceAtLeast(1.5f * density)
                val left = i * groupW + gap / 2 + s * barW
                rect.set(left, chartBottom - h, left + barW * 0.92f, chartBottom)
                bar.color = colors.getOrElse(s) { colors.firstOrNull() ?: 0xFF888888.toInt() }
                bar.alpha = if (highlightIndex >= 0 && i != highlightIndex) 150 else 255
                canvas.drawRoundRect(rect, radius, radius, bar)
            }
            val label = labels.getOrNull(i)
            if (!label.isNullOrBlank()) {
                // Centred under its bar, but kept inside the view so edge labels aren't cut off
                text.textAlign = Paint.Align.CENTER
                val half = text.measureText(label) / 2
                val mid = i * groupW + groupW / 2
                val x = if (width > 2 * half) mid.coerceIn(half, width - half) else mid
                canvas.drawText(label, x, height - 2 * density, text)
            }
        }
    }
}
