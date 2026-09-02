package com.example.pixelpayout.utils

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.redemption.WalletFormat
import com.pixelpayout.R
import java.util.Date

/** How long the declined sheet ignores taps on the scrim behind it. */
private const val SCRIM_GUARD_MILLIS = 2000L

/**
 * The "your payout was settled" announcement.
 *
 * A FREE FUNCTION ON THE ACTIVITY, not a fragment method, because settlement
 * is an app-level event: it is decided by hand on our side, usually while the
 * app is closed, so whichever screen the user opens next is arbitrary. Only
 * the host activity is guaranteed to be there to say it.
 *
 * Approval and decline get two different layouts rather than one with a
 * colour swapped, per the handoff. Approval is the moment the whole app
 * exists to produce, so it takes the screen; a decline leaves the user
 * mid-flow with their stars back, so it is a sheet with "Redeem again" on it.
 *
 * Returns whether a dialog actually reached the screen, so the caller only
 * burns its "already told them" mark on an announcement that really appeared.
 * An activity that is finishing declines silently rather than throwing, which
 * is the normal race when a settlement lands during a rotation or a back
 * press - saying nothing now is fine, the caller asks again next resume.
 */
fun AppCompatActivity.showRedemptionResult(
    result: UserRepository.ResolvedRedemption,
    onRedeemAgain: () -> Unit,
    onDismissed: () -> Unit
): Boolean {
    if (isFinishing || isDestroyed) return false
    return if (result.approved) {
        showRewardPaid(result, onDismissed)
    } else {
        showRedemptionDeclined(result, onRedeemAgain, onDismissed)
    }
}

// --------------------------------------------------------------------------
// 1a - paid
// --------------------------------------------------------------------------

private fun AppCompatActivity.showRewardPaid(
    result: UserRepository.ResolvedRedemption,
    onDismissed: () -> Unit
): Boolean {
    val view = layoutInflater.inflate(R.layout.dialog_reward_paid, null)

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

    view.findViewById<View>(R.id.paidContent).padForSystemBars(bottomExtraDp = 26f)

    view.findViewById<TextView>(R.id.paidAmount).text = result.headline

    // Only game top-ups carry a destination; anything else hides the row and
    // the rule under it rather than printing an empty value.
    val destination = result.destination
    val sentToRow = view.findViewById<View>(R.id.paidSentToRow)
    val rule = view.findViewById<View>(R.id.paidDetailRule)
    if (destination.isBlank()) {
        sentToRow.visibility = View.GONE
        rule.visibility = View.GONE
    } else {
        view.findViewById<TextView>(R.id.paidSentToValue).text = destination
    }

    view.findViewById<TextView>(R.id.paidApprovedValue).text =
        settledAt(result.resolvedAtMillis)

    // An approval usually carries no note. When the admin left one it is the
    // only part of this screen they wrote themselves, so it gets a box.
    val note = result.rejectionReason?.trim().orEmpty()
    if (note.isNotEmpty()) {
        view.findViewById<View>(R.id.paidNoteBox).visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.paidNoteText).text = note
    }

    view.findViewById<View>(R.id.paidDone).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.paidClose).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.paidShare).setOnClickListener {
        shareWin(result.headline)
    }

    // Resting state is set BEFORE the window appears and the animators are
    // started after, so the first frame is never the finished layout at full
    // opacity followed by a jump back to the start of the animation.
    preparePaid(view)
    dialog.show()
    animatePaid(view, running)
    return true
}

/** Everything the motion below animates FROM. */
private fun AppCompatActivity.preparePaid(view: View) {
    val d = resources.displayMetrics.density
    view.alpha = 0f

    listOf(R.id.paidGlow, R.id.paidMedallion, R.id.paidAmount).forEach { id ->
        view.findViewById<View>(id).apply {
            alpha = 0f
            scaleX = if (id == R.id.paidGlow) 0.8f else 0.72f
            scaleY = scaleX
        }
    }
    view.findViewById<View>(R.id.paidMedallionRing).alpha = 0f

    listOf(
        R.id.paidEyebrow, R.id.paidTitle, R.id.paidMessage,
        R.id.paidDetailCard, R.id.paidFooter
    ).forEach { id ->
        view.findViewById<View>(id).apply {
            alpha = 0f
            translationY = 14f * d
        }
    }
}

/**
 * The handoff's motion, in the order it plays.
 *
 * Transform and opacity only - no animated blur or filter - so it stays cheap
 * on the low-end devices most of these users are on.
 */
