package com.example.pixelpayout.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.example.pixelpayout.config.AppConfig

/**
 * The rewarded ads every "watch this to get that" control draws from.
 *
 * A SMALL POOL, NOT ONE AD. Several places want a rewarded ad - the streak
 * claim, the goal bonus, the "+1 attempt" pill on both Games and Quizzes, and
 * the double-XP offer on both results screens - and holding a single cached ad
 * between them meant whoever went first emptied the cache for everyone else.
 * With attempts and XP now buyable with ads, "no ad ready" is not a cosmetic
 * problem: it is a control the economy depends on, greyed out.
 *
 * REQUESTS ARE PACED, AND THAT IS THE MOST IMPORTANT THING HERE. AdMob keeps
 * its own client-side limiter and answers a unit that has failed repeatedly
 * with "Too many recently failed requests... you must wait a few seconds"
 * (code 1) instead of fetching anything. That failure then feeds the retry
 * path, which asks again, which trips the limiter again - a loop that looks
 * exactly like no fill from outside and guarantees it from inside. Everything
 * below funnels through [nextAllowedAt] so no caller, retry or screen change
 * can put requests out faster than the network will accept them:
 *
 *   * ONE REQUEST AT A TIME. Filling a pool of two used to mean two
 *     simultaneous requests, which reached the limiter's threshold twice as
 *     fast. The pool now fills one ad after another.
 *   * A FLOOR BETWEEN REQUESTS, whoever asks. Screens call loadRewardedAd
 *     from onViewCreated, so switching tabs used to fire a fresh pair every
 *     time; those calls are now a request only if the floor has elapsed.
 *   * OPENING A SCREEN NO LONGER RESETS THE BACKOFF. That was added so a user
 *     returning after a network blip would not sit out a long delay, and it
 *     removed the only brake on the loop above. Only a real tap may shorten a
 *     wait now - see [onUserWantsAd] - and not below the floor.
 *   * A RATE-LIMIT REFUSAL IS NOT A FILL FAILURE. Code 1 means we asked too
 *     fast, so it sets its own longer cooldown that nothing may override.
 *
 * The other failure modes this handles, all of which look identical to the
 * user - a greyed-out button:
 *
 *   * THE REPLACEMENT STARTED TOO LATE. It was requested from
 *     onAdDismissedFullScreenContent, so the refill only began once the user
 *     had finished watching. It now starts at SHOW time.
 *   * ADS GO STALE. A rewarded ad expires roughly an hour after it loads, so
 *     one cached before a long idle period fails at the moment it is needed.
 *   * A LOAD THAT NEVER CAME BACK KILLED THE POOL PERMANENTLY. The in-flight
 *     count was only ever decremented from the two load callbacks, so a
 *     request that never called back left it elevated forever and topUp's
 *     "enough already on order" test read as satisfied for the rest of the
 *     process. In-flight requests now carry a start time and are abandoned
 *     after [LOAD_TIMEOUT_MS].
 */
class AdManager private constructor() {

    /** A loaded ad and when it arrived, because rewarded ads expire. */
    private class CachedAd(val ad: RewardedAd, val loadedAtMillis: Long)

    private val pool = ArrayDeque<CachedAd>()

    /**
     * Requests already out, keyed by an id, valued by when they started.
     *
     * A map rather than a bare counter SO THAT A LOST REQUEST CAN BE
     * RECOGNISED. A counter can only be decremented by a callback, which is
     * exactly what a lost request never delivers; a start time can be checked
     * against the clock by anyone. See [pruneStuckLoads].
     */
    private val inFlight = HashMap<Long, Long>()
    private var nextLoadId = 0L

    /**
     * The single gate every request passes. No load goes out before this.
     *
     * Written by three things that must not be able to undercut each other -
     * the minimum spacing between requests, the failure backoff, and the
     * rate-limit cooldown - and each only ever pushes it later, never earlier.
     * One field rather than three checks so a new caller cannot be added that
     * happens to consult two of them.
     */
    private var nextAllowedAt = 0L

    /**
     * A rate-limit refusal's cooldown, held separately because it is the one
     * wait a user tap may NOT shorten. Asking again inside it cannot produce
     * an ad - only another refusal.
     */
    private var rateLimitedUntil = 0L

    private var lastRequestAt = 0L

    private var adAvailabilityCallback: ((Boolean) -> Unit)? = null

    /**
     * Kept so the pool can refill itself without a caller. Application
     * context only - this object outlives every Activity that asks it for an
     * ad.
     */
    private var appContext: Context? = null

