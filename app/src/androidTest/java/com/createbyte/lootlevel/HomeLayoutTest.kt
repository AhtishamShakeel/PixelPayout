package com.createbyte.lootlevel

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.createbyte.lootlevel.utils.setStarText
import com.createbyte.lootlevel.ui.home.setRewardAmount
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.FragmentHomeBinding
import com.createbyte.lootlevel.databinding.LayoutCustomToolbarBinding
import com.createbyte.lootlevel.ui.main.PixelBottomNav
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native offline renders. No sign-in, network requests, or reward writes. */
@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    @Test fun homeIsReadableAcrossWidthsTextSizesAndClaimStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for ((widthDp, scale) in listOf(390 to 1f, 320 to 1f, 390 to 1.5f, 320 to 1.5f)) {
                for (claimable in listOf(false, true)) {
                    val config = Configuration(instrumentation.targetContext.resources.configuration)
                    config.fontScale = scale
                    config.screenWidthDp = widthDp
                    val context = ContextThemeWrapper(
                        instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_LootLevel)
                    val inflater = LayoutInflater.from(context).cloneInContext(context)
                    inflater.factory2 = object : LayoutInflater.Factory2 {
                        override fun onCreateView(parent: View?, name: String, context: android.content.Context,
                            attrs: android.util.AttributeSet): View? = when (name) {
                                "TextView" -> AppCompatTextView(context, attrs)
                                "ImageView" -> AppCompatImageView(context, attrs)
                                else -> null
                            }
                        override fun onCreateView(name: String, context: android.content.Context,
                            attrs: android.util.AttributeSet): View? = onCreateView(null, name, context, attrs)
                    }
                    val b = FragmentHomeBinding.inflate(inflater)
                    b.root.layoutDirection = View.LAYOUT_DIRECTION_LTR
                    b.rewardTitle.setText(R.string.home_first_reward)
                    b.rewardAmount.setRewardAmount(if (claimable) "325 Diamonds" else "30 UC")
                    instrumentation.context.assets.open("home_reward_preview.png").use {
                        b.rewardArtwork.setImageBitmap(BitmapFactory.decodeStream(it))
                    }
                    b.balanceTarget.setStarText("600 stars to unlock", "600 stars", R.color.stars_accent)
                    b.balanceCurrent.setStarText("0 / 600 ★")
                    b.redemptionProgress.progress = 0
                    b.levelTitle.text = "Level 1"
                    b.levelBadgeNumber.text = "1"
                    b.levelReward.setStarText("+5 ★")
                    b.levelXpCurrent.text = "27 / 50 XP"
                    b.levelProgressBar.progress = 54
                    if (claimable) {
                        b.btnPayout.text = "Redeem 325 Diamonds now"
                        b.levelRewardsButton.setStarText("Claim 125 ★ now")
                    }
                    b.goalsCard.isVisible = true
                    b.goalsHeader.isVisible = true
                    b.goalsSubtitle.text = "Finish all 3 tasks to claim"
                    b.goalsBonus.setStarText("+10 ★")
                    val labels = listOf(b.goalLabel1, b.goalLabel2, b.goalLabel3)
                    val counts = listOf(b.goalProgress1, b.goalProgress2, b.goalProgress3)
                    val rings = listOf(b.goalRing1, b.goalRing2, b.goalRing3)
                    val actions = listOf(b.goalAction1, b.goalAction2, b.goalAction3)
                    listOf("Play 8 games", "Complete 9 quizzes", "Get 15 correct answers").forEachIndexed { i, label ->
                        labels[i].text = label
                        counts[i].text = listOf("2/8", "9/9", "4/15")[i]
                        rings[i].progress = listOf(25, 100, 27)[i]
                        actions[i].text = listOf("Play", "Done ✓", "Start")[i]
                        if (i == 1) {
                            rings[i].setIndicatorColor(context.getColor(R.color.success))
                            actions[i].setBackgroundResource(R.drawable.bg_home_day_claimed)
                            labels[i].setTextColor(context.getColor(R.color.text_faint))
                        }
                    }
                    b.goalsClaimButton.isVisible = claimable
                    b.goalsBonus.isVisible = !claimable
                    b.streakFooter.text = "Tomorrow: +20 XP"
                    val cells = listOf(b.streakCell1,b.streakCell2,b.streakCell3,b.streakCell4,b.streakCell5,b.streakCell6,b.streakCell7)
                    cells.forEachIndexed { i, cell ->
                        cell.tag = if (i == 0) "reference_day_active" else "reference_day"
                        cell.text = "Day ${i+1}\n" + listOf("✓\nToday","20\nXP","30\nXP","10\n★","50\nXP","60\nXP","20\n★")[i]
                    }
                    b.streakCell1.setBackgroundResource(R.drawable.bg_home_day_active)
                    b.streakCell4.setTextColor(context.getColor(R.color.stars_accent))
                    b.streakCell7.setTextColor(context.getColor(R.color.stars_accent))
                    b.streakClaimButton.isVisible = claimable
                    b.streakFooter.isVisible = !claimable
                    b.payoutFeedRow.isVisible = true
                    b.payoutAvatar1.text = "A"
                    b.payoutFeedText.text = "Abd***iz received 30 UC"
                    b.payoutFeedTime.text = "1d ago"
                    b.debugControls.isVisible = false
                    val density = context.resources.displayMetrics.density
                    val width = (widthDp * density).toInt()
                    val height = ((760 - 72 - 78) * density).toInt()
                    b.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    b.root.layout(0,0,width,height)
                    // Unattached offline views do not receive visibility callbacks.
                    rings.forEach { it.progressDrawable?.setVisible(true, false) }
                    val state = "$widthDp-$scale-$claimable"
                    assertEquals("Hero card heights: $state", b.starsCard.height, b.xpCard.height)
                    assertTrue("Hero cards have native app height", b.starsCard.height >= 220 * density - 1)
                    assertTrue("Hero cards stay side by side", b.xpCard.left >= b.starsCard.right)
                    assertTrue("Artwork belongs to right half", b.rewardArtwork.left > b.starsCard.width/2)
                    assertTrue("Progress bar remains in left half", b.redemptionProgress.width <= b.starsCard.width*.5)
                    assertTrue("Star progress does not overlap its count", b.redemptionProgress.bottom <= b.balanceCurrent.top)
                    assertTrue("Level progress does not overlap its reward", b.levelProgressBar.bottom <= b.levelReward.top)
                    assertTrue("Redeem button has normal app height", b.btnPayout.height >= 44 * density - 1)
                    assertTrue("Level button has normal app height", b.levelRewardsButton.height >= 44 * density - 1)
                    assertTrue("Level label must stay intact", b.levelTitle.lineCount <= 1)
                    fun check(view: View) {
                        if (view.visibility != View.VISIBLE) return
                        if (view is TextView && view.text.isNotEmpty()) {
                            val layout = view.layout ?: return
                            assertTrue("Clipped height $state: ${view.text}", layout.height <= view.height-view.compoundPaddingTop-view.compoundPaddingBottom)
                            for (line in 0 until layout.lineCount) {
                                assertEquals("Ellipsis $state: ${view.text}", 0, layout.getEllipsisCount(line))
                                // getLineMax excludes trailing whitespace at a wrap.
                                assertTrue("Clipped width $state: ${view.text}", layout.getLineMax(line) <= view.width-view.compoundPaddingLeft-view.compoundPaddingRight+2)
                            }
                        }
                        if (view is ViewGroup) for (i in 0 until view.childCount) check(view.getChildAt(i))
                    }
                    check(b.root)
                    val toolbar = LayoutCustomToolbarBinding.inflate(inflater)
                    toolbar.usernameText.text = "Hey, hammad"
                    toolbar.pointsHeader.pointsText.text = if (claimable) "12500" else "0"
                    toolbar.levelAvatar.levelRing.progress = 27
                    toolbar.levelAvatar.levelBadge.text = "1"
                    val navigation = PixelBottomNav(context)
                    listOf(toolbar.root, navigation).forEach { chrome ->
                        chrome.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            if (chrome == toolbar.root) View.MeasureSpec.makeMeasureSpec((72 * density).toInt(), View.MeasureSpec.EXACTLY)
                            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        chrome.layout(0, 0, width, chrome.measuredHeight)
                    }
                    assertEquals("Home uses the shared 72dp title bar", (72 * density).toInt(), toolbar.root.height)
                    toolbar.levelAvatar.levelRing.progressDrawable?.setVisible(true, false)
                    run {
                        val content = b.root.getChildAt(0)
                        val gap = b.root.paddingBottom
                        val bitmap = Bitmap.createBitmap(width,toolbar.root.height + b.root.paddingTop + content.height + gap + navigation.height,Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(context.getColor(R.color.home_background))
                        toolbar.root.draw(canvas)
                        canvas.translate(b.root.paddingLeft.toFloat(), toolbar.root.height.toFloat() + b.root.paddingTop)
                        content.draw(canvas)
                        canvas.translate(-b.root.paddingLeft.toFloat(), content.height.toFloat() + gap)
                        navigation.draw(canvas)
                        val suffix = if (claimable) "-claimable" else ""
                        File(context.getExternalFilesDir(null),"home-native-$widthDp-$scale$suffix.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                        bitmap.recycle()
                    }
                    if (widthDp == 390 && scale == 1f && !claimable) {
                        val screen = Bitmap.createBitmap(width, toolbar.root.height + height + navigation.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(screen)
                        canvas.drawColor(context.getColor(R.color.home_background))
                        toolbar.root.draw(canvas)
                        canvas.translate(0f, toolbar.root.height.toFloat())
                        b.root.draw(canvas)
                        canvas.translate(0f, height.toFloat())
                        navigation.draw(canvas)
                        File(context.getExternalFilesDir(null), "home-native-screen.png").outputStream().use {
                            screen.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        screen.recycle()
                    }
                    b.root.fullScroll(View.FOCUS_DOWN)
                    assertTrue("Check-in must remain reachable", b.root.scrollY > 0 || b.root.getChildAt(0).height <= b.root.height)
                }
            }
        }
    }
}
