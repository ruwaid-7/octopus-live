package uk.octopuslive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The gas card's flame. Off: a thin grey outline. On: an orange outline with fire inside.
 *
 * The fire is the standard real-time technique: value noise scrolling upward, multiplied by a
 * height falloff that's tallest in the middle, then a colour ramp (pale yellow base -> amber ->
 * orange tips). It's worked out in a small 48 x 48 image about 30 times a second and drawn
 * smoothed and clipped to the outline, so it looks the same on every phone.
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
            if (v) postInvalidateOnAnimation()
        }

    private val gas = context.getColor(R.color.gas_accent)
    private val muted = context.getColor(R.color.text_muted)
    private val amber = context.getColor(R.color.elec_mid)
    private val pale = 0xFFFFE08A.toInt()

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val firePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flame = Path()
    private val scaled = Path()
    private val m = Matrix()
    private val start = System.nanoTime()

    private val n = 48
    private val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(n * n)
    private var lastFrame = 0L
    private val src = Rect(0, 0, n, n)
    private val dst = RectF()

    init {
        // The flame outline (24 x 24 design units)
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
            val now = System.nanoTime()
            if (now - lastFrame > 30_000_000L || lastFrame == 0L) {   // ~30 frames a second
                lastFrame = now
                renderFire(((now - start) / 1e9 % 1000.0).toFloat())
            }
            canvas.save()
            canvas.clipPath(scaled)
            dst.set(dx, dy, dx + 24 * s, dy + 24 * s)
            canvas.drawBitmap(bitmap, src, dst, firePaint)
            canvas.restore()
        }

        outline.color = if (on) gas else muted
        outline.strokeWidth = 1.5f * s
        canvas.drawPath(scaled, outline)

        if (on && running) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- the fire

    private fun renderFire(t: Float) {
        val sway = 0.05f * sin(t * 2.1f) + 0.03f * sin(t * 5.3f)
        val lift = 0.04f * sin(t * 7.7f)
        for (py in 0 until n) {
            val y = 1f - (py + 0.5f) / n
            val fy = ((y - 0.10f) / 0.80f).coerceIn(0f, 1f)
            for (px in 0 until n) {
                val x = (px + 0.5f) / n - 0.5f
                val xs = x + sway * fy
                // Tall in the middle, short at the sides
                val h = 0.66f * (1f - min(abs(xs) * 2.3f, 1f).pow(1.6f)) + lift
                // Noise stretched upward and scrolling up: licking tongues
                val nz = 0.6f * fbm(xs * 7f, fy * 2.2f - t * 2.2f) + 0.4f * fbm(xs * 14f + 5f, fy * 4.5f - t * 3.6f)
                val f = 1f - fy / max(h, 0.05f) - (nz - 0.45f) * (0.35f + fy * 1.6f)
                val a = (f / 0.08f).coerceIn(0f, 1f)
                pixels[py * n + px] = if (a <= 0f) 0 else {
                    val k1 = ((f - 0.12f) / 0.25f).coerceIn(0f, 1f)
                    val k2 = ((f - 0.42f) / 0.25f).coerceIn(0f, 1f)
                    val c = blend(blend(gas, amber, k1), pale, k2)
                    ((a * 255).toInt() shl 24) or (c and 0xFFFFFF)
                }
            }
        }
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
    }

    private fun fbm(x0: Float, y0: Float): Float {
        var x = x0; var y = y0
        var v = 0f; var amp = 0.5f
        repeat(4) {
            v += amp * noise(x, y)
            x = x * 2.03f + 1.7f
            y = y * 2.03f + 9.2f
            amp *= 0.5f
        }
        return v
    }

    private fun noise(x: Float, y: Float): Float {
        val ix = floor(x); val iy = floor(y)
        val fx = x - ix; val fy = y - iy
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val i = ix.toInt(); val j = iy.toInt()
        val a = hash(i, j); val b = hash(i + 1, j); val c = hash(i, j + 1); val d = hash(i + 1, j + 1)
        return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy
    }

    /** A fast, repeatable pseudo-random value in 0..1 for a grid point. */
    private fun hash(i: Int, j: Int): Float {
        var h = i * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun blend(c1: Int, c2: Int, k: Float): Int {
        val r = ((c1 shr 16 and 255) + ((c2 shr 16 and 255) - (c1 shr 16 and 255)) * k).toInt()
        val g = ((c1 shr 8 and 255) + ((c2 shr 8 and 255) - (c1 shr 8 and 255)) * k).toInt()
        val b = ((c1 and 255) + ((c2 and 255) - (c1 and 255)) * k).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
