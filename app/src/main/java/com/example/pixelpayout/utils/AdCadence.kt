package com.example.pixelpayout.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Decides when an interstitial is allowed to interrupt.
 *
 * ONE COUNTER FOR GAMES AND QUIZZES TOGETHER, not one each. A player
 * alternating between the two would otherwise trip both counters at half the
 * intended interval and see twice the ads - and from their side it is one
 * session of playing, not two separate budgets.
 *
 * PERSISTED, NOT HELD IN MEMORY. GamePlayActivity finishes after every run
 * and the app's process is killed routinely on the devices this is aimed at.
 * An in-memory counter would reset on both, so the ad would arrive at
 * genuinely random moments - sometimes never, sometimes twice in a row - and
 * no amount of tuning the interval would change that.
 *
 * The rules, in the order they are applied:
 *
 *   1. A rewarded ad just played -> no interstitial, and the counter does not
 *      advance. This is the important one. Stacking an interstitial behind
 *      the "double your XP" ad would spend our best impression setting up our
 *      worst, put two full-screen ads on one transition, and teach players
 *      that taking the offer costs them extra - so the double has to buy a
 *      skip. Somebody who doubles every single run sees no interstitials at
 *      all, which is the correct outcome: they are already watching more ads
 *      than anyone.
 *
 *      ASKED TWO WAYS, because the caller's answer alone was not enough. The
 *      `rewardedShown` flag is a field on the activity that finished, and it
 *      only knows about ads that activity itself showed. It therefore missed
 *      every rewarded ad watched at the same transition from somewhere else -
 *      a bonus attempt bought on the games list seconds before the run, a
 *      level reward released on the way back - and it was lost outright
 *      whenever the activity was recreated behind the ad, which a full-screen
 *      ad can easily cause. Both produced exactly what this rule exists to
 *      prevent: an interstitial immediately after a rewarded ad. So the
 *      timestamp written by [noteRewardedShown] is consulted as well, and it
 *      is persisted in the same place as the rest of the cadence, which is
 *      what makes it survive the recreation the flag does not.
 *   2. The first few completions on a fresh install are free. Early retention
 *      is worth more than three impressions.
 *   3. Otherwise every [EVERY_N_COMPLETIONS] completions, and never inside
 *      [MIN_GAP_MS] of the last one - a quiz can end quickly, and without a
 *      floor two fast activities can stack two interstitials inside a minute.
 *
 * THE SLOT IS SPENT ON AN IMPRESSION, NOT ON THE DECISION. Only
 * [noteInterstitialShown] resets the counter, and [AdHold] calls it from the
 * ad's own callback - so a slot that comes due and then finds no ad stays
 * open and is tried again at the next completion, rather than being silently
 * burned. Readiness therefore cannot be the final word here: an ad that
 * arrives DURING the hold still counts, which is the point of the hold.
 *
 * What is still asked here is whether an ad could plausibly arrive at all -
 * see [InterstitialAdManager.canServeWithin]. Without that, a unit that never
 * fills would leave the counter permanently past its threshold and charge the
 * player an 800ms pause on every single exit, forever, showing nothing.
 */
object AdCadence {

    private const val TAG = "AdCadence"
    private const val PREFS = "ad_cadence"

    private const val KEY_LIFETIME = "lifetime_completions"
    private const val KEY_SINCE_LAST = "completions_since_interstitial"
    private const val KEY_LAST_SHOWN_AT = "last_interstitial_at"
    private const val KEY_LAST_REWARDED_AT = "last_rewarded_at"

    /**
     * Show one every second activity finished.
     *
     * The economy allows 10 games plus 10 quizzes a day, and up to three
     * bought attempts on each - so 26 completions is a realistic hard day,
     * which at every second is about thirteen interstitials. That is a heavy
     * load for an app whose pitch is that we pay the player, and it is
     * deliberate: [MIN_GAP_MS] is what actually keeps it tolerable, because
     * two completions inside the floor cannot both pay.
     */
    private const val EVERY_N_COMPLETIONS = 2

    /** No two interstitials closer together than this, whatever the count. */
    private const val MIN_GAP_MS = 45_000L

    /** Completions at the start of a fresh install that are never interrupted. */
    private const val GRACE_COMPLETIONS = 3

    /**
     * How long a rewarded ad keeps an interstitial off the screen.
     *
     * Deliberately [MIN_GAP_MS], not a number of its own. A rewarded ad is a
     * full-screen ad, and the floor already says how close together two of
     * those may be - the only reason interstitials were being measured solely
     * against each other is that rewarded ads were not being recorded at all.
     * Measuring both against one floor is the same rule the player already
     * experiences, applied to every ad rather than to one kind of it.
     */
    private const val REWARDED_SUPPRESS_MS = MIN_GAP_MS

