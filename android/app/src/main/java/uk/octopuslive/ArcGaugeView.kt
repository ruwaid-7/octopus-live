package uk.octopuslive

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * The electricity speedometer on Home (and in the dial widget), drawn in a fixed 280 x 162 dp box:
 * a thin colour band (green to 0.5 kW, amber to 2 kW, red above), tick marks with kW numbers,
 * a tapered needle on a centre hub that swings to the live reading, and (dark theme) a soft glow
 * inside the arc in the colour of the current usage. The reading itself is shown below the dial.
 * Above 4 kW the dial grows to 8, 12, 16 kW…; the colours stay at 0.5 and 2 kW, and past the
 * 4 kW point the red fades into dark red towards the end of the dial.
 */
class ArcGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val green = context.getColor(R.color.elec_low)
    private val amber = context.getColor(R.color.elec_mid)
    private val red = context.getColor(R.color.elec_high)
    private val darkRed = context.getColor(R.color.elec_very_high)
    private val ink = context.getColor(R.color.text_primary)
    private val card = context.getColor(R.color.card_surface)

    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * dp; strokeCap = Paint.Cap.BUTT }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted)
        textSize = 10 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val needlePath = Path()

    private var shown = -1f      // 0..1 along the dial (animated); -1 = no reading
    private var fullW = BASE_W   // watts at the right-hand end of the dial
    private var watts: Int? = null
    private var animator: ValueAnimator? = null

    /** Live power in watts; null hides the needle. */
    fun setWatts(watts: Int?) {
        if (watts != this.watts) invalidate()   // the glow and hub follow the reading
        this.watts = watts
        val full = watts?.let { scaleFor(it, fullW) } ?: fullW
        if (full != fullW) {
            animator?.cancel()
            if (shown >= 0) shown = shown * fullW / full   // same reading, new scale: swing on from there
            fullW = full
            invalidate()
        }
        val target = watts?.let { (it / fullW).coerceIn(0f, 1f) } ?: -1f
        if (target == shown) return
        animator?.cancel()
        if (target < 0 || shown < 0 || !isLaidOut) { shown = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 650
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((W * dp).toInt(), (H * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = CX * dp
        val cy = CY * dp
        val r = RADIUS * dp

        // Glow inside the arc in the colour of the current usage (fades out before the box edges).
        // Dark theme only: on a light background it looks like a stain rather than a glow.
        val w = watts
        if (w != null && shown >= 0 && night) {
            val c = levelColour(w)
            val a = 0x5A
            val gy = cy - r * 0.5f
            val gr = r * 0.78f
            glow.shader = RadialGradient(cx, gy, gr,
                intArrayOf(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(a / 3, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, gy, gr, glow)
        }

        // Colour band. Canvas angles: 180° is the left end, 270° the top, 360° the right end.
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val g = 180f * GREEN_W / fullW
        val am = 180f * AMBER_W / fullW
        arc(canvas, 0f, g - 1.5f, green)
        arc(canvas, g + 1.5f, am - 1.5f, amber)
        if (fullW > BASE_W) {
            // Red up to 4 kW, then a fade to dark red at the end. SweepGradient runs clockwise from
            // 3 o'clock (0..1 = 0..360°); the dial is 180° (left) to 360° (right) on that circle.
            val at4kw = (180f + 180f * BASE_W / fullW) / 360f
            band.shader = SweepGradient(cx, cy, intArrayOf(red, red, darkRed, darkRed), floatArrayOf(0f, at4kw, 0.995f, 1f))
            arc(canvas, am + 1.5f, 180f, red)
            band.shader = null
        } else {
            arc(canvas, am + 1.5f, 180f, red)
        }

        // Ticks: 8 steps, the even ones longer and numbered (0-4 kW, 0-8 kW, ...)
        for (i in 0..TICKS) {
            val f = i / TICKS.toFloat()
            val major = i % 2 == 0
            val th = Math.PI * (1 - f)
            val c = cos(th).toFloat()
            val s = sin(th).toFloat()
            val r1 = r - (if (major) 20 else 15) * dp
            val r2 = r - 9 * dp
            tick.color = label.color
            tick.strokeWidth = (if (major) 2f else 1.3f) * dp
            tick.alpha = if (major) 255 else 150
            canvas.drawLine(cx + r1 * c, cy - r1 * s, cx + r2 * c, cy - r2 * s, tick)
            if (major) {
                val rl = r - 33 * dp
                canvas.drawText(kw(fullW * f), cx + rl * c, cy - rl * s + label.textSize * 0.35f, label)
            }
        }

        // Needle: tapered, with a short tail, on a round hub
        if (shown >= 0) {
            val th = Math.PI * (1 - shown)
            val c = cos(th).toFloat()
            val s = sin(th).toFloat()
            val len = r - 24 * dp
            val tail = 12 * dp
            val base = 2.5f * dp
            val tip = 1.1f * dp
            // (s, c) is at right angles to the needle's direction (c, -s)
            needlePath.reset()
            needlePath.moveTo(cx - tail * c + base * s, cy + tail * s + base * c)
            needlePath.lineTo(cx + len * c + tip * s, cy - len * s + tip * c)
            needlePath.lineTo(cx + len * c - tip * s, cy - len * s - tip * c)
            needlePath.lineTo(cx - tail * c - base * s, cy + tail * s - base * c)
            needlePath.close()
            canvas.drawPath(needlePath, needle)
        }
        hub.color = ink
        canvas.drawCircle(cx, cy, 9 * dp, hub)
        hub.color = w?.takeIf { shown >= 0 }?.let { levelColour(it) } ?: card
        canvas.drawCircle(cx, cy, 4 * dp, hub)
    }

    /** From..to are degrees along the dial, 0 = left end, 180 = right end. */
    private fun arc(canvas: Canvas, from: Float, to: Float, colour: Int) {
        if (to <= from) return
        band.color = colour
        canvas.drawArc(oval, 180f + from, to - from, false, band)
    }

    /** Same thresholds as the reading's colour; past 4 kW it deepens towards dark red. */
    private fun levelColour(w: Int): Int = when {
        w < GREEN_W -> green
        w < AMBER_W -> amber
        w <= BASE_W -> red
        else -> blend(red, darkRed, ((w - BASE_W) / (fullW - BASE_W)).coerceIn(0f, 1f))
    }

    private fun blend(c1: Int, c2: Int, k: Float) = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * k).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * k).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * k).toInt(),
    )

    private fun kw(w: Float): String {
        val v = w / 1000f
        return if (v == Math.round(v).toFloat()) String.format(Locale.UK, "%d", Math.round(v)) else String.format(Locale.UK, "%.1f", v)
    }

    companion object {
        const val BASE_W = 4000f
        private const val STEP_W = 4000f
        private const val GREEN_W = 500f
        private const val AMBER_W = 2000f
        private const val TICKS = 8
        /** Box and geometry in dp. */
        const val W = 280f
        const val H = 162f
        private const val CX = 140f
        private const val CY = 140f
        private const val RADIUS = 118f

        /**
         * Watts at the end of the dial for this reading: 4 kW, or the next 4 kW step above it.
         * Drops back only once well under the smaller scale, so it doesn't flick between the two.
         */
        fun scaleFor(watts: Int, current: Float = BASE_W): Float {
            val needed = maxOf(BASE_W, ceil(watts / STEP_W) * STEP_W)
            return if (needed < current && watts > needed * 0.9f) current else needed
        }
    }
}
