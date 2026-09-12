package com.example.pixelpayout

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pixelpayout.data.model.OfferwallEntry
import com.example.pixelpayout.ui.rewards.OfferwallAdapter
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentRewardsBinding
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline layout fixtures: no catalogue writes, SDK calls, or reward requests. */
@RunWith(AndroidJUnit4::class)
class EarnLayoutTest {
    @Test
    fun offerCardsRemainReadableAndSelectTheCorrectProvider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for ((widthDp, fontScale) in listOf(390 to 1f, 320 to 1f, 390 to 1.5f, 320 to 1.5f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration)
                config.fontScale = fontScale
                config.screenWidthDp = widthDp
                val context = ContextThemeWrapper(
                    instrumentation.targetContext.createConfigurationContext(config),
                    R.style.Theme_PixelPayout
                )
                val inflater = LayoutInflater.from(context).cloneInContext(context)
                inflater.factory2 = object : LayoutInflater.Factory2 {
                    override fun onCreateView(parent: View?, name: String, context: android.content.Context,
                        attrs: android.util.AttributeSet): View? =
                        if (name == "TextView") AppCompatTextView(context, attrs) else null
                    override fun onCreateView(name: String, context: android.content.Context,
                        attrs: android.util.AttributeSet): View? = onCreateView(null, name, context, attrs)
                }
                val binding = FragmentRewardsBinding.inflate(inflater)
                binding.root.layoutDirection = View.LAYOUT_DIRECTION_LTR
                binding.leaderboardRank.text = "#1 · You"
                binding.leaderboardMyXp.text = "350 XP"
                binding.leaderboardPrizePool.text = "2,450 ★"
                binding.leaderboardPrizeShare.text = "Top 30 share"
                binding.leaderboardSubtitle.text = "Top 30 share 2,450 stars"
                binding.offerwallLoading.isVisible = false
                binding.offerwallEmpty.isVisible = false
                binding.offerwallList.isVisible = true
                val wall = OfferwallEntry("preview", "Tapjoy", "", "", OfferwallEntry.TYPE_TAPJOY, 1, 0)
                var selected: OfferwallEntry? = null
                binding.offerwallList.layoutManager = LinearLayoutManager(context)
                binding.offerwallList.adapter = OfferwallAdapter(listOf(wall)) { selected = it }
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (760 * density).toInt()
                binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                binding.root.layout(0, 0, width, height)
                val card = binding.offerwallList.findViewHolderForAdapterPosition(0)!!.itemView
                assertTrue(card.performClick())
                assertSame(wall, selected)
                assertTrue(binding.viewTournament.height >= (48 * density).toInt())
                assertTrue("Summary must sit below the artwork",
                    binding.leaderboardSubtitle.top >= binding.tournamentArt.bottom)
                fun checkText(view: View) {
                    if (view is TextView && view.visibility == View.VISIBLE) {
                        val layout = view.layout ?: return
                        assertTrue("Clipped text at ${widthDp}dp/$fontScale: ${view.text}",
                            layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                        for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) checkText(view.getChildAt(index))
                }
                checkText(binding.root)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                binding.root.draw(Canvas(bitmap))
                File(context.getExternalFilesDir(null), "earn-${widthDp}-${fontScale}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                binding.root.fullScroll(View.FOCUS_DOWN)
                assertTrue("Offers remain reachable by scrolling", binding.root.scrollY > 0 ||
                    binding.root.getChildAt(0).height <= binding.root.height)
                binding.offerwallList.adapter = null
            }
        }
    }
}