    /**
     * How far ahead [InterstitialAdManager] is asked to look when deciding
     * whether a slot is worth pausing for. Matches AdHold's hold, because
     * that is exactly the time a request would have to land in.
     */
    private const val HOLD_WINDOW_MS = 800L

    /**
     * Records a finished game or quiz and answers whether an interstitial is
     * DUE.
     *
     * Both halves are one call on purpose. As two - "count this" and "should
     * I?" - every caller would have to get the order right and remember to do
     * both, and the rewarded-ad suppression in particular is the kind of rule
     * that gets dropped at one call site and noticed months later in the
     * revenue split.
     *
     * @param rewardedShown whether the player watched a rewarded ad as part
     *   of finishing this activity. Suppresses the interstitial outright, as
     *   does a recent [noteRewardedShown] - see rule 1 above for why both.
     */
    fun onActivityCompleted(context: Context, rewardedShown: Boolean): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (rewardedShown || watchedRewardedRecently(prefs)) {
            Log.d(TAG, "Rewarded ad shown - interstitial suppressed, counter held")
            return false
        }

        val lifetime = prefs.getInt(KEY_LIFETIME, 0) + 1
        val sinceLast = prefs.getInt(KEY_SINCE_LAST, 0) + 1
        prefs.edit()
            .putInt(KEY_LIFETIME, lifetime)
            .putInt(KEY_SINCE_LAST, sinceLast)
            .apply()

        if (lifetime <= GRACE_COMPLETIONS) {
            Log.d(TAG, "Completion $lifetime is within the new-install grace period")
            return false
        }

        if (sinceLast < EVERY_N_COMPLETIONS) return false

        val now = System.currentTimeMillis()
        val lastShownAt = prefs.getLong(KEY_LAST_SHOWN_AT, 0L)
        val elapsed = now - lastShownAt
        // A stamp in the future means the device clock moved backwards. Left
        // alone that would block interstitials until real time caught up,
        // which could be days; re-stamping to now costs at most one skipped
        // slot.
        if (elapsed < 0) {
            prefs.edit().putLong(KEY_LAST_SHOWN_AT, now).apply()
            return false
        }
        if (lastShownAt != 0L && elapsed < MIN_GAP_MS) {
            Log.d(TAG, "Slot due but only ${elapsed}ms since the last interstitial")
            return false
        }

        // Asked last, and deliberately NOT a readiness test - an ad that
        // lands during the hold still counts. This only refuses the pause
        // when no request can even go out during it.
        if (!InterstitialAdManager.getInstance().canServeWithin(HOLD_WINDOW_MS)) {
            Log.d(TAG, "Slot due but no ad cached and none can be requested yet")
            return false
        }

        return true
    }

    /**
     * Records that a rewarded ad was put on screen.
     *
     * Called from AdManager's onAdShowedFullScreenContent rather than from
     * the seven places that ask for a rewarded ad, so a control added later
     * cannot forget to declare itself and end up followed by an interstitial.
     *
     * ON DISPLAY, NOT ON THE REWARD, and the difference cuts both ways. An ad
     * closed after two seconds is still a rendered impression and is still
     * paid for, so treating it as though no ad happened understates what that
     * transition already earned. It is also still a full-screen ad the player
     * has just shut, which is the worst moment available to open another one:
     * their finger is already moving toward a close button, and that is
     * precisely the stray tap AdHold's pause exists to prevent.
     *
     * The counter is untouched. This suppresses, it does not consume; the
     * slot stays open for the next completion, exactly as `rewardedShown`
     * already did.
     */
    fun noteRewardedShown(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REWARDED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Whether a rewarded ad landed close enough to count as this transition.
     *
     * A stamp in the future means the device clock moved backwards, and is
     * read as "not recent" rather than blocking interstitials until real time
     * catches up. The cost of that is at most one extra interstitial, and the
     * caller's own `rewardedShown` still covers the case this exists for.
     */
    private fun watchedRewardedRecently(prefs: SharedPreferences): Boolean {
        val at = prefs.getLong(KEY_LAST_REWARDED_AT, 0L)
        if (at == 0L) return false
        return (System.currentTimeMillis() - at) in 0 until REWARDED_SUPPRESS_MS
    }

    /**
     * Consumes the slot, an ad having actually been shown.
     *
     * Driven by the interstitial's own callback rather than by the decision
     * above, so the counter only resets on a real impression. A no-fill at
     * show time therefore costs the player a short hold and nothing more: the
     * slot stays open and the next completion tries again.
     */
    fun noteInterstitialShown(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SINCE_LAST, 0)
            .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
            .apply()
    }
}
