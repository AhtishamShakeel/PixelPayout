package com.example.pixelpayout.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.example.pixelpayout.config.AppConfig

/**
 * The single cached rewarded ad, shared by every screen that offers one.
 *
 * TEMPORARY BY DESIGN - this is the one-unit, one-network placeholder that
 * the placement-based gateway replaces. Two things were fixed in place
 * because a screen that depends on ad availability cannot work without them:
 *
 *   * a failed load used to be silent AND final. The error was discarded and
 *     nothing retried, so one failure - a cold start racing MobileAds.initialize,
 *     a dropped connection, anything - left every "watch an ad" control dead
 *     for the rest of the process with no way to find out why.
 *   * the load held whatever Context it was handed, which from a fragment is
 *     the Activity. Parking that in a process-lifetime singleton with a
 *     delayed retry attached to it leaks the Activity, so only the application
 *     context is kept.
 */
class AdManager private constructor() {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var adAvailabilityCallback: ((Boolean) -> Unit)? = null

    private val retryHandler = Handler(Looper.getMainLooper())

    /** Consecutive failures, for the backoff. Reset by any successful load. */
    private var retryAttempt = 0

    /** Stops a burst of loadRewardedAd calls queueing a retry each. */
    private var retryScheduled = false

    fun setAdAvailabilityCallback(callback: (Boolean) -> Unit) {
        adAvailabilityCallback = callback
        callback(rewardedAd != null)
    }

    /**
     * Whether an ad is cached and can be shown right now.
     *
     * Deliberately a poll rather than another callback: setAdAvailabilityCallback
     * holds ONE listener, so a second screen registering silently unsubscribes
     * the first. Screens that need to enable a control on availability read
     * this on a tick they already run.
     */
    fun isRewardedAdReady(): Boolean = rewardedAd != null

    fun loadRewardedAd(context: Context) {
        if (rewardedAd != null || isLoading) return

        // The Activity that asked may be gone by the time this answers, and
        // the retry below outlives it for certain.
        val appContext = context.applicationContext

        isLoading = true
        Log.d(TAG, "Loading rewarded ad (attempt ${retryAttempt + 1})")

        RewardedAd.load(
            appContext,
            AppConfig.ADMOB_REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    Log.d(TAG, "Rewarded ad loaded")
                    rewardedAd = ad
                    isLoading = false
                    retryAttempt = 0
                    adAvailabilityCallback?.invoke(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Logged in full because this is the only place the reason
                    // exists. code 3 is no fill, 2 is network, 0 is an
                    // internal error - and "the button does nothing" looks
                    // identical for all three.
                    Log.w(
                        TAG,
                        "Rewarded ad failed to load: code=${error.code} " +
                            "domain=${error.domain} message=${error.message} " +
                            "cause=${error.cause}"
                    )
                    rewardedAd = null
                    isLoading = false
                    adAvailabilityCallback?.invoke(false)
                    scheduleRetry(appContext)
                }
            }
        )
    }

    /**
     * Tries again after a failure, backing off 2s, 4s, 8s... to a minute.
     *
     * Without this a single failure is permanent: nothing else calls
     * loadRewardedAd until a screen is recreated, so the offer stays dead
     * while the user sits and looks at it.
     */
    private fun scheduleRetry(context: Context) {
        if (retryScheduled) return

        retryAttempt++
        val delayMs = (1L shl retryAttempt.coerceAtMost(6)) * 1_000L
        val backoff = delayMs.coerceAtMost(MAX_RETRY_DELAY_MS)

        Log.d(TAG, "Retrying rewarded ad load in ${backoff}ms")
        retryScheduled = true
        retryHandler.postDelayed({
            retryScheduled = false
            loadRewardedAd(context)
        }, backoff)
    }

    fun showRewardedAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onAdClosed: () -> Unit,
        onAdFailedToShow: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            Log.w(TAG, "showRewardedAd with nothing cached")
            onAdFailedToShow()
            loadRewardedAd(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd(activity)
                onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Rewarded ad failed to show: ${error.code} ${error.message}")
                rewardedAd = null
                onAdFailedToShow()
                // The cached ad is spent either way, so start replacing it.
                loadRewardedAd(activity)
            }
        }

        ad.show(activity) {
            onRewarded()
        }
    }

    companion object {
        private const val TAG = "AdManager"
        private const val MAX_RETRY_DELAY_MS = 60_000L

        private var instance: AdManager? = null

        fun getInstance(): AdManager {
            if (instance == null) {
                instance = AdManager()
            }
            return instance!!
        }
    }
}
