package com.createbyte.lootlevel.utils

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * "You placed in the weekly tournament."
 *
 * THE THING THIS FIXES is that winning one used to be silent. The Monday
 * settlement credits the stars and writes the result onto the user document;
 * until now nothing read it, so the single reward in this app that a user
 * competes for over a whole week was also the only one that arrived with no
 * announcement at all - the balance simply went up.
 *
 * A FREE FUNCTION ON THE ACTIVITY, not a fragment method, for the same reason
 * [showRedemptionResult] is one: settlement happens on a schedule, almost
 * always with the app closed, so whichever screen the user opens next is
 * arbitrary and only the host activity is guaranteed to be there to speak.
 *
 * Returns whether a dialog actually reached the screen, so the caller only
 * burns its "already told them" mark on an announcement that really appeared.
 * An activity that is finishing declines silently rather than throwing, which
 * is the normal race when this lands during a rotation or a back press -
 * saying nothing now is fine, the caller asks again on the next resume.
 */
fun AppCompatActivity.showLeaderboardPrize(
    prize: UserRepository.LeaderboardPrize,
    boardSize: Int,
    onViewBoard: () -> Unit,
    onDismissed: () -> Unit
): Boolean {
    if (isFinishing || isDestroyed) return false

    val view = layoutInflater.inflate(R.layout.dialog_leaderboard_prize, null)

    // Held so every one of them can be stopped on dismiss. The falling stars
    // repeat forever; without this they would outlive the dialog and keep a
    // detached view tree alive with them.
    val running = mutableListOf<Animator>()

    val dialog = Dialog(this, R.style.TakeoverDialogTheme).apply {
        setContentView(view)
        setOnDismissListener {
            running.forEach { it.cancel() }
            running.clear()
            onDismissed()
        }
    }
    dialog.goEdgeToEdge()

    view.findViewById<View>(R.id.prizeContent).padForSystemBars(bottomExtraDp = 26f)

    // The medal, where there is one. Ranks four and below keep the gold of
    // the trophy - they won a gold-coloured prize, they just did not medal -
    // which is the same rule the standings rows follow.
    val metal = metalFor(prize.rank)
    view.findViewById<ImageView>(R.id.prizeTrophy)
        .setColorFilter(color(if (metal != 0) metal else R.color.gold))

    view.findViewById<TextView>(R.id.prizeRankBadge).text =
        getString(R.string.leaderboard_rank, prize.rank.toString())

    view.findViewById<TextView>(R.id.prizeTitle).text = placeTitle(prize.rank)

    view.findViewById<TextView>(R.id.prizeAmount).text =
        getString(R.string.leaderboard_prize_star, formatCount(prize.points))

    view.findViewById<TextView>(R.id.prizePlaceValue).text =
        getString(R.string.leaderboard_prize_place_value, prize.rank, boardSize)

    // Only what the settlement actually recorded. A prize written before this
    // field existed carries no XP, and "0 XP" beside a win is a worse thing
    // to print than nothing at all.
    if (prize.weeklyXp > 0) {
        view.findViewById<TextView>(R.id.prizeXpValue).text =
            getString(R.string.leaderboard_xp, formatCount(prize.weeklyXp))
    } else {
        view.findViewById<View>(R.id.prizeXpRow).visibility = View.GONE
        view.findViewById<View>(R.id.prizeXpRule).visibility = View.GONE
    }

    view.findViewById<TextView>(R.id.prizeWeekValue).text = weekLabel(prize.weekKey)

    view.findViewById<View>(R.id.prizeDone).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.prizeClose).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.prizeViewBoard).setOnClickListener {
        dialog.dismiss()
        onViewBoard()
    }

    // Resting state is set BEFORE the window appears and the animators start
    // after, so the first frame is never the finished layout at full opacity
    // followed by a jump back to the start of the animation.
    prepare(view)
    dialog.show()
    animate(view, running)
    return true
}

/** Everything the motion below animates FROM. */
private fun AppCompatActivity.prepare(view: View) {
    val d = resources.displayMetrics.density
    view.alpha = 0f

    listOf(R.id.prizeGlow, R.id.prizeMedallion, R.id.prizeAmount).forEach { id ->
        view.findViewById<View>(id).apply {
            alpha = 0f
            scaleX = if (id == R.id.prizeGlow) 0.8f else 0.72f
            scaleY = scaleX
        }
    }
    view.findViewById<View>(R.id.prizeMedallionRing).alpha = 0f

    listOf(
        R.id.prizeEyebrow, R.id.prizeTitle, R.id.prizeMessage,
        R.id.prizeDetailCard, R.id.prizeFooter
    ).forEach { id ->
        view.findViewById<View>(id).apply {
            alpha = 0f
            translationY = 14f * d
        }
    }
}

/**
 * The same motion the paid-out dialog uses, in the same order.
 *
 * Transform and opacity only - no animated blur or filter - so it stays cheap
 * on the low-end devices most of these users are on.
 */
