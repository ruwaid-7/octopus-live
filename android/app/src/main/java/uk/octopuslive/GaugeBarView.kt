package uk.octopuslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Live electricity gauge on Home: a segmented 0-4 kW track (green to 0.5 kW, amber to 2 kW, red above),
 * a marker thumb at the current power, and tick labels underneath. The thumb glides to new values.
 */
class GaugeBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary) }
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = context.getColor(R.color.card_surface)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted); textSize = 11 * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val colours = intArrayOf(context.getColor(R.color.elec_low), context.getColor(R.color.elec_mid), context.getColor(R.color.elec_high))
    private val rect = RectF()

    private var shown = -1f   // current thumb position 0..1 (animated); -1 = no reading
    private var animator: android.animation.ValueAnimator? = null

    /** Sets the live power in watts (null = no reading: the thumb is hidden). */
    fun setWatts(watts: Int?) {
        val target = watts?.let { (it / FULL_W).coerceIn(0f, 1f) } ?: -1f
        animator?.cancel()
        if (target < 0 || shown < 0 || !isLaidOut) { shown = target; invalidate(); return }
        animator = android.animation.ValueAnimator.ofFloat(shown, target).apply {
            duration = 400
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (36 * dp).toInt())   // 16 track + 4 gap + 16 ticks
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val inset = 5 * dp            // room for the thumb at both ends
        val trackW = w - 2 * inset
        val gap = 3 * dp
        val top = 4 * dp
        val bottom = top + 8 * dp
        val r = 4 * dp
        // Segments 12.5% : 37.5% : 50% of 0-4 kW
        val edges = floatArrayOf(0f, 0.125f, 0.5f, 1f)
        for (i in 0 until 3) {
            val l = inset + trackW * edges[i] + if (i > 0) gap / 2 else 0f
            val rr = inset + trackW * edges[i + 1] - if (i < 2) gap / 2 else 0f
            seg.color = colours[i]
            rect.set(l, top, rr, bottom)
            // Round only the outer ends
            canvas.drawRoundRect(rect, if (i == 1) 0f else r, if (i == 1) 0f else r, seg)
            if (i == 0) canvas.drawRect(rect.right - r, top, rect.right, bottom, seg)
            if (i == 2) canvas.drawRect(rect.left, top, rect.left + r, bottom, seg)
        }
        // Thumb 10 x 16, radius 4, 2dp card-coloured outline
        if (shown >= 0) {
            val cx = inset + trackW * shown
            rect.set(cx - 5 * dp, 0f, cx + 5 * dp, 16 * dp)
            canvas.drawRoundRect(rect, r, r, thumbFill)
            rect.inset(1 * dp, 1 * dp)
            canvas.drawRoundRect(rect, r, r, thumbStroke)
        }
        // Ticks: 0, 0.5 kW, 2 kW, 4 kW
        val y = 20 * dp - tick.ascent()
        tick.textAlign = Paint.Align.LEFT
        canvas.drawText("0", inset, y, tick)
        tick.textAlign = Paint.Align.CENTER
        canvas.drawText("0.5 kW", inset + trackW * 0.125f + 8 * dp, y, tick)
        canvas.drawText("2 kW", inset + trackW * 0.5f, y, tick)
        tick.textAlign = Paint.Align.RIGHT
        canvas.drawText("4 kW", inset + trackW, y, tick)
    }

    companion object {
        const val FULL_W = 4000f
    }
}