    /**
     * Two handlers, not one. The load queue gets cancelled and rescheduled
     * when a tap shortens a wait; the wait poller must survive that, because
     * it belongs to a user who is currently looking at a spinner.
     */
    private val loadHandler = Handler(Looper.getMainLooper())
    private val waitHandler = Handler(Looper.getMainLooper())

    private var loadScheduled = false
    private var retryAttempt = 0

    fun setAdAvailabilityCallback(callback: (Boolean) -> Unit) {
        adAvailabilityCallback = callback
        callback(isRewardedAdReady())
    }

    /**
     * Whether an ad can be shown right now.
     *
     * Deliberately a poll rather than another callback: setAdAvailabilityCallback
     * holds ONE listener, so a second screen registering silently unsubscribes
     * the first. Screens that gate a control on availability read this on a
     * tick they already run.
     */
    fun isRewardedAdReady(): Boolean {
        sweep()
        return pool.isNotEmpty()
    }

    /**
     * Tops the pool up if the pacer allows it.
     *
     * Called from app start and from screen setup, which happen often - every
     * tab switch recreates a fragment - so this is deliberately CHEAP AND
     * FREQUENTLY A NO-OP. It used to reset the failure backoff on the grounds
     * that a screen opening means somebody wants an ad soon; that turned every
     * tab switch into an immediate pair of requests and drove the unit into
     * AdMob's rate limiter. Wanting an ad soon is not the same as asking for
     * one, and only asking earns a shortened wait.
     */
    fun loadRewardedAd(context: Context) {
        appContext = context.applicationContext
        sweep()
        topUp()
    }

    /**
     * Signals that a user has actually tapped something needing an ad.
     *
     * This is the one caller allowed to cut a pending backoff short, because
     * it is the one place a person is waiting on the answer. It cuts to the
     * request floor rather than to zero, and it will not touch a rate-limit
     * cooldown at all - inside one, asking again cannot return an ad, so
     * "trying harder" would only extend the block.
     */
    private fun onUserWantsAd() {
        val now = SystemClock.uptimeMillis()
        if (now < rateLimitedUntil) {
            Log.d(TAG, "User wants an ad but the unit is rate limited - not asking")
            return
        }

        retryAttempt = 0
        nextAllowedAt = minOf(nextAllowedAt, lastRequestAt + MIN_REQUEST_INTERVAL_MS)
        loadHandler.removeCallbacksAndMessages(null)
        loadScheduled = false
        topUp()
    }

    /** Drops what is stale and abandons what is lost. */
    private fun sweep() {
        dropExpired()
        pruneStuckLoads()
    }

    /**
     * Issues at most ONE request, and only when the pacer allows.
     *
     * One at a time rather than filling every empty slot at once: a pool of
     * two meant two simultaneous requests, which reaches the rate limiter's
     * threshold twice as quickly for no benefit, since the second ad is not
     * needed until the first has been watched. onAdLoaded calls back here, so
     * the pool still fills - just in sequence, one request per interval.
     */
    private fun topUp() {
        val context = appContext ?: return
        if (pool.size >= POOL_SIZE) return
        if (inFlight.isNotEmpty()) return
        if (loadScheduled) return

        val now = SystemClock.uptimeMillis()
        if (now < nextAllowedAt) {
            scheduleLoad(nextAllowedAt - now)
            return
        }

        loadOne(context)
    }

    private fun scheduleLoad(delayMs: Long) {
        if (loadScheduled) return
        if (appContext == null) return

        loadScheduled = true
        Log.d(TAG, "Next rewarded request in ${delayMs}ms")
        loadHandler.postDelayed({
            loadScheduled = false
            sweep()
            topUp()
        }, delayMs.coerceAtLeast(0))
    }

