package com.createbyte.lootlevel.utils

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.createbyte.lootlevel.R

/**
 * Star figures, drawn with the real star.
 *
 * Every string that quotes a Stars figure used to end in the "\u2605"
 * CHARACTER, which was the wrong star twice over: it is whatever shape the
 * TextView's font happens to resolve - not ic_star, the star on every other
 * surface in this app - and being part of the sentence it took the
 * sentence's colour, so a Stars figure inside a dim caption was a dim star.
 *
 * The strings still carry "\u2605" because that is what makes them readable
 * in strings.xml and translatable as ordinary sentences. It is swapped for
 * the drawable here, at the point of display.
 *
 * The FIGURE, not the whole line, is what gets weight and colour. These
 * lines are captions - "30 UC at 600 stars", "Top 30 share 2,450 stars" -
 * and emboldening all of one would make a caption shout; emboldening the
 * number alone is what makes it scannable.
 */
private const val STAR_SCALE = 0.95f

/**
 * Renders [formatted] into this TextView, replacing every "\u2605" with the
 * ic_star drawable in [starColor].
 *
 * [emphasise] is matched with lastIndexOf, not indexOf, because the figure a
 * star belongs to is always the last one before it: "6 XP to claim 6 stars"
 * has to embolden the SECOND 6, and the first match would be the XP.
 */
fun TextView.setStarText(
    formatted: CharSequence,
    emphasise: String? = null,
    @ColorRes emphasisColor: Int? = null,
    @ColorRes starColor: Int = R.color.stars_accent,
    @ColorRes figureColor: Int? = null,
    centerStars: Boolean = false
) {
    val out = SpannableStringBuilder(formatted)

    // Every Stars figure at once - each number sitting directly before a star -
    // for sentences quoting more than one ("costs 20 ★ and you have 5 ★"),
    // which a single [emphasise] match cannot reach.
    if (figureColor != null) {
        val color = ContextCompat.getColor(context, figureColor)
        FIGURE_BEFORE_STAR.findAll(out).forEach { match ->
            val range = match.groups[1]!!.range
            out.setSpan(
                StyleSpan(Typeface.BOLD), range.first, range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                ForegroundColorSpan(color), range.first, range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    if (emphasise != null) {
        val start = out.toString().lastIndexOf(emphasise)
        if (start >= 0) {
            val end = start + emphasise.length
            out.setSpan(
                StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            emphasisColor?.let {
                out.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, it)),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    val star = ContextCompat.getDrawable(context, R.drawable.ic_star)?.mutate()
    if (star != null) {
        val size = (textSize * STAR_SCALE).toInt()
        star.setBounds(0, 0, size, size)
        star.setTint(ContextCompat.getColor(context, starColor))

        // Walked backwards so replacing one glyph cannot move the index of
        // the next one still to be replaced.
        var at = out.toString().lastIndexOf(STAR_CHAR)
        while (at >= 0) {
            out.setSpan(
                if (centerStars) CenteredImageSpan(star) else ImageSpan(star, ImageSpan.ALIGN_BASELINE),
                at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            at = out.toString().lastIndexOf(STAR_CHAR, at - 1)
        }
    }

    text = out
}

private const val STAR_CHAR = "\u2605"

/** A number (grouping commas allowed) directly before a star, space optional. */
private val FIGURE_BEFORE_STAR = Regex("""(\d[\d,]*)\s?\u2605""")

/**
 * A star centred on the text's own middle rather than sat on its baseline.
 *
 * ALIGN_BASELINE puts the drawable's bottom on the baseline, so a star about
 * the height of the font rides well above the digits beside it - and in a
 * body with extra line spacing, as the app dialog has, that offset is what
 * reads as "not centred". Centring on the font metrics lines it up with the
 * figure it belongs to. (ImageSpan.ALIGN_CENTER would do this, but only from
 * API 29, and the app supports 24.)
 */
private class CenteredImageSpan(drawable: android.graphics.drawable.Drawable) :
    ImageSpan(drawable) {

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint
    ) {
        val d = drawable
        val metrics = paint.fontMetricsInt
        // Centre of the glyphs on this line, measured from the baseline.
        val textCentre = y + (metrics.ascent + metrics.descent) / 2
        canvas.save()
        canvas.translate(x, (textCentre - d.bounds.height() / 2).toFloat())
        d.draw(canvas)
        canvas.restore()
    }
}
