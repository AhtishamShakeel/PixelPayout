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

    /** Where a rung sits relative to the user. */
    enum class State { REACHED, NEXT, LOCKED }

    data class Perk(@DrawableRes val icon: Int, val text: String)

    data class Rung(
        val level: Int,
        val xpRequired: Int,
        val state: State,
        val perks: List<Perk>
    )

    data class Ladder(
        val rungs: List<Rung>,
        /** Level-up stars already paid, from levels at or below the user's. */
        val starsEarned: Int,
        /** Level-up stars still ahead. */
        val starsAhead: Int,
        /** The curve's top level, for "N of M levels pay out". */
        val maxLevel: Int
    ) {
        val rungCount: Int get() = rungs.size
    }

    /**
     * @param firstRedeemMinLevel null until the config read lands; the rung is
     *   simply absent until then rather than guessing at the default, which
     *   would put a level on screen that the console may have moved.
     */
    fun build(
        res: Resources,
        curve: UserRepository.LevelCurve,
        currentLevel: Int,
        firstRedeemMinLevel: Int?,
        games: List<RedemptionGame>
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

        val rungs = levels.map { level ->
            Rung(
                level = level,
                xpRequired = curve.xpRequiredFor(level),
                state = when {
                    level <= currentLevel -> State.REACHED
                    level == nextLevel -> State.NEXT
                    else -> State.LOCKED
                },
                perks = perksByLevel.getValue(level)
            )
        }

        val earned = curve.levelRewards.filterKeys { it <= currentLevel }.values.sum()
        val ahead = curve.levelRewards.filterKeys { it > currentLevel }.values.sum()

        return Ladder(
            rungs = rungs,
            starsEarned = earned,
            starsAhead = ahead,
            maxLevel = curve.maxLevel
        )
    }
}
