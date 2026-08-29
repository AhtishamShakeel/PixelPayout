package com.example.pixelpayout.ui.redemption

import android.content.Context
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.ServerClock
import com.pixelpayout.R
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Wording and number formatting shared by the Wallet screen, its sheet and
 * its two lists.
 *
 * It lives apart from all three because the same ledger entry is described in
 * more than one place, and a label that drifted between them would look like
 * two different events.
 */
object WalletFormat {

    /** Grouped digits: "1,000" is read at a glance, "1000" is counted. */
    private val NUMBER: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

    fun number(value: Int): String = NUMBER.format(value)

    /**
     * What one ledger line is called.
     *
     * Built here from `source` and `metadata` rather than read from the
     * document: the ledger records what happened, not how to word it, so a
     * stored label would be frozen in whatever wording it had when written.
     *
     * A REDEMPTION carrying positive points is a refund, not a purchase - the
     * refund path writes the mirror image of the original debit, and calling
     * both "Redeemed" would show a user their stars coming back under the
     * label for spending them.
     */
    fun label(context: Context, entry: UserRepository.LedgerEntry): String = when {
        entry.source == SOURCE_REDEMPTION && entry.points > 0 ->
            context.getString(R.string.activity_refunded)

        entry.source == SOURCE_REDEMPTION -> entry.detail
            ?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.activity_redeemed, it) }
            ?: context.getString(R.string.activity_redeemed_generic)

        else -> context.getString(
            when (entry.source) {
                "QUIZ" -> R.string.activity_source_quiz
                "GAME" -> R.string.activity_source_game
                "STREAK" -> R.string.activity_source_streak
                "REFERRAL_REFEREE", "REFERRAL_REFERRER" -> R.string.activity_source_referral
                "LEVEL_UP" -> R.string.activity_source_level_up
                "MISSION" -> R.string.activity_source_goals
                "OFFERWALL", "SURVEY", "SPONSORED_APP" -> R.string.activity_source_offerwall
                "ADMIN_GRANT" -> R.string.activity_source_admin
                else -> R.string.activity_source_other
            }
        )
    }

    /** The icon beside a ledger line. Direction first, then source. */
    fun icon(entry: UserRepository.LedgerEntry): Int = when {
        entry.points < 0 -> R.drawable.ic_arrow_up_right
        entry.source == "GAME" -> R.drawable.ic_game
        entry.source == "QUIZ" -> R.drawable.ic_quiz
        entry.source.startsWith("REFERRAL") -> R.drawable.ic_users
        entry.source == "STREAK" -> R.drawable.ic_bolt
        else -> R.drawable.ic_star
    }

    /**
     * Relative for the last two days, absolute after that.
     *
     * "3 days ago" reads as vaguer than a date the moment it stops being
     * today or yesterday, so it stops there rather than counting upward.
     */
    fun day(context: Context, millis: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = millis }

        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

        if (sameDay(now, then)) return context.getString(R.string.activity_today)

        now.add(Calendar.DAY_OF_YEAR, -1)
        if (sameDay(now, then)) return context.getString(R.string.activity_yesterday)

        return android.text.format.DateFormat.format("d MMM", millis).toString()
    }

    /**
     * How long a payout is promised to take, at the outside.
     *
     * Ours, not something the server enforces - approval is a human step - so
     * once the window passes the countdown stops and the order says it is in
     * review. A timer that has run out, or gone negative, is worse than no
     * timer at all.
     *
     * Lives here rather than in either screen because Home and the Orders tab
     * both make this promise, and two copies of a number the user is holding
     * us to is one copy too many.
     */
    const val PAYOUT_TARGET_MILLIS = 48L * 60L * 60L * 1000L

    /** Hours until the last one, then minutes - "41h", "35m". */
    fun remainingLabel(context: Context, remainingMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
        return if (hours >= 1) {
            context.getString(R.string.pending_redeem_hours, hours)
        } else {
            context.getString(
                R.string.pending_redeem_minutes,
                TimeUnit.MILLISECONDS.toMinutes(remainingMs).coerceAtLeast(1)
            )
        }
    }

    /**
     * The line under a pending order: how long is left of the 48 hours, or
     * that it is past the target and being looked at by a person.
     *
     * [requestedAtMillis] null means the server timestamp has not landed yet
     * - a write is in flight - so it says nothing rather than counting down
     * from a time it invented.
     */
    fun payoutEta(context: Context, requestedAtMillis: Long?): String? {
        if (requestedAtMillis == null) return null
        val remaining = requestedAtMillis + PAYOUT_TARGET_MILLIS - ServerClock.now()
        return if (remaining > 0) {
            context.getString(R.string.order_eta, remainingLabel(context, remaining))
        } else {
            context.getString(R.string.order_eta_overdue)
        }
    }

    private const val SOURCE_REDEMPTION = "REDEMPTION"
}
