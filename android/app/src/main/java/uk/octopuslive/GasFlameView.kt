package uk.octopuslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * The gas card's flame. Off: a thin grey outline. On: an orange outline with flickering
 * flame tongues inside (orange, with an amber core) that dance around the middle height.
 * Animates only while [running] is true (gas on and Home on screen).
 */
class GasFlameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** True while the flame should move (gas on, Home visible). */
    var running = false
        set(v) {
            if (field == v) return
            field = v
            if (v) { started = System.nanoTime(); postInvalidateOnAnimation() }
        }

    private val dp = resources.displayMetrics.density
    private val gas = context.getColor(R.color.gas_accent)
    private val muted = context.getColor(R.color.text_muted)
    private val core = context.getColor(R.color.elec_mid)   // amber: the hot centre
    private val tongue = Path()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flame = Path()
    private val scaled = Path()
    private val m = Matrix()
    private var started = System.nanoTime()

    init {
        // The flame outline (24 x 24 design units), as in the gas-off icon
        flame.moveTo(12.0f, 21.5f)
        flame.cubicTo(8.0f, 21.5f, 5.5f, 18.4f, 5.5f, 14.2f)
        flame.cubicTo(5.5f, 9.6f, 9.8f, 5.8f, 12.2f, 2.2f)
        flame.cubicTo(13.0f, 5.2f, 13.8f, 6.8f, 13.4f, 8.6f)
        flame.cubicTo(14.8f, 7.5f, 15.6f, 6.1f, 15.8f, 4.8f)
        flame.cubicTo(17.6f, 7.6f, 18.5f, 10.9f, 18.5f, 14.2f)
        flame.cubicTo(18.5f, 18.4f, 16.0f, 21.5f, 12.0f, 21.5f)
        flame.close()
    }

    /**
     * One flame tongue: base [halfWidth] each side of [cx] at the bottom of the outline, a pointed
     * tip at about [height] of the outline's height that flickers (uneven, quick changes) and sways.
     */
    private fun tongue(canvas: Canvas, cx: Float, halfWidth: Float, height: Float, t: Float, seed: Float) {
        val bottom = 21.5f; val top = 2.2f
        val flicker = 0.07f * sin(t * 9.1f + seed) + 0.05f * sin(t * 13.7f + seed * 2) + 0.04f * sin(t * 5.3f + seed * 3)
        val tipY = bottom - (bottom - top) * (height + flicker)
        val sway = 0.6f * sin(t * 3.7f + seed) + 0.3f * sin(t * 7.9f + seed * 1.5f)
        val tipX = cx + sway
        val shoulder = tipY + (bottom - tipY) * 0.45f
        tongue.reset()
        tongue.moveTo(cx - halfWidth, bottom + 1f)
        tongue.cubicTo(cx - halfWidth * 1.05f, shoulder, tipX - halfWidth * 0.25f, tipY + (bottom - tipY) * 0.25f, tipX, tipY)
        tongue.cubicTo(tipX + halfWidth * 0.25f, tipY + (bottom - tipY) * 0.25f, cx + halfWidth * 1.05f, shoulder, cx + halfWidth, bottom + 1f)
        tongue.close()
        tongue.transform(m)
        canvas.drawPath(tongue, fill)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        val dx = (width - 24 * s) / 2f
        val dy = (height - 24 * s) / 2f
        m.setScale(s, s)
        m.postTranslate(dx, dy)
        scaled.reset()
        flame.transform(m, scaled)

        if (on) {
            val t = ((System.nanoTime() - started) / 1e9).toFloat()
            canvas.save()
            canvas.clipPath(scaled)
            // Three flickering tongues of flame rising from the base (tallest in the middle),
            // then a lighter, hotter core in front. Heights hover around the middle of the outline.
            fill.color = gas
            fill.alpha = 235
            tongue(canvas, 9.6f, 3.4f, 0.40f, t, 0.0f)
            tongue(canvas, 14.4f, 3.4f, 0.44f, t, 1.7f)
            tongue(canvas, 12.0f, 5.2f, 0.60f, t, 3.1f)
            fill.color = core
            fill.alpha = 215
            tongue(canvas, 12.0f, 2.6f, 0.34f, t, 4.4f)
            canvas.restore()
        }

        outline.color = if (on) gas else muted
        outline.strokeWidth = 1.5f * s
        canvas.drawPath(scaled, outline)

        if (on && running) postInvalidateOnAnimation()
    }
}
