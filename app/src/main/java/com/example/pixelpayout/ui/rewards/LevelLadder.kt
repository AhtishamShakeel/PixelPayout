package com.example.pixelpayout.ui.rewards

import android.content.res.Resources
import androidx.annotation.DrawableRes
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.repository.UserRepository
import com.pixelpayout.R

/**
 * What each level is worth, assembled from the things that actually gate on
 * a level. Pure - no Firestore, no views - so the screen only has to draw it.
 *
 * Nothing here is a table of its own. Every rung is derived from a number
 * some other part of the system already enforces:
 *
 *   * level-up stars come from the curve document's `levelRewards`, which
 *     the server seeds from LEVEL_UP_POINTS and then reads back when it
 *     pays - so a console retune moves the ladder and the payout together,
 *   * the referral rung is placed from `referralUnlockXp`, the same figure
 *     readReferrerForUnlock tests,
 *   * the first-redeem rung from `config/redemption.firstRedeemMinLevel`,
 *     which validateRedemption re-reads on every claim,
 *   * game rungs from each catalogue document's own `minLevel`.
 *
 * That is the point of building it this way: a level ladder written down by
 * hand would be a second set of numbers, and it would be wrong the first time
 * any of the four above was retuned - while still looking authoritative.
 *
 * LEVELS THAT UNLOCK NOTHING ARE NOT LISTED. In practice that is now only
 * level 1 - every level from 2 up pays stars - so the ladder reads as the
 * whole climb rather than as a filtered view of it. The rule stays because
 * the reward table is console-editable: someone can empty a level, and a rung
 * promising nothing is worse than no rung. [Ladder.rungCount] against the
 * curve's max level is what tells the user when anything is missing.
 */
object LevelLadder {

    /**
     * Where a rung sits relative to the user.
     *
     * [UNCLAIMED] is a level already REACHED whose star bonus is still locked
     * behind a rewarded ad. It is a separate state rather than a flag on
     * REACHED because the two want opposite things from the user: one is
     * finished business, the other is the only thing on this screen there is
     * anything to do about.
     */
    enum class State { REACHED, UNCLAIMED, NEXT, LOCKED }

    data class Perk(@DrawableRes val icon: Int, val text: String)

    data class Rung(
        val level: Int,
        val xpRequired: Int,
        val state: State,
        val perks: List<Perk>,
        /**
         * Whether THIS is the rung the claim button would pay next.
         *
         * Rewards are released lowest-first, so of several unclaimed rungs
         * exactly one is available and the rest are queued behind it. Saying
         * so on the rungs themselves is what makes the rule visible instead
         * of merely enforced - the alternative is a user tapping claim,
         * getting level 2, and having to work out why they did not get the
         * level 5 they were looking at.
         */
        val isNextClaim: Boolean = false
    )

    data class Ladder(
        val rungs: List<Rung>,
        /**
         * Level-up stars already IN THE BALANCE - levels at or below the
         * user's, minus anything still waiting behind an ad. Reached and
         * unclaimed is not earned; counting it here would mean the figure
         * disagreed with the balance on Home.
         */
        val starsEarned: Int,
        /** Reached, promised, and still behind an ad. */
        val starsLocked: Int,
        /** Level-up stars still ahead. */
        val starsAhead: Int,
        /** The curve's top level, for "N of M levels pay out". */
        val maxLevel: Int,
        /** The level a claim would release next, or null when none is due. */
        val nextClaimLevel: Int?,
        /** What that next claim is worth. */
        val nextClaimStars: Int,
        /** How many levels are queued in total, this one included. */
        val pendingCount: Int
    ) {
        val rungCount: Int get() = rungs.size
    }

    /**
     * @param firstRedeemMinLevel null until the config read lands; the rung is
     *   simply absent until then rather than guessing at the default, which
     *   would put a level on screen that the console may have moved.
     */
    /**
     * @param pendingLevels levels already reached whose star bonus has not
     *   been released yet, straight from the user document. Everything about
     *   claiming on this screen is derived from it - which rungs are tagged,
     *   which one the button pays, and whether the header offers anything at
     *   all - so there is one source for the answer rather than a screen
     *   state that can drift out of step with the server's queue.
     */
    fun build(
        res: Resources,
        curve: UserRepository.LevelCurve,
        currentLevel: Int,
        firstRedeemMinLevel: Int?,
        games: List<RedemptionGame>,
        pendingLevels: List<Int> = emptyList()
    ): Ladder {
        val perksByLevel = mutableMapOf<Int, MutableList<Perk>>()
        fun add(level: Int, perk: Perk) {
            if (level < 1 || level > curve.maxLevel) return
            perksByLevel.getOrPut(level) { mutableListOf() }.add(perk)
        }

        curve.levelRewards.forEach { (level, points) ->
            add(level, Perk(R.drawable.ic_star, res.getString(R.string.level_perk_stars, points)))
        }

        // Placed by XP rather than by a level number, because that is how the
        // server states the rule. The wording quotes the XP figure too: the
        // level is reached a little before the threshold is, so naming only
        // the level would promise the payout slightly early.
        if (curve.referralUnlockXp > 0) {
            add(
                curve.levelForXp(curve.referralUnlockXp),
                Perk(
                    R.drawable.ic_users,
                    res.getString(R.string.level_perk_referral, curve.referralUnlockXp)
                )
            )
        }

        if (firstRedeemMinLevel != null) {
            add(
                firstRedeemMinLevel,
                Perk(R.drawable.ic_gift, res.getString(R.string.level_perk_first_redeem))
            )
        }

        // minLevel of 1 means no gate at all, so those games are not an
        // unlock and do not belong on the ladder.
        games.filter { it.minLevel > 1 }
            .sortedBy { it.name }
            .forEach { game ->
                add(
                    game.minLevel,
                    Perk(
                        R.drawable.ic_redeem,
                        res.getString(R.string.level_perk_game, game.name)
                    )
                )
            }

        val levels = perksByLevel.keys.sorted()
        val nextLevel = levels.firstOrNull { it > currentLevel }

        // Only levels actually reached can be waiting on a claim. Filtering
        // rather than trusting the list keeps a stale snapshot - one read
        // before a rollback, say - from tagging a rung the user is not on.
        val pending = pendingLevels.filter { it <= currentLevel }.sorted()
        val nextClaim = pending.firstOrNull()

        val rungs = levels.map { level ->
            Rung(
                level = level,
                xpRequired = curve.xpRequiredFor(level),
                state = when {
                    level <= currentLevel ->
                        if (pending.contains(level)) State.UNCLAIMED else State.REACHED
                    level == nextLevel -> State.NEXT
                    else -> State.LOCKED
                },
                perks = perksByLevel.getValue(level),
                isNextClaim = level == nextClaim
            )
        }

        val reached = curve.levelRewards.filterKeys { it <= currentLevel }
        val locked = reached.filterKeys { pending.contains(it) }.values.sum()
        val ahead = curve.levelRewards.filterKeys { it > currentLevel }.values.sum()

        return Ladder(
            rungs = rungs,
            starsEarned = reached.values.sum() - locked,
            starsLocked = locked,
            starsAhead = ahead,
            maxLevel = curve.maxLevel,
            nextClaimLevel = nextClaim,
            // From the curve, not from the ledger entry the server will
            // actually pay. They agree unless the table was retuned between
            // the level-up and the claim, and in that case the server's
            // figure wins - which is why the toast afterwards quotes what was
            // paid rather than repeating this.
            nextClaimStars = nextClaim?.let { curve.levelRewards[it] } ?: 0,
            pendingCount = pending.size
        )
    }
}
