package com.example.pixelpayout.ui.home

import android.content.Context
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
import com.pixelpayout.R
import java.lang.ref.WeakReference

/** Displays static illustration panels directly from the supplied reference.
 * Click handling and accessibility labels belong to these native views; account
 * amounts, goals, levels and check-in data are separate live native controls.
 */
class HomeReferenceArt @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val bitmap by lazy {
        val artwork = when (tag) {
            "games_background" -> R.drawable.home_games_background
            "quizzes_background" -> R.drawable.home_quizzes_background
            "offers_background" -> R.drawable.home_offers_background
            else -> null
        }
        if (artwork != null) {
            BitmapFactory.decodeResource(resources, artwork,
                BitmapFactory.Options().apply { inScaled = false })
        } else {
            cache.get() ?: BitmapFactory.decodeResource(resources, R.drawable.home_reference,
                BitmapFactory.Options().apply { inScaled = false }).also { cache = WeakReference(it) }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = when (tag) {
            "games_background", "quizzes_background", "offers_background" -> Rect(0, 0, bitmap.width, bitmap.height)
            "guide" -> Rect(40, 439, 986, 588)
            "games" -> Rect(40, 650, 352, 834)
            "quizzes" -> Rect(365, 650, 662, 834)
            "offers" -> Rect(673, 650, 986, 834)
            "gift" -> Rect(62, 858, 144, 936)
            "goal_game" -> Rect(67, 948, 122, 993)
            "goal_quiz" -> Rect(67, 1006, 122, 1051)
            "calendar" -> Rect(65, 1143, 105, 1184)
            else -> return
        }
        val save = canvas.save()
        if (tag.toString().endsWith("_background")) {
            val radius = width * .065f
            canvas.clipPath(Path().apply { addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW) })
            val bottom = when (tag) {
                "games_background" -> 0xFF1C103D.toInt()
                "quizzes_background" -> 0xFF073338.toInt()
                else -> 0xFF081C51.toInt()
            }
            canvas.drawColor(bottom)
            // Keep the complete objects above the native labels on shorter cards.
            val artWidth = minOf(width.toFloat(), height * .78f)
            val artHeight = artWidth * bitmap.height / bitmap.width
            val scale = artWidth / bitmap.width
            val backgroundPaint = Paint(paint).apply {
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - artWidth) / 2f, 0f)
                    })
                }
            }
            // Extend the image edges into the remaining background without cropping.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            // Blend only the empty area below the complete objects, so the
            // extended edges do not form seams behind the title and chip.
            val backgroundBlend = Paint().apply { shader = LinearGradient(0f, artHeight * .65f, 0f, artHeight,
                intArrayOf(bottom and 0x00FFFFFF, bottom), null, Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, artHeight * .65f, width.toFloat(), height.toFloat(), backgroundBlend)
            canvas.restoreToCount(save)
            return
        }
        if (tag == "goal_game" || tag == "goal_quiz") {
            canvas.clipPath(Path().apply {
                addCircle(width / 2f, height / 2f, minOf(width, height) / 2f, Path.Direction.CW)
            })
        }
        val fit = minOf(width.toFloat() / source.width(), height.toFloat() / source.height())
        val drawWidth = source.width() * fit
        val drawHeight = source.height() * fit
        canvas.drawBitmap(bitmap, source, RectF((width - drawWidth) / 2f, (height - drawHeight) / 2f,
            (width + drawWidth) / 2f, (height + drawHeight) / 2f), paint)
        canvas.restoreToCount(save)
    }
    companion object { private var cache = WeakReference<Bitmap>(null) }
}
