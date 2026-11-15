package uk.octopuslive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.min

/**
 * A page that scrolls as usual, and at the top can be pulled down to refresh: the content slides
 * down, a round indicator fills as you pull, and past the line it spins until [setRefreshing] (false).
 * No extra libraries; vertical pulls only, so sideways swipes (Usage) still work.
 */
class PullRefreshScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    /** Called once when a pull is released past the line. */
    var onRefresh: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val trigger = 72 * dp          // pull this far to refresh
    private val maxPull = 120 * dp
    private val hold = 56 * dp             // where the content waits while refreshing

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.card_surface) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp; color = context.getColor(R.color.card_stroke)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.brand_pink)
    }
    private val oval = RectF()

    private var pull = 0f
    private var startX = 0f
    private var startY = 0f
    private var armed = false
    private var pulling = false
    private var refreshing = false
    private var settle: ValueAnimator? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x; startY = ev.y
                pulling = false
                armed = scrollY == 0 && !refreshing && onRefresh != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (armed && !pulling) {
                    val dy = ev.y - startY
                    if (dy > slop && dy > abs(ev.x - startX) * 1.5f && scrollY == 0) {
                        pulling = true
                        startY = ev.y
                        settle?.cancel()
                        // Let go of whatever the finger was on (a button, the swipe detector...)
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    } else if (dy < -slop) {
                        armed = false   // scrolling up: an ordinary scroll
                    }
                }
                if (pulling) {
                    setPull(((ev.y - startY) * 0.5f).coerceIn(0f, maxPull))
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                pulling = false
                if (pull >= trigger && ev.actionMasked == MotionEvent.ACTION_UP) {
                    refreshing = true
                    animateTo(hold)
                    onRefresh?.invoke()
                } else {
                    animateTo(0f)
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Stop the spinner and slide the content back (call when the refresh has finished). */
    fun setRefreshing(on: Boolean) {
        if (on == refreshing) return
        refreshing = on
        animateTo(if (on) hold else 0f)
    }

    private fun setPull(p: Float) {
        pull = p
        getChildAt(0)?.translationY = p
        invalidate()
    }

    private fun animateTo(target: Float) {
        settle?.cancel()
        settle = ValueAnimator.ofFloat(pull, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPull(it.animatedValue as Float) }
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (pull <= 0f) return
        val r = 17 * dp
        val progress = min(pull / trigger, 1f)
        val cx = width / 2f
        val cy = scrollY + pull / 2f
        disc.alpha = (255 * progress).toInt()
        ring.alpha = disc.alpha
        arc.alpha = disc.alpha
        canvas.drawCircle(cx, cy, r, disc)
        canvas.drawCircle(cx, cy, r, ring)
        val a = r - 6 * dp
        oval.set(cx - a, cy - a, cx + a, cy + a)
        if (refreshing) {
            val spin = (System.nanoTime() / 2_500_000L % 360).toFloat()
            canvas.drawArc(oval, spin, 270f, false, arc)
            postInvalidateOnAnimation()
        } else {
            canvas.drawArc(oval, -90f + 180f * progress, 300f * progress, false, arc)
        }
    }
}