    private fun loadOne(context: Context) {
        val loadId = nextLoadId++
        val now = SystemClock.uptimeMillis()
        inFlight[loadId] = now
        lastRequestAt = now
        // Booked BEFORE the request rather than in the callback, so the floor
        // holds even for a request that never answers.
        nextAllowedAt = maxOf(nextAllowedAt, now + MIN_REQUEST_INTERVAL_MS)

        Log.d(TAG, "Loading rewarded ad (pool=${pool.size})")

        RewardedAd.load(
            context,
            AppConfig.ADMOB_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    // May already be gone if this request was written off as
                    // stuck and then turned up late. The ad is still good, so
                    // it is kept - only the bookkeeping is discarded.
                    inFlight.remove(loadId)
                    pool.addLast(CachedAd(ad, System.currentTimeMillis()))
                    retryAttempt = 0
                    Log.d(TAG, "Rewarded ad loaded (pool=${pool.size})")
                    adAvailabilityCallback?.invoke(true)
                    // Fills the next slot, paced by the same floor.
                    topUp()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    inFlight.remove(loadId)
                    // Logged in full because this is the only place the reason
                    // exists. code 3 is no fill, 2 is network, 1 here is our
                    // own request rate - and "the button does nothing" looks
                    // identical for all of them.
                    Log.w(
                        TAG,
                        "Rewarded ad failed to load: code=${error.code} " +
                            "domain=${error.domain} message=${error.message}"
                    )
                    adAvailabilityCallback?.invoke(pool.isNotEmpty())

                    if (error.code == ERROR_CODE_TOO_MANY_REQUESTS) {
                        // Not a fill problem - it is us. Nothing can be
                        // fetched until this passes, so the cooldown is set
                        // here and honoured even against a user tap.
                        val until = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
                        rateLimitedUntil = maxOf(rateLimitedUntil, until)
                        nextAllowedAt = maxOf(nextAllowedAt, until)
                        Log.w(TAG, "Rate limited - holding requests for ${RATE_LIMIT_COOLDOWN_MS}ms")
                        scheduleLoad(RATE_LIMIT_COOLDOWN_MS)
                        return
                    }

                    scheduleRetry()
                }
            }
        )
    }

    /**
     * Writes off requests that never called back.
     *
     * The SDK does not promise a callback for every load, and without this a
     * single lost one costs a pool slot for the life of the process - see the
     * note on the class. Abandoning it here is safe in both directions: if it
     * was truly lost, the slot is freed; if it turns up afterwards, onAdLoaded
     * still banks the ad and only the bookkeeping entry is missing.
     */
    private fun pruneStuckLoads() {
        if (inFlight.isEmpty()) return
        val cutoff = SystemClock.uptimeMillis() - LOAD_TIMEOUT_MS
        val lost = inFlight.entries.filter { it.value < cutoff }.map { it.key }
        if (lost.isEmpty()) return

        lost.forEach { inFlight.remove(it) }
        Log.w(TAG, "Abandoned ${lost.size} rewarded load(s) that never called back")
    }

    /**
     * Drops ads that have aged out.
     *
     * An expired rewarded ad does not announce itself - it simply fails at
     * show(), which the user experiences as the offer breaking at the moment
     * they accepted it. Cheaper to notice here and load a fresh one.
     */
    private fun dropExpired() {
        val cutoff = System.currentTimeMillis() - AD_TTL_MS
        var dropped = 0
        while (pool.isNotEmpty() && pool.first().loadedAtMillis < cutoff) {
            pool.removeFirst()
            dropped++
        }
        if (dropped > 0) {
            Log.d(TAG, "Dropped $dropped expired rewarded ad(s)")
        }
    }

    /**
     * Backs off after a failure: 5s, 10s, 20s... to a minute.
     *
     * Starts at five seconds rather than two. Two was below the interval
     * AdMob's own limiter tolerates on a failing unit, so the backoff's first
     * few steps were themselves part of what tripped it - the retry that was
     * meant to recover from a no-fill was instead converting it into a rate
     * limit.
     */
    private fun scheduleRetry() {
        retryAttempt++
        val backoff = (RETRY_BASE_MS shl (retryAttempt - 1).coerceIn(0, 5))
            .coerceAtMost(MAX_RETRY_DELAY_MS)

        nextAllowedAt = maxOf(nextAllowedAt, SystemClock.uptimeMillis() + backoff)
        scheduleLoad(backoff)
    }

    /**
     * Shows an ad, waiting briefly for one if the pool happens to be empty.
     *
     * The pool makes that rare, but rare is not never - a burst of claims, a
     * load that failed and is mid-backoff, a cold launch. Telling the user
     * "try again in a moment" at that point turns their tap into nothing,
     * which for a control the economy sells is the worst possible answer.
     * Waiting a few seconds converts almost all of those into a slightly slow
     * success instead.
     *
     * Gives up at [WAIT_TIMEOUT_MS] rather than hanging: past that it is not
     * a momentary gap, it is no fill, and the user should be told.
     */
    fun showRewardedAdWhenReady(
        activity: Activity,
        onRewarded: () -> Unit,
        onAdClosed: () -> Unit,
        onAdFailedToShow: () -> Unit
    ) {
        if (isRewardedAdReady()) {
            showRewardedAd(activity, onRewarded, onAdClosed, onAdFailedToShow)
            return
        }

        Log.d(TAG, "Pool empty on demand - waiting up to ${WAIT_TIMEOUT_MS}ms")
        appContext = activity.applicationContext
        // A tap is the strongest signal that somebody wants an ad, and the
        // only one allowed to shorten a pending wait.
        onUserWantsAd()
        awaitAd(
            activity = activity,
            deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS,
            onRewarded = onRewarded,
            onAdClosed = onAdClosed,
            onAdFailedToShow = onAdFailedToShow
        )
    }

    private fun awaitAd(
        activity: Activity,
        deadline: Long,
        onRewarded: () -> Unit,
        onAdClosed: () -> Unit,
        onAdFailedToShow: () -> Unit
    ) {
        // The user can leave while we wait. Showing an ad over a dead Activity
        // throws, and there is nobody left to reward anyway.
        if (activity.isFinishing || activity.isDestroyed) return

        if (isRewardedAdReady()) {
            showRewardedAd(activity, onRewarded, onAdClosed, onAdFailedToShow)
            return
        }

        if (SystemClock.uptimeMillis() >= deadline) {
            Log.w(TAG, "Gave up waiting for a rewarded ad")
            onAdFailedToShow()
            return
        }

        waitHandler.postDelayed({
            awaitAd(activity, deadline, onRewarded, onAdClosed, onAdFailedToShow)
        }, WAIT_POLL_MS)
    }

    fun showRewardedAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onAdClosed: () -> Unit,
        onAdFailedToShow: () -> Unit
    ) {
        appContext = activity.applicationContext
        sweep()

        val cached = pool.removeFirstOrNull()
        if (cached == null) {
            Log.w(TAG, "showRewardedAd with an empty pool")
            onAdFailedToShow()
            topUp()
            return
        }

        // Refilled NOW rather than when the ad closes. The user is about to
        // spend fifteen seconds or more watching this one, which is far more
        // time than a load needs - so by the time they are back and reaching
        // for the button again, the next ad is already waiting.
        topUp()

        cached.ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${error.code} ${error.message}")
                onAdFailedToShow()
                // The ad is spent either way; it was already taken from the
                // pool, and topUp above has its replacement on the way.
            }
        }

        cached.ad.show(activity) { onRewarded() }
    }

    companion object {
        private const val TAG = "AdManager"

        /**
         * ERROR_CODE_INVALID_REQUEST, which is also what the SDK returns for
         * "Too many recently failed requests for ad unit ID". Named for what
         * it means to us, since that is the only form of it this code path
         * produces - the unit id is a constant and cannot be malformed.
         */
        private const val ERROR_CODE_TOO_MANY_REQUESTS = 1

        /**
         * How many ads to keep ready.
         *
         * Two, because the worst realistic run is a user buying bonus
         * attempts back to back: one is showing while the next is loading,
         * and the spare covers a load that fails and has to back off. Filled
         * one at a time - see topUp.
         */
        private const val POOL_SIZE = 2

        /**
         * The minimum gap between two requests for this unit, whoever asks.
         *
         * AdMob's own message on a failing unit is "you must wait a few
         * seconds before making another ad request", so this is that, taken
         * literally and applied to every caller rather than only to retries.
         */
        private const val MIN_REQUEST_INTERVAL_MS = 5_000L

        /**
         * How long to stop asking entirely after a rate-limit refusal.
         *
         * Longer than the request floor because the limiter is already
         * unhappy: the goal is to let it forget, not to test it.
         */
        private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

        /**
         * Conservative against the roughly one hour a rewarded ad stays
         * valid - better to throw away a good ad than to offer one that
         * cannot be shown.
         */
        private const val AD_TTL_MS = 50 * 60 * 1000L

        /**
         * How long a request may be outstanding before its slot is reclaimed.
         *
         * Generously past any real load, because this is not a deadline, it
         * is a leak detector.
         */
        private const val LOAD_TIMEOUT_MS = 30_000L

        private const val RETRY_BASE_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        /**
         * How long a tap will wait for an ad before admitting there is none.
         *
         * Fill is the scarce thing here, and the alternative to waiting is
         * telling somebody who asked for an ad that they cannot have one -
         * which costs us the impression and them the reward. The label says
         * "Finding an ad" for exactly this reason.
         */
        private const val WAIT_TIMEOUT_MS = 8_000L
        private const val WAIT_POLL_MS = 250L

        private var instance: AdManager? = null

        fun getInstance(): AdManager {
            if (instance == null) {
                instance = AdManager()
            }
            return instance!!
        }
    }
}
