package uk.octopuslive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The big dial on the meter display, like the one on a smart meter's in-home display:
 * a 240° arc filled to [fraction], an optional marker (budget or a typical day) and the value in the middle.
 */
class DialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var fraction = 0f            // 0..1 of the dial
    var marker: Float? = null    // 0..1, null = none
    var fillColour = 0
    var value = "—"
    var unit = ""
    var caption = ""
    var scaleLabels: List<String> = emptyList()   // evenly spaced round the arc, e.g. 0, 1, 2, 3, 4 kW

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = context.getColor(R.color.divider_subtle)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = context.getColor(R.color.text_primary)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_primary); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_secondary); textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_muted); textAlign = Paint.Align.CENTER
    }
    private val oval = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square-ish: as wide as allowed, height a little less (the bottom of the circle is open)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hMax = MeasureSpec.getSize(heightMeasureSpec)
        var side = w
        if (hMode != MeasureSpec.UNSPECIFIED) side = min(side, (hMax / 0.88f).toInt())
        side = min(side, (360 * dp).toInt())
        setMeasuredDimension(w, (side * 0.88f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width.toFloat(), height / 0.88f)
        val stroke = side * 0.075f
        val cx = width / 2f
        val r = side / 2f - stroke - side * 0.06f
        val cy = side / 2f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        fill.strokeWidth = stroke
        fill.color = fillColour
        canvas.drawArc(oval, START, SWEEP, false, track)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.005f) canvas.drawArc(oval, START, SWEEP * f, false, fill)

        // Budget / typical marker: a short line across the arc
        marker?.coerceIn(0f, 1f)?.let { m ->
            val a = Math.toRadians((START + SWEEP * m).toDouble())
            val inner = r - stroke * 0.95f
            val outer = r + stroke * 0.95f
            markerPaint.strokeWidth = 3 * dp
            canvas.drawLine(
                cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(), markerPaint,
            )
        }

        // Scale labels just outside the arc
        if (scaleLabels.size > 1) {
            labelPaint.textSize = side * 0.04f
            scaleLabels.forEachIndexed { i, label ->
                val a = Math.toRadians((START + SWEEP * i / (scaleLabels.size - 1)).toDouble())
                val lr = r + stroke * 0.5f + side * 0.045f
                canvas.drawText(label, cx + (lr * cos(a)).toFloat(), cy + (lr * sin(a)).toFloat() + labelPaint.textSize / 3, labelPaint)
            }
        }

        // Value in the middle
        valuePaint.textSize = side * (if (value.length > 6) 0.15f else 0.19f)
        unitPaint.textSize = side * 0.06f
        captionPaint.textSize = side * 0.048f
        canvas.drawText(value, cx, cy + valuePaint.textSize * 0.3f, valuePaint)
        canvas.drawText(unit, cx, cy + valuePaint.textSize * 0.3f + unitPaint.textSize * 1.5f, unitPaint)
        if (caption.isNotEmpty()) {
            canvas.drawText(caption, cx, cy + r * 0.78f, captionPaint)
        }
    }

    companion object {
        private const val START = 150f   // degrees, clockwise from 3 o'clock: starts bottom-left
        private const val SWEEP = 240f
    }
}
