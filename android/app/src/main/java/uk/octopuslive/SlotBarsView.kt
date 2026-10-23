package uk.octopuslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Today's electricity on Home: 48 half-hour bars from midnight, each coloured by its average power
 * (green under 0.5 kW, amber under 2 kW, red above). Half-hours still to come are short grey stubs.
 * Time labels 00:00, 06:00, 12:00, 18:00, 24:00 underneath.
 */
class SlotBarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = context.getColor(R.color.divider_subtle); strokeWidth = 1 * dp }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted); textSize = 10 * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val low = context.getColor(R.color.elec_low)
    private val mid = context.getColor(R.color.elec_mid)
    private val high = context.getColor(R.color.elec_high)
    private val future = context.getColor(R.color.divider_subtle)
    private val rect = RectF()
    private var kwh: List<Double> = emptyList()

    /** kWh per half-hour since midnight (today's slots so far). */
    fun setSlots(slots: List<Double>) {
        if (slots == kwh) return
        kwh = slots
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (46 + 3 + 1 + 4 + 14).times(dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val gap = 2 * dp
        val barW = (w - gap * 47) / 48
        val chartH = 46 * dp
        val base = chartH            // bars sit on this line; 4dp padding then the divider
        val peakKw = (kwh.maxOrNull() ?: 0.0) * 2
        val scaleKw = maxOf(3.4, peakKw)   // as in the design: 3.4 kW fills the chart unless the peak is higher
        for (i in 0 until 48) {
            val x = i * (barW + gap)
            val kw = kwh.getOrNull(i)?.times(2)   // kWh in half an hour -> average kW
            val h: Float
            if (kw == null) {
                bar.color = future
                h = chartH * 0.10f
            } else {
                bar.color = if (kw < 0.5) low else if (kw < 2.0) mid else high
                h = (chartH * (kw / scaleKw).toFloat()).coerceIn(chartH * 0.10f, chartH)
            }
            rect.set(x, base - h, x + barW, base)
            val r = 1.5f * dp
            canvas.drawRoundRect(rect, r, r, bar)
            canvas.drawRect(x, base - minOf(h, r), x + barW, base, bar)   // square bottom corners
        }
        val lineY = chartH + 3 * dp
        canvas.drawLine(0f, lineY, w, lineY, line)
        val y = lineY + 4 * dp - axis.ascent()
        val labels = listOf("00:00", "06:00", "12:00", "18:00", "24:00")
        labels.forEachIndexed { i, s ->
            axis.textAlign = when (i) { 0 -> Paint.Align.LEFT; labels.size - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(s, w * i / (labels.size - 1), y, axis)
        }
    }
}