private fun AppCompatActivity.animatePaid(view: View, running: MutableList<Animator>) {
    val d = resources.displayMetrics.density

    view.animate().alpha(1f).setDuration(220L).start()

    // The bloom.
    view.findViewById<View>(R.id.paidGlow)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(100L).setDuration(500L)
        .setInterpolator(DecelerateInterpolator()).start()

    // The medallion lands with a small overshoot, then the ring pulses once
    // outward through it and fades - the "it actually happened" beat.
    view.findViewById<View>(R.id.paidMedallion)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(120L).setDuration(520L)
        .setInterpolator(OvershootInterpolator(2.2f)).start()

    val ring = view.findViewById<View>(R.id.paidMedallionRing)
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
    riseIn(view.findViewById(R.id.paidEyebrow), 300L)
    riseIn(view.findViewById(R.id.paidTitle), 360L)
    riseIn(view.findViewById(R.id.paidMessage), 500L)
    riseIn(view.findViewById(R.id.paidDetailCard), 580L, duration = 440L)
    riseIn(view.findViewById(R.id.paidFooter), 660L)

    // The figure pops rather than rises: it is the number they waited for.
    view.findViewById<View>(R.id.paidAmount)
        .animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(440L).setDuration(500L)
        .setInterpolator(OvershootInterpolator(2.2f)).start()

    // The falling stars, each on its own clock so they never march in step.
    val stars = listOf(
        R.id.paidStar1 to Triple(0.85f, 3400L, 150L),
        R.id.paidStar2 to Triple(0.60f, 4100L, 700L),
        R.id.paidStar3 to Triple(0.75f, 3800L, 1400L),
        R.id.paidStar4 to Triple(0.50f, 4400L, 350L),
        R.id.paidStar5 to Triple(0.80f, 3600L, 1900L),
        R.id.paidStar6 to Triple(0.60f, 4200L, 1100L),
        R.id.paidStar7 to Triple(0.70f, 3900L, 2300L)
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

// --------------------------------------------------------------------------
// 1c - declined
// --------------------------------------------------------------------------

private fun AppCompatActivity.showRedemptionDeclined(
    result: UserRepository.ResolvedRedemption,
    onRedeemAgain: () -> Unit,
    onDismissed: () -> Unit
): Boolean {
    val view = layoutInflater.inflate(R.layout.dialog_redemption_declined, null)
    val dialog = Dialog(this, R.style.TakeoverDialogTheme).apply {
        setContentView(view)
        setOnDismissListener { onDismissed() }
    }
    dialog.goEdgeToEdge()

    val sheet = view.findViewById<View>(R.id.declinedSheet)
    sheet.padForSystemBars(bottomExtraDp = 26f, applyTop = false)
    // Swallows taps so they do not reach the scrim behind and close it.
    sheet.isClickable = true

    view.findViewById<TextView>(R.id.declinedRefund).text =
        getString(R.string.redemption_refund_amount, result.refundedPoints)

    val reason = result.rejectionReason?.trim().orEmpty()
    view.findViewById<TextView>(R.id.declinedReason).text = reason.ifEmpty {
        getString(R.string.redemption_declined_reason_default)
    }

    view.findViewById<TextView>(R.id.declinedReviewed).text =
        settledAt(result.resolvedAtMillis)

    view.findViewById<View>(R.id.declinedDone).setOnClickListener { dialog.dismiss() }
    view.findViewById<View>(R.id.declinedClose).setOnClickListener { dialog.dismiss() }
    // The scrim closes the sheet, but not for the first couple of seconds.
    //
    // THIS DIALOG IS SHOWN EXACTLY ONCE - the settled timestamp is marked the
    // moment it appears - so a tap that lands here by accident does not just
    // close a sheet, it permanently costs the user the only explanation they
    // will get for why their payout was turned down. The sheet slides up
    // under wherever their finger already was, which is precisely when a
    // stray tap is likely, so the guard covers that window and then gets out
    // of the way. "Got it", the close button and Back are unaffected.
    val openedAt = SystemClock.elapsedRealtime()
    view.findViewById<View>(R.id.declinedScrim).setOnClickListener {
        if (SystemClock.elapsedRealtime() - openedAt >= SCRIM_GUARD_MILLIS) {
            dialog.dismiss()
        }
    }
    view.findViewById<View>(R.id.declinedRetry).setOnClickListener {
        dialog.dismiss()
        onRedeemAgain()
    }

    // How far the sheet has to travel is its own height, which is not known
    // until it has been laid out - so it starts INVISIBLE rather than merely
    // offset, and is placed and revealed in the same frame the slide begins.
    // Setting the offset after show() would let one frame draw it already in
    // position, which reads as a flash rather than a sheet coming up.
    view.alpha = 0f
    sheet.visibility = View.INVISIBLE
    dialog.show()

    view.animate().alpha(1f).setDuration(200L).start()
    sheet.post {
        sheet.translationY = sheet.height.toFloat()
        sheet.visibility = View.VISIBLE
        sheet.animate().translationY(0f).setDuration(380L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
    return true
}

// --------------------------------------------------------------------------
// shared
// --------------------------------------------------------------------------

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
private fun View.padForSystemBars(bottomExtraDp: Float, applyTop: Boolean = true) {
    val density = resources.displayMetrics.density
    val extra = (bottomExtraDp * density).toInt()
    val top = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            v.paddingLeft,
            if (applyTop) top + bars.top else top,
            v.paddingRight,
            extra + bars.bottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/** "Today, 21:04", or "2 Sep, 21:04" once it is not today any more. */
private fun AppCompatActivity.settledAt(millis: Long): String {
    val day = WalletFormat.day(this, millis)
    val time = android.text.format.DateFormat.getTimeFormat(this).format(Date(millis))
    return getString(R.string.redemption_detail_time, day, time)
}

private fun AppCompatActivity.shareWin(headline: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.redemption_paid_share_subject))
        putExtra(
            Intent.EXTRA_TEXT,
            getString(R.string.redemption_paid_share_body, headline)
        )
    }
    startActivity(Intent.createChooser(intent, getString(R.string.redemption_paid_share)))
}