private fun AppCompatActivity.animate(view: View, running: MutableList<Animator>) {
    val d = resources.displayMetrics.density

    view.animate().alpha(1f).setDuration(220L).start()

    // The bloom.
    view.findViewById<View>(R.id.prizeGlow)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(100L).setDuration(500L)
        .setInterpolator(DecelerateInterpolator()).start()

    // The medallion lands with a small overshoot, then the ring pulses once
    // outward through it and fades - the "it actually happened" beat.
    view.findViewById<View>(R.id.prizeMedallion)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(120L).setDuration(520L)
        .setInterpolator(OvershootInterpolator(2.2f)).start()

    val ring = view.findViewById<View>(R.id.prizeMedallionRing)
    ObjectAnimator.ofPropertyValuesHolder(
        ring,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.9f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.9f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0f)
    ).apply {
        startDelay = 500L
        duration = 1500L
        interpolator = DecelerateInterpolator()
        running += this
        start()
    }

    // The copy rises in sequence, so the eye is walked down it.
    riseIn(view.findViewById(R.id.prizeEyebrow), 300L)
    riseIn(view.findViewById(R.id.prizeTitle), 360L)
    riseIn(view.findViewById(R.id.prizeMessage), 500L)
    riseIn(view.findViewById(R.id.prizeDetailCard), 580L, duration = 440L)
    riseIn(view.findViewById(R.id.prizeFooter), 660L)

    // The figure pops rather than rises: it is the number they played for.
    view.findViewById<View>(R.id.prizeAmount)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(440L).setDuration(500L)
        .setInterpolator(OvershootInterpolator(2.2f)).start()

    // The falling stars, each on its own clock so they never march in step.
    val stars = listOf(
        R.id.prizeStar1 to Triple(0.85f, 3400L, 150L),
        R.id.prizeStar2 to Triple(0.60f, 4100L, 700L),
        R.id.prizeStar3 to Triple(0.75f, 3800L, 1400L),
        R.id.prizeStar4 to Triple(0.50f, 4400L, 350L),
        R.id.prizeStar5 to Triple(0.80f, 3600L, 1900L),
        R.id.prizeStar6 to Triple(0.60f, 4200L, 1100L)
    )
    stars.forEach { (id, spec) ->
        val (peak, duration, delay) = spec
        val star = view.findViewById<ImageView>(id)
        ObjectAnimator.ofPropertyValuesHolder(
            star,
            PropertyValuesHolder.ofKeyframe(
                View.ALPHA,
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.12f, peak),
                Keyframe.ofFloat(1f, 0f)
            ),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -24f * d, 330f * d),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 180f)
        ).apply {
            this.duration = duration
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            running += this
            start()
        }
    }
}

private fun riseIn(target: View, delay: Long, duration: Long = 400L) {
    target.animate().alpha(1f).translationY(0f)
        .setStartDelay(delay).setDuration(duration)
        .setInterpolator(DecelerateInterpolator()).start()
}

/** "1st place", "2nd place", and so on. */
private fun AppCompatActivity.placeTitle(rank: Int): String =
    getString(R.string.leaderboard_prize_title, ordinal(rank))

/**
 * The English ordinal for a rank.
 *
 * Only ever asked about 1..30 - the board is capped at LEADERBOARD_SIZE - but
 * written for any positive number rather than as a table of thirty, so
 * widening the board later cannot silently produce a "21th".
 */
private fun ordinal(value: Int): String {
    val suffix = when {
        value % 100 in 11..13 -> "th"
        value % 10 == 1 -> "st"
        value % 10 == 2 -> "nd"
        value % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$value$suffix"
}

/**
 * The week a prize was won, as dates rather than as an index.
 *
 * `weekKey` is whole weeks since the epoch counted from a Monday - the same
 * arithmetic as utcWeekFor on the server, inverted. Nobody can read "week
 * 2987", and by the time this dialog is shown the week is already over, so
 * naming its days is the only form that means anything.
 */
private fun weekLabel(weekKey: Int): String {
    val startMillis = (weekKey.toLong() * 7 - 3) * TimeUnit.DAYS.toMillis(1)
    val endMillis = startMillis + TimeUnit.DAYS.toMillis(6)
    // FORMATTED IN UTC, unlike the reset countdown on the board. That
    // countdown is a duration and belongs in the reader's own clock; this is
    // the name of a week whose boundaries are defined in UTC, and rendering
    // it locally would move Monday to Sunday for anyone west of Greenwich and
    // print a week that does not match the one they played.
    return "${weekDayFormat().format(Date(startMillis))} – " +
        weekDayFormat().format(Date(endMillis))
}

/** The metal a place is worth, or 0 for the ranks that get no medal. */
@ColorRes
private fun metalFor(rank: Int): Int = when (rank) {
    1 -> R.color.gold
    2 -> R.color.silver
    3 -> R.color.bronze
    else -> 0
}

private fun AppCompatActivity.color(@ColorRes id: Int): Int =
    ContextCompat.getColor(this, id)

private fun formatCount(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(value)

/**
 * Fills the screen and lets the layout handle the system bars itself.
 *
 * TakeoverDialogTheme is already non-floating; this is the part that has to
 * happen on the window instance, and without it the content would stop at the
 * status bar instead of the scrim running under it.
 */
private fun Dialog.goEdgeToEdge() {
    window?.let { win ->
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(win, false)
    }
}

/** Keeps content clear of the status and navigation bars. */
private fun View.padForSystemBars(bottomExtraDp: Float) {
    val density = resources.displayMetrics.density
    val extra = (bottomExtraDp * density).toInt()
    val top = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, top + bars.top, v.paddingRight, extra + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * "1 Sep". Both ends carry their month so a week spanning two of them reads
 * correctly, which is the only case where it matters.
 *
 * Built per call rather than held in a field: SimpleDateFormat is not thread
 * safe, and a shared one pinned to UTC is a trap for the next caller who
 * wants a local time out of it.
 */
private fun weekDayFormat(): SimpleDateFormat =
    SimpleDateFormat("d MMM", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
