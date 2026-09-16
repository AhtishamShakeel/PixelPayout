package com.createbyte.lootlevel.ui.play

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.createbyte.lootlevel.R
import kotlin.math.max
import kotlin.math.min

/**
 * A full-screen dim with a lit cut-out around whatever the player should tap
 * next, and a pulsing ring on it.
 *
 * Built for players who may not read much English: the hole and the ring say
 * "tap here" on their own, so the words on the callout can stay to a few.
 *
 * TOUCHES INSIDE THE HOLE GO THROUGH to the real view underneath - the game
 * row, the Quizzes tab, the category card - so the tutorial drives the actual
 * app rather than a copy of it. Everything outside the hole is swallowed,
 * bottom bar included, which is why this sits on the activity's content root
 * rather than inside the Play fragment.
 *
 * The hole is re-measured on every frame from the live views, so it follows
 * scrolling, the collapsing header and the quiz grid loading in late.
 */
class PlayTutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** The views to light up. Their bounds are unioned into one hole. */
    var targets: () -> List<View> = { emptyList() }

    /** A tap that went through the hole. Fired on touch-down. */
    var onHoleTapped: (() -> Unit)? = null

    /** Children that must stay tappable even over the hole. */
    private val cards = mutableListOf<View>()

    /** The callout that follows the hole, placed below it or above it. */
    var callout: View? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val hole = RectF()
    private val firstTarget = RectF()
    private var hasHole = false
    private val holeRadius = dp(16f)
    private val holePad = dp(6f)

    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val scrimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.tutorial_scrim)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.brand_violet_light)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = ContextCompat.getColor(context, R.color.brand_violet_light)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_violet_light)
    }

    private var pulse = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_300
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            if (hasHole) invalidate()
        }
    }

    private val selfLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        measureHole()
        true
    }

    /** True from a touch-down in the hole until that gesture ends. */
    private var passingThrough = false

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    fun addCard(view: View) {
        if (view !in cards) cards += view
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    private fun measureHole() {
        val views = targets().filter { it.isAttachedToWindow && it.isShown && it.width > 0 }
        if (views.isEmpty()) {
            if (hasHole) {
                hasHole = false
                placeCallout()
                invalidate()
            }
            return
        }

        getLocationInWindow(selfLocation)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        views.forEachIndexed { index, view ->
            view.getLocationInWindow(targetLocation)
            val l = (targetLocation[0] - selfLocation[0]).toFloat()
            val t = (targetLocation[1] - selfLocation[1]).toFloat()
            val r = l + view.width
            val b = t + view.height
            if (index == 0) firstTarget.set(l, t, r, b)
            left = min(left, l)
            top = min(top, t)
            right = max(right, r)
            bottom = max(bottom, b)
        }

        val newLeft = max(left - holePad, 0f)
        val newTop = max(top - holePad, 0f)
        val newRight = min(right + holePad, width.toFloat())
        val newBottom = min(bottom + holePad, height.toFloat())
        if (!hasHole || hole.left != newLeft || hole.top != newTop ||
            hole.right != newRight || hole.bottom != newBottom
        ) {
            hole.set(newLeft, newTop, newRight, newBottom)
            hasHole = true
            placeCallout()
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        placeCallout()
    }

    /** Below the hole when it fits, above it when it does not. */
    private fun placeCallout() {
        val view = callout ?: return
        if (!hasHole || view.height == 0) return
        val gap = dp(18f)
        val below = hole.bottom + gap
        view.translationY = if (below + view.height + gap <= height) {
            below - view.top
        } else {
            max(gap, hole.top - gap - view.height) - view.top
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        path.reset()
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        if (hasHole) path.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW)
        canvas.drawPath(path, scrimPaint)

        if (hasHole) {
            canvas.drawRoundRect(hole, holeRadius, holeRadius, edgePaint)

            // The "tap here" mark, on the first target: a dot, and a ring that
            // grows out of it and fades.
            val cx = firstTarget.centerX()
            val cy = firstTarget.centerY()
            ringPaint.alpha = ((1f - pulse) * 255).toInt()
            canvas.drawCircle(cx, cy, dp(14f) + pulse * dp(26f), ringPaint)
            dotPaint.alpha = 220
            canvas.drawCircle(cx, cy, dp(11f), dotPaint)
        }

        super.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            passingThrough = hasHole &&
                hole.contains(event.x, event.y) &&
                cards.none { it.isShown && it.hitRect().contains(event.x, event.y) }
            if (passingThrough) onHoleTapped?.invoke()
        }

        if (passingThrough) {
            // Declining the touch-down hands the whole gesture to whatever is
            // underneath, so the rest of it never reaches here.
            passingThrough = false
            return false
        }

        super.dispatchTouchEvent(event)
        return true
    }

    private fun View.hitRect(): RectF =
        RectF(
            left + translationX,
            top + translationY,
            right + translationX,
            bottom + translationY
        )
}
