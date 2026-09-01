package com.example.pixelpayout.utils

import android.content.Context
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
 *   2. The first few completions on a fresh install are free. Early retention
 *      is worth more than three impressions.
 *   3. Otherwise every [EVERY_N_COMPLETIONS] completions, and never inside
 *      [MIN_GAP_MS] of the last one - a quiz can end quickly, and without a
 *      floor two fast activities can stack two interstitials inside a minute.
 *
 * A slot that cannot be spent - too soon, or no ad in the cache - is NOT
 * consumed: the counter keeps climbing and the ad shows at the next
 * completion instead of being pushed a full interval away.
 *
 * READINESS IS ASKED HERE, before the slot is committed. Deferring it to the
 * moment of display costs the player a pause in front of an ad that may not
 * exist: with no fill the counter never resets, so every completion from the
 * second onward reads as due and every exit pays for a wait that ends in
 * nothing. Asking first means a no-fill is invisible - the player simply
 * carries on - and the slot stays open for the next completion.
 */
object AdCadence {

    private const val TAG = "AdCadence"
    private const val PREFS = "ad_cadence"

    private const val KEY_LIFETIME = "lifetime_completions"
    private const val KEY_SINCE_LAST = "completions_since_interstitial"
    private const val KEY_LAST_SHOWN_AT = "last_interstitial_at"

    /**
     * Show one every third activity finished.
     *
     * The economy allows 10 games plus 10 quizzes a day, and up to three
     * bought attempts on each - so 26 completions is a realistic hard day.
     * One interstitial per completion would be 26 full-screen ads imposed on
     * somebody whose reason for being here is that we pay THEM; a third of
     * that is about six, which is a normal ad load for a casual game.
     */
    private const val EVERY_N_COMPLETIONS = 3

    /**
     * No two interstitials closer together than this, whatever the count.
     *
     * 50s, not the 90 this started at. A quiz attempt is a single question on
     * a countdown, so a normal player finishes three of them in well under two
     * minutes - and at 90s the floor, not the every-third rule, was deciding
     * when ads appeared. That is the wrong knob in charge: the count is the
     * policy, and this is only here to stop two activities finishing back to
     * back from stacking two full-screen ads inside a minute.
     */
    private const val MIN_GAP_MS = 50_000L

    /** Completions at the start of a fresh install that are never interrupted. */
    private const val GRACE_COMPLETIONS = 3

    /**
     * Records a finished game or quiz and answers whether to show an
     * interstitial now.
     *
     * Both halves are one call on purpose. As two - "count this" and "should
     * I?" - every caller would have to get the order right and remember to do
     * both, and the rewarded-ad suppression in particular is the kind of rule
     * that gets dropped at one call site and noticed months later in the
     * revenue split.
     *
     * @param rewardedShown whether the player watched a rewarded ad as part
     *   of finishing this activity. Suppresses the interstitial outright.
     */
    fun onActivityCompleted(context: Context, rewardedShown: Boolean): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (rewardedShown) {
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

        // Asked last, and only once everything else has passed, because a
        // slot spent on an ad that does not exist is an interval of silence
        // for no impression - and, worse, a wait the player sits through for
        // nothing.
        if (!InterstitialAdManager.getInstance().isReady()) {
            Log.d(TAG, "Slot due but no interstitial cached")
            return false
        }

        prefs.edit()
            .putInt(KEY_SINCE_LAST, 0)
            .putLong(KEY_LAST_SHOWN_AT, now)
            .apply()
        return true
    }
}
