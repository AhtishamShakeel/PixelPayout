package com.example.pixelpayout.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.pixelpayout.R
import kotlin.math.roundToInt

/** Retains the approved horizontal composition while allowing native dp heights
 * and sp typography. Taller sections distribute their vertical positions within
 * the same design grid; artwork keeps its own aspect ratio in ImageViews.
 */
class HomeDesignLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val designWidth: Float
    var designHeight: Float = 0f
        set(value) { if (field != value) { field = value; requestLayout() } }
    private val flow: Boolean
    private var factor = 1f
    private var verticalFactor = 1f
    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.HomeDesignLayout)
        designWidth = a.getFloat(R.styleable.HomeDesignLayout_designWidth, 1024f)
        designHeight = a.getFloat(R.styleable.HomeDesignLayout_designHeight, 0f)
        flow = a.getBoolean(R.styleable.HomeDesignLayout_designFlow, false)
        a.recycle()
        clipChildren = false
        clipToPadding = false
    }

    class Params(context: Context, attrs: AttributeSet) : LayoutParams(context, attrs) {
        val x: Float; val y: Float; val w: Float; val h: Float; val textSize: Float
        val textPixels: Float; val gap: Float
        init {
            val a = context.obtainStyledAttributes(attrs, R.styleable.HomeDesignLayout_Layout)
            x = a.getFloat(R.styleable.HomeDesignLayout_Layout_designX, 0f)
            y = a.getFloat(R.styleable.HomeDesignLayout_Layout_designY, 0f)
            w = a.getFloat(R.styleable.HomeDesignLayout_Layout_designW, 0f)
            h = a.getFloat(R.styleable.HomeDesignLayout_Layout_designH, 0f)
            textSize = a.getFloat(R.styleable.HomeDesignLayout_Layout_designTextSize, 0f)
            textPixels = a.getDimension(R.styleable.HomeDesignLayout_Layout_designTextSp, 0f)
            gap = a.getDimension(R.styleable.HomeDesignLayout_Layout_designGap, -1f)
            a.recycle()
        }
    }
    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = Params(context, attrs)
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun checkLayoutParams(p: LayoutParams) = p is Params

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        factor = width / designWidth
        val desiredHeight = maxOf((designHeight * factor).roundToInt(), suggestedMinimumHeight)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        verticalFactor = if (!flow && designHeight > 0) height / designHeight else factor
        var total = 0f
        var firstVisible = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val p = child.layoutParams as Params
            val cw = (p.w * factor).roundToInt().coerceAtLeast(0)
            val ch = maxOf((p.h * verticalFactor).roundToInt(), child.minimumHeight)
            val textPixels = if (p.textPixels > 0) p.textPixels else p.textSize * factor
            if (child is TextView && textPixels > 0) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPixels)
                (child.text as? Spanned)?.getSpans(0, child.text.length, ImageSpan::class.java)?.forEach {
                    val side = (child.textSize * .95f).roundToInt()
                    it.drawable.setBounds(0, 0, side, side)
                }
                child.setPadding(0, 0, 0, 0)
                child.minimumWidth = 0
                if (child is MaterialButton) {
                    child.insetTop = 0; child.insetBottom = 0
                    child.cornerRadius = (12 * factor).roundToInt()
                }
            }
            if (child is CircularProgressIndicator) {
                child.indicatorInset = 0
                child.indicatorSize = minOf(cw, ch)
                child.trackThickness = (3 * factor).roundToInt().coerceAtLeast(1)
            }
            style(child, factor)
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, if (p.h > 0) MeasureSpec.EXACTLY else MeasureSpec.UNSPECIFIED))
            // Catalogue amounts and translated/action labels are not fixed copy.
            if (child is TextView && textPixels > 0 && cw > 0 && ch > 0) {
                var size = textPixels
                val minimum = size * .5f
                while (size > minimum && child.layout?.let { layout ->
                    layout.height > ch || layout.lineCount > child.maxLines || (0 until layout.lineCount).any { layout.getLineMax(it) > cw + 1 }
                } == true) {
                    size -= factor
                    child.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                    (child.text as? Spanned)?.getSpans(0, child.text.length, ImageSpan::class.java)?.forEach {
                        val side = (size * .95f).roundToInt()
                        it.drawable.setBounds(0, 0, side, side)
                    }
                    child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
                }
            }
            if (flow) total += flowGap(p, firstVisible) + child.measuredHeight
            firstVisible = false
        }
        setMeasuredDimension(width, if (flow) total.roundToInt() else height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var cursor = 0f
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val p = child.layoutParams as Params
            val x = (p.x * factor).roundToInt()
            val y = if (flow) (cursor + flowGap(p, first)).roundToInt()
                else (p.y * verticalFactor).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            if (flow) cursor = y + child.measuredHeight.toFloat()
            first = false
        }
    }

    /**
     * The space above a flowed child. None above the first VISIBLE one: Home
     * shows or hides its top rows at runtime (How it works, the pending
     * redemption), and whichever card ends up first should not sit below a
     * gap meant to separate it from a card that is not there.
     */
    private fun flowGap(p: Params, first: Boolean): Float =
        if (first) 0f else if (p.gap >= 0) p.gap else p.y * factor
    private fun style(view: View, scale: Float) {
        val palette = when (view.tag) {
            "reference_reward" -> intArrayOf(0xFF0D1036.toInt(), 0xFF061A39.toInt(), 0xFF34358A.toInt())
            "reference_level" -> intArrayOf(0xFF1D0D40.toInt(), 0xFF140D30.toInt(), 0xFF52209A.toInt())
            "reference_games" -> intArrayOf(0xFF6719B0.toInt(), 0xFF1C103D.toInt(), 0xFF7B36BD.toInt())
            "reference_quizzes" -> intArrayOf(0xFF048691.toInt(), 0xFF073338.toInt(), 0xFF13989D.toInt())
            "reference_offers" -> intArrayOf(0xFF075FE2.toInt(), 0xFF081C51.toInt(), 0xFF256BD1.toInt())
            "reference_xp_chip" -> intArrayOf(0x55483189, 0x4435245B, 0x225B4393)
            "reference_arrow" -> intArrayOf(0x555D43AA, 0x333C2C73, 0x225B4393)
            "reference_panel" -> intArrayOf(0xFF131328.toInt(), 0xFF111124.toInt(), 0xFF29283F.toInt())
            "reference_button" -> intArrayOf(0xFF673CFF.toInt(), 0xFF5321FF.toInt(), 0xFF855CF2.toInt())
            "reference_change" -> intArrayOf(0xCC15133A.toInt(), 0xCC10152F.toInt(), 0xFF6D63A5.toInt())
            "reference_gold" -> intArrayOf(0xFF302718.toInt(), 0xFF231E1D.toInt(), 0xFF7E693D.toInt())
            "reference_day" -> intArrayOf(0xFF121225.toInt(), 0xFF111124.toInt(), 0xFF2C2B42.toInt())
            "reference_day_active" -> intArrayOf(0xFF6933FF.toInt(), 0xFF5423FD.toInt(), 0xFF7043FF.toInt())
            "reference_day_claimed" -> intArrayOf(0xFF281A42.toInt(), 0xFF211933.toInt(), 0xFF504168.toInt())
            else -> return
        }
        val radius = when (view.tag) { "reference_gold" -> 32; "reference_button" -> 15; "reference_change", "reference_day", "reference_day_active", "reference_day_claimed" -> 12; else -> 20 }
        val background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.copyOf(2)).apply {
            cornerRadius = maxOf(radius * scale, (if (view.tag == "reference_gold") 22 else 10) * resources.displayMetrics.density)
            setStroke((2 * scale).roundToInt().coerceAtLeast(1), palette[2])
        }
        view.background = if (view.isClickable) RippleDrawable(ColorStateList.valueOf(0x35FFFFFF), background, null) else background
    }
}
