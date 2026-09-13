package com.example.pixelpayout.ui.play

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.pixelpayout.R
import java.lang.ref.WeakReference

/** Displays only the illustration regions of the supplied design, without its UI text.
 * Keeping a single source preserves the exact artwork instead of approximating it.
 * All labels, buttons and live values are separate native views.
 */
class ReferenceGameArtView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val artwork: Bitmap = cached.get() ?: BitmapFactory.decodeResource(
        resources, R.drawable.games_reference_art,
        BitmapFactory.Options().apply { inScaled = false }
    ).also { cached = WeakReference(it) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val edgePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var horizontalFade: Shader? = null
    private var verticalFade: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val isStep = tag?.toString()?.startsWith("step_") == true
        horizontalFade = if (isStep) {
            LinearGradient(0f, 0f, w.toFloat(), 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, .13f, .87f, 1f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, w * .32f, 0f,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        verticalFade = if (isStep || tag == "controller") {
            LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, .10f, .90f, 1f), Shader.TileMode.CLAMP)
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = when (tag?.toString()) {
            "flappy" -> Rect(366, 790, 826, 1103)
            "tower" -> Rect(366, 1129, 826, 1450)
            "controller" -> Rect(598, 111, 833, 281)
            "step_play" -> Rect(98, 304, 184, 377)
            "step_xp" -> Rect(306, 299, 369, 377)
            "step_level" -> Rect(493, 301, 574, 379)
            "step_star" -> Rect(686, 300, 773, 377)
            else -> return
        }
        // Coordinates refer to the original 864 x 1821 design.
        source.set(
            source.left * artwork.width / 864, source.top * artwork.height / 1821,
            source.right * artwork.width / 864, source.bottom * artwork.height / 1821
        )
        val isStep = tag?.toString()?.startsWith("step_") == true
        val scale = if (isStep) {
            minOf(width.toFloat() / source.width(), height.toFloat() / source.height())
        } else {
            maxOf(width.toFloat() / source.width(), height.toFloat() / source.height())
        }
        val drawWidth = source.width() * scale
        val drawHeight = source.height() * scale
        destination.set((width - drawWidth) / 2f, (height - drawHeight) / 2f,
            (width + drawWidth) / 2f, (height + drawHeight) / 2f)
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(artwork, source, destination, paint)
        edgePaint.shader = horizontalFade
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgePaint)
        verticalFade?.let {
            edgePaint.shader = it
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgePaint)
        }
        canvas.restoreToCount(layer)
    }

    private companion object {
        var cached = WeakReference<Bitmap>(null)
    }
}
