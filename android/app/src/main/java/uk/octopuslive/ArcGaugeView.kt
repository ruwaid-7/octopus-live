package uk.octopuslive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 180° electricity dial on Home (0-4 kW), drawn in a fixed 264 x 138 dp box:
 * three flat segments (green to 0.5 kW, amber to 2 kW, red above) with small gaps,
 * scale labels, and a round marker that glides to the live reading.
 * Geometry from the design: centre (132, 118), radius 96, stroke 10.
 */
class ArcGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * dp; strokeCap = Paint.Cap.BUTT }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.text_primary) }
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = context.getColor(R.color.card_surface)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        textSize = 10 * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val green = context.getColor(R.color.elec_low)
    private val amber = context.getColor(R.color.elec_mid)
    private val red = context.getColor(R.color.elec_high)
    private val oval = RectF()

    private var shown = -1f   // 0..1 along the dial (animated); -1 = no reading
    private var animator: ValueAnimator? = null

    /** Live power in watts; null hides the marker. */
    fun setWatts(watts: Int?) {
        val target = watts?.let { (it / FULL_W).coerceIn(0f, 1f) } ?: -1f
        if (target == shown) return
        animator?.cancel()
        if (target < 0 || shown < 0 || !isLaidOut) { shown = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((W * dp).toInt(), (H * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = 132 * dp
        val cy = 118 * dp
        val r = 96 * dp
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // Canvas angles: 180° is the left end, 270° the top, 360° the right end
        arc(canvas, 0f, 22.5f, green)
        arc(canvas, 26.5f, 88f, amber)
        arc(canvas, 92f, 180f, red)

        // Scale labels at the design's positions
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("0", 20 * dp, 122 * dp, label)
        canvas.drawText("0.5", 30 * dp, 69 * dp, label)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText("2 kW", 132 * dp, 12 * dp, label)
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("4 kW", 242 * dp, 122 * dp, label)

        // Marker: 8dp circle with a 3dp card-coloured ring
        if (shown >= 0) {
            val theta = Math.PI * (1 - shown)
            val x = cx + (r * cos(theta)).toFloat()
            val y = cy - (r * sin(theta)).toFloat()
            canvas.drawCircle(x, y, 8 * dp, thumbFill)
            canvas.drawCircle(x, y, 8 * dp - 1.5f * dp, thumbRing)
        }
    }

    /** From..to are degrees along the dial, 0 = left end, 180 = right end. */
    private fun arc(canvas: Canvas, from: Float, to: Float, colour: Int) {
        seg.color = colour
        canvas.drawArc(oval, 180f + from, to - from, false, seg)
    }

    companion object {
        const val FULL_W = 4000f
        private const val W = 264f
        private const val H = 138f
    }
}
