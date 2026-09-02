package com.example.pixelpayout.utils

import android.app.Dialog
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.pixelpayout.data.model.LevelUpEvent
import com.pixelpayout.R

/**
 * Announces a level-up the same way regardless of which activity earned it,
 * so a milestone bonus never goes unmentioned just because it came from a
 * game rather than a quiz.
 *
 * [LevelUpEvent.milestonePoints] is what the level-up LOCKED, not what it
 * paid: reaching the level earns the stars and a rewarded ad releases them
 * (see claimLevelReward). So the wording says the stars are waiting and where
 * to collect them.
 *
 * A TOAST HERE, NOT A DIALOG. The level-up lands while the player is still
 * inside the game or the quiz - mid-run in a game whose XP crossed the
 * threshold before the last frame - and a modal offer there interrupts the
 * thing they are doing. The offer is made by [showPendingLevelRewards]
 * instead, once they are back on a normal app screen.
 */
fun Context.showLevelUp(event: LevelUpEvent) {
    val message = if (event.milestonePoints > 0) {
        getString(R.string.level_up_with_bonus, event.level, event.milestonePoints)
    } else {
        getString(R.string.level_up, event.level)
    }
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/**
 * The celebration, shown once the player is back on an ordinary app screen.
 *
 * IT DOES NOT CLAIM ANYTHING. An earlier version offered the ad directly and
 * quoted the level just reached, which was wrong whenever anything older was
 * still owed: rewards are released oldest first, so a dialog headed "Level 10"
 * would watch an ad and pay level 6. Rather than explain that in a dialog,
 * this states the whole position - every level still owed, and what they come
 * to in total - and sends the player to the screen where they are released one
 * at a time, in an order that screen shows them.
 *
 * @param level the level just reached, for the headline.
 * @param pendingLevels every level still owed, lowest first.
 * @param rewards the published `levelRewards` table, for the amounts.
 * @return whether the dialog was actually shown. False when the curve has not
 *   landed yet and the total would be a lie; the caller retries when it does.
 */
fun AppCompatActivity.showPendingLevelRewards(
    level: Int,
    pendingLevels: List<Int>,
    rewards: Map<Int, Int>,
    onDismissed: () -> Unit,
    onClaim: () -> Unit
): Boolean {
    if (pendingLevels.isEmpty() || isFinishing || isDestroyed) return false

    val total = pendingLevels.sumOf { rewards[it] ?: 0 }
    // No curve, no figure. Showing "+0 ★" would be worse than showing nothing
    // and asking again in a moment.
    if (total <= 0) return false

    val view = layoutInflater.inflate(R.layout.dialog_ad_claim, null)
    val dialog = Dialog(this, R.style.CustomDialogTheme).apply {
        setContentView(view)
        setOnDismissListener { onDismissed() }
    }

    view.findViewById<View>(R.id.adClaimDot).setBackgroundResource(R.drawable.bg_dot_stars)
    view.findViewById<TextView>(R.id.adClaimTitle).text =
        getString(R.string.level_up_dialog_title, level)
    view.findViewById<TextView>(R.id.adClaimReward).setStarText(
        getString(R.string.level_claim_amount, total)
    )

    // The range rather than a list of every level: a player who has left this
    // alone for a while can be owed a dozen, and "6, 7, 8, 9, 10, 11..." stops
    // being readable long before it stops being accurate.
    view.findViewById<TextView>(R.id.adClaimMessage).text = if (pendingLevels.size == 1) {
        getString(R.string.level_up_dialog_message_one, pendingLevels.first())
    } else {
        getString(
            R.string.level_up_dialog_message_many,
            pendingLevels.size,
            pendingLevels.first(),
            pendingLevels.last()
        )
    }

    // The shared layout is labelled for the streak claim, the only other thing
    // that uses it. Both buttons are relabelled rather than the layout being
    // copied: nothing is claimed from here, so "Watch ad" would be a promise
    // this dialog does not keep.
    view.findViewById<TextView>(R.id.adClaimCancel).apply {
        setText(R.string.level_up_dialog_later)
        setOnClickListener { dialog.dismiss() }
    }
    view.findViewById<TextView>(R.id.adClaimWatch).apply {
        setText(R.string.level_up_dialog_claim)
        setOnClickListener {
            dialog.dismiss()
            onClaim()
        }
    }

    dialog.show()
    return true
}
