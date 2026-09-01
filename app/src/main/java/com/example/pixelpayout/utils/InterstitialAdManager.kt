package com.example.pixelpayout.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.pixelpayout.config.AppConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * The between-activities interstitial.
 *
 * A SIBLING OF [AdManager], NOT A GENERALISATION OF IT. The two look alike
 * from a distance - cache an ad, refill at show time, back off on failure -
 * but they answer opposite questions when there is no ad:
 *
 *   * A rewarded ad is something the player ASKED FOR. AdManager therefore
 *     waits several seconds for one, because a tap that does nothing is worse
 *     than a tap that takes a moment; the player is buying something.
 *   * An interstitial is something we are IMPOSING. It gets the short hold
 *     [AdHold] puts in front of it and nothing more - the player has finished
 *     and asked to move on, so we do not hold them while we shop for an
 *     advert.
 *
 * Hence [show]'s contract: `onDone` fires exactly once, always, and says
 * whether an ad was actually displayed. Callers navigate from it, so a
 * missing ad can never strand anyone, and the cadence only spends a slot when
 * there was really an impression.
 *
 * One ad cached rather than AdManager's pool of two, because the cadence
 * itself guarantees the spacing a pool would otherwise cover - see
 * [AdCadence]. Nothing can ask for two interstitials inside the minimum gap.
 */
class InterstitialAdManager private constructor() {

    private var cached: InterstitialAd? = null
    private var cachedAtMillis = 0L

    /**
     * When the outstanding request started, or null if there is none.
     *
     * A timestamp rather than a boolean FOR THE SAME REASON AdManager tracks
     * start times: a flag can only be cleared by a callback, which is exactly
     * what a lost request never delivers, and a stuck flag means [load] is a
     * no-op for the rest of the process. Interstitials fail silently by
     * design - the player just goes where they were going - so that state
     * would never be noticed from the outside.
     */
    private var loadStartedAt: Long? = null

    /** Application context, so the cache can refill without a caller. */
    private var appContext: Context? = null

    /**
     * The gate every request passes, exactly as in [AdManager] and for the
     * same reason: AdMob's client-side limiter answers a unit that has failed
     * repeatedly with a refusal rather than a fetch, and the retry path then
     * turns that refusal into another one. This unit is asked on every exit
     * from a game or quiz as well as on retry, so it needs the same floor
     * even though it has its own limiter budget.
     */
    private var nextAllowedAt = 0L

    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private var loadScheduled = false

    /**
     * Whether an ad could be shown right now.
     *
     * Read after the hold rather than before it, so a request that lands
     * during the hold still counts.
     */
    fun isReady(): Boolean {
        sweep()
        return cached != null
    }

    /**
     * Whether showing an ad is worth pausing the player for.
     *
     * True when one is already cached, or when a request could actually go
     * out inside [windowMs] and so has a real chance of landing during the
     * hold.
     *
     * THIS IS WHAT STOPS A DEAD UNIT CHARGING THE PLAYER A PAUSE PER EXIT.
     * Readiness moved to after the hold so that a request landing during it
     * still counts - but on its own that means a unit which never fills is
     * never ready, the cadence slot never resets, and from the second
     * completion onward EVERY game and quiz ends with a pause that shows
     * nothing. Asking whether a request is even permitted right now
     * distinguishes "the cache happens to be empty this second", which the
     * hold genuinely fixes, from "this unit is in backoff or rate limited",
     * which it cannot.
     */
    fun canServeWithin(windowMs: Long): Boolean {
        sweep()
        if (cached != null) return true
        // nextAllowedAt carries the request floor, the failure backoff and the
        // rate-limit cooldown all folded together - see the note on it - so
        // this one comparison covers every reason a request would be refused.
        return SystemClock.uptimeMillis() + windowMs >= nextAllowedAt
    }

