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
 * The gas card's flame. Off: a thin grey outline. On: an orange outline filled with orange
 * "gas" whose level rises and falls smoothly around the middle, with a gentle ripple on top.
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
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flame = Path()
    private val liquid = Path()
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

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height) / 24f
        val dx = (width - 24 * s) / 2f
        val dy = (height - 24 * s) / 2f
        m.setScale(s, s)
        m.postTranslate(dx, dy)
        scaled.reset()
        flame.transform(m, scaled)

        if (on) {
            val t = (System.nanoTime() - started) / 1e9
            // Level (share of the flame's height, from the bottom): drifts between about 40% and 62%
            val level = 0.51 + 0.09 * sin(t * 2 * Math.PI / 3.2) + 0.02 * sin(t * 2 * Math.PI / 1.3)
            val top = 2.2f; val bottom = 21.5f
            val surface = bottom - (bottom - top) * level.toFloat()
            // A gentle ripple across the surface
            liquid.reset()
            liquid.moveTo(0f, 24f)
            liquid.lineTo(0f, surface)
            var x = 0f
            while (x <= 24f) {
                val y = surface + 0.45f * sin(x * 0.55 + t * 3.1).toFloat() + 0.2f * sin(x * 1.3 - t * 2.3).toFloat()
                liquid.lineTo(x, y)
                x += 0.5f
            }
            liquid.lineTo(24f, 24f)
            liquid.close()
            liquid.transform(m)

            canvas.save()
            canvas.clipPath(scaled)
            fill.color = gas
            fill.alpha = 230
            canvas.drawPath(liquid, fill)
            canvas.restore()
        }

        outline.color = if (on) gas else muted
        outline.strokeWidth = 1.5f * s
        canvas.drawPath(scaled, outline)

        if (on && running) postInvalidateOnAnimation()
    }
}
