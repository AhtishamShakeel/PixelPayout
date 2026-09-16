package com.createbyte.lootlevel.ui.home

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.createbyte.lootlevel.R

/** Displays static illustration panels directly from the supplied reference.
 * Click handling and accessibility labels belong to these native views; account
 * amounts, goals, levels and check-in data are separate live native controls.
 *
 * Home is rebuilt on every return to it, so two things here are deliberate:
 * decoded artwork is held for the life of the process (see [bitmaps]) rather
 * than decoded again for each new view, and nothing is allocated in onDraw -
 * the clip path and paints are built once per size change, not per frame of
 * the tab fade.
 */
class HomeReferenceArt @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val bitmap: Bitmap by lazy {
        val artwork = when (tag) {
            "games_background" -> R.drawable.home_games_background
            "quizzes_background" -> R.drawable.home_quizzes_background
            "offers_background" -> R.drawable.home_offers_background
            else -> R.drawable.home_reference
        }
        decode(resources, artwork)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val isBackground get() = tag.toString().endsWith("_background")

    // Built in onSizeChanged, reused by every onDraw.
    private val clip = Path()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blendPaint = Paint()
    private var blendTop = 0f
    private val destination = RectF()
    private val shaderMatrix = Matrix()

    private val source: Rect? = when (tag) {
        "games_background", "quizzes_background", "offers_background" -> null
        "guide" -> Rect(40, 439, 986, 588)
        "games" -> Rect(40, 650, 352, 834)
        "quizzes" -> Rect(365, 650, 662, 834)
        "offers" -> Rect(673, 650, 986, 834)
        "gift" -> Rect(62, 858, 144, 936)
        "goal_game" -> Rect(67, 948, 122, 993)
        "goal_quiz" -> Rect(67, 1006, 122, 1051)
        "calendar" -> Rect(65, 1143, 105, 1184)
        else -> null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        clip.reset()

        if (isBackground) {
            val radius = w * .065f
            clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)

            // Keep the complete objects above the native labels on shorter cards.
            val artWidth = minOf(w.toFloat(), h * .78f)
            val artHeight = artWidth * bitmap.height / bitmap.width
            val scale = artWidth / bitmap.width
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate((w - artWidth) / 2f, 0f)
            backgroundPaint.shader =
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(shaderMatrix)
                }

            // Blend only the empty area below the complete objects, so the
            // extended edges do not form seams behind the title and chip.
            val bottom = backgroundBottom()
            blendTop = artHeight * .65f
            blendPaint.shader = LinearGradient(0f, blendTop, 0f, artHeight,
                intArrayOf(bottom and 0x00FFFFFF, bottom), null, Shader.TileMode.CLAMP)
            return
        }

        val src = source ?: return
        if (tag == "goal_game" || tag == "goal_quiz") {
            clip.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
        }
        val fit = minOf(w.toFloat() / src.width(), h.toFloat() / src.height())
        val drawWidth = src.width() * fit
        val drawHeight = src.height() * fit
        destination.set((w - drawWidth) / 2f, (h - drawHeight) / 2f,
            (w + drawWidth) / 2f, (h + drawHeight) / 2f)
    }

    private fun backgroundBottom(): Int = when (tag) {
        "games_background" -> 0xFF1C103D.toInt()
        "quizzes_background" -> 0xFF073338.toInt()
        else -> 0xFF081C51.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        if (isBackground) {
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(backgroundBottom())
            // Extend the image edges into the remaining background without cropping.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            canvas.drawRect(0f, blendTop, width.toFloat(), height.toFloat(), blendPaint)
            canvas.restoreToCount(save)
            return
        }

        val src = source ?: return
        val save = canvas.save()
        if (!clip.isEmpty) canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, src, destination, paint)
        canvas.restoreToCount(save)
    }

    companion object {
        /**
         * Decoded artwork, kept for the life of the process.
         *
         * Held strongly on purpose. A weak reference was cleared as soon as
         * the user left Home, so every return decoded the images again on the
         * main thread in the middle of the tab fade. The backgrounds are
         * 512px, so the whole set costs a few MB of memory.
         */
        private val bitmaps = HashMap<Int, Bitmap>()

        private fun decode(resources: Resources, id: Int): Bitmap = bitmaps.getOrPut(id) {
            BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })
        }
    }
}