    /** Fills the cache. Safe to call as often as you like. */
    fun load(context: Context) {
        appContext = context.applicationContext
        sweep()
        if (cached != null || loadStartedAt != null) return

        val now = SystemClock.uptimeMillis()
        if (now < nextAllowedAt) {
            scheduleLoad(nextAllowedAt - now)
            return
        }

        loadStartedAt = now
        // Booked before the request, so the floor holds even for one that
        // never answers.
        nextAllowedAt = maxOf(nextAllowedAt, now + MIN_REQUEST_INTERVAL_MS)
        Log.d(TAG, "Loading interstitial")

        InterstitialAd.load(
            appContext!!,
            AppConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadStartedAt = null
                    cached = ad
                    cachedAtMillis = System.currentTimeMillis()
                    retryAttempt = 0
                    Log.d(TAG, "Interstitial loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadStartedAt = null
                    // Logged in full for the same reason AdManager logs it:
                    // no fill, no network and an internal error all look
                    // identical from outside, and here they look like nothing
                    // at all, because a missing interstitial is silent.
                    Log.w(
                        TAG,
                        "Interstitial failed to load: code=${error.code} " +
                            "domain=${error.domain} message=${error.message}"
                    )

                    if (error.code == ERROR_CODE_TOO_MANY_REQUESTS) {
                        // Not a fill problem - it is our request rate.
                        // No separate cooldown field as AdManager keeps: that
                        // one exists so a user tap cannot override the limiter,
                        // and nothing here is ever asked for on demand. The
                        // gate below is the whole rule.
                        val until = SystemClock.uptimeMillis() + RATE_LIMIT_COOLDOWN_MS
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
     * Shows the cached ad if there is one, then calls [onDone].
     *
     * [onDone] is invoked exactly once in every path - shown and dismissed,
     * failed to show, or nothing cached - always on the main thread, and its
     * argument says whether an ad was really displayed. It is where the
     * caller navigates.
     */
    fun show(activity: Activity, onDone: (shown: Boolean) -> Unit) {
        sweep()

        val ad = cached
        if (ad == null || activity.isFinishing || activity.isDestroyed) {
            load(activity)
            onDone(false)
            return
        }

        cached = null
        appContext = activity.applicationContext

        // Refilled now rather than on dismissal, exactly as AdManager does:
        // the player is about to spend several seconds on this one, which is
        // more time than a load needs.
        load(activity)

        var finished = false
        val finish = { shown: Boolean ->
            if (!finished) {
                finished = true
                onDone(shown)
            }
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = finish(true)

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Interstitial failed to show: ${error.code} ${error.message}")
                finish(false)
            }
        }

        ad.show(activity)
    }

    /** Drops what is stale, abandons what is lost. */
    private fun sweep() {
        dropIfExpired()
        pruneStuckLoad()
    }

    /**
     * Drops an ad that has aged out.
     *
     * An expired interstitial fails at show() rather than announcing itself,
     * and here that failure is invisible - the player just goes where they
     * were going. Which sounds harmless, and is exactly why it is worth
     * catching: an app whose interstitials silently stopped filling would
     * look completely normal from the inside.
     */
    private fun dropIfExpired() {
        if (cached == null) return
        if (System.currentTimeMillis() - cachedAtMillis >= AD_TTL_MS) {
            Log.d(TAG, "Dropped expired interstitial")
            cached = null
        }
    }

    /**
     * Writes off a request that never called back, so it cannot block every
     * later load for the life of the process. See [loadStartedAt].
     */
    private fun pruneStuckLoad() {
        val started = loadStartedAt ?: return
        if (SystemClock.uptimeMillis() - started >= LOAD_TIMEOUT_MS) {
            loadStartedAt = null
            Log.w(TAG, "Abandoned an interstitial load that never called back")
        }
    }

    /**
     * Tries again after a failure, backing off 5s, 10s, 20s... to a minute.
     *
     * Without this one failure is permanent until something else happens to
     * call [load] - and unlike a greyed-out rewarded button, nobody would
     * ever notice. Starts at five seconds, not two: two is below what the
     * limiter tolerates on a failing unit, so the early retries were part of
     * what tripped it.
     */
    private fun scheduleRetry() {
        retryAttempt++
        val backoff = (RETRY_BASE_MS shl (retryAttempt - 1).coerceIn(0, 5))
            .coerceAtMost(MAX_RETRY_DELAY_MS)

        nextAllowedAt = maxOf(nextAllowedAt, SystemClock.uptimeMillis() + backoff)
        scheduleLoad(backoff)
    }

    private fun scheduleLoad(delayMs: Long) {
        if (loadScheduled) return
        val context = appContext ?: return

        loadScheduled = true
        Log.d(TAG, "Next interstitial request in ${delayMs}ms")
        retryHandler.postDelayed({
            loadScheduled = false
            load(context)
        }, delayMs.coerceAtLeast(0))
    }

    companion object {
        private const val TAG = "InterstitialAdManager"

        /**
         * Conservative against the roughly one hour an interstitial stays
         * valid. Cheaper to throw a good ad away than to show nothing while
         * believing we showed something.
         */
        private const val AD_TTL_MS = 50 * 60 * 1000L

        /** See AdManager.LOAD_TIMEOUT_MS - this is a leak detector, not a deadline. */
        private const val LOAD_TIMEOUT_MS = 30_000L

        /** ERROR_CODE_INVALID_REQUEST, which is also "too many recent failures". */
        private const val ERROR_CODE_TOO_MANY_REQUESTS = 1

        /** The minimum gap between two requests for this unit, whoever asks. */
        private const val MIN_REQUEST_INTERVAL_MS = 5_000L

        /** How long to stop asking entirely after a rate-limit refusal. */
        private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

        private const val RETRY_BASE_MS = 5_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        private var instance: InterstitialAdManager? = null

        fun getInstance(): InterstitialAdManager {
            if (instance == null) {
                instance = InterstitialAdManager()
            }
            return instance!!
        }
    }
}
