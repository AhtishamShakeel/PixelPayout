package com.createbyte.lootlevel.utils

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.createbyte.lootlevel.config.AppConfig
import com.createbyte.lootlevel.data.repository.AdNetworkConfigStore
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions

/**
 * Unity Ads, the FALLBACK behind AdMob - for rewarded ads and interstitials.
 *
 * NOT MEDIATION. AdMob mediation would put Unity behind the AdMob account, so
 * an AdMob ban would take Unity down with it. Instead the two SDKs run side by
 * side and [AdManager] / [InterstitialAdManager] choose at show time, in the
 * order `config/ads` in Firestore sets (AdMob first by default) - see
 * [AdNetworkConfigStore]. Callers never know which
 * network played - the reward path is network-agnostic on purpose.
 *
 * KEPT LOADED IN PARALLEL, not only once AdMob has failed. A fallback that
 * starts loading at the moment it is needed arrives seconds too late for a
 * player looking at "Finding an ad". Unity holds one ad per placement, so this
 * is one extra request per ad actually shown, not a stream.
 *
 * Off entirely while [AppConfig.UNITY_GAME_ID] is blank, so the app behaves
 * exactly as it did before until an id is filled in.
 *
 * Consent: Unity is an IAB TCF vendor and reads the TCF string Google's UMP
 * form stores, so nothing is passed by hand. Unity must be on the AdMob
 * console's GDPR ad partners list for EEA/UK users to be asked about it. No
 * request goes out before [AdConsent.canRequestAds], same as AdMob.
 *
 * Rewards are asserted by the client, exactly as with AdMob today - no
 * server-to-server callback yet. The server's daily caps are what bound it.
 */
object UnityAdsNetwork {

    private const val TAG = "UnityAdsNetwork"

    /** One ad per placement: what Unity holds, and all a fallback needs. */
    private class Slot(val placementId: String) {
        var loaded = false
        var loadStartedAt: Long? = null
        var nextAllowedAt = 0L
        var retryAttempt = 0
        var retryScheduled = false
    }

    private val rewarded = Slot(AppConfig.UNITY_REWARDED_PLACEMENT_ID)
    private val interstitial = Slot(AppConfig.UNITY_INTERSTITIAL_PLACEMENT_ID)

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private var initStarted = false
    private var initialized = false

    /** Whether a Game ID is set at all. Decides whether the SDK starts. */
    private val configured: Boolean
        get() = AppConfig.UNITY_GAME_ID.isNotBlank()

    /**
     * Whether Unity may load and show right now. The Firestore switch is
     * separate from [configured] so turning Unity off and back on from the
     * console doesn't need the SDK restarted.
     */
    private val enabled: Boolean
        get() = configured && AdNetworkConfigStore.unityEnabled

    /** Starts the SDK once per process. Loads follow on completion. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (!configured) {
            Log.d(TAG, "No Unity game id - Unity Ads is off")
            return
        }
        if (initStarted) return
        initStarted = true

        UnityAds.initialize(
            context.applicationContext,
            AppConfig.UNITY_GAME_ID,
            AppConfig.UNITY_TEST_MODE,
            object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    handler.post {
                        initialized = true
                        Log.d(TAG, "Initialized (testMode=${AppConfig.UNITY_TEST_MODE})")
                        loadRewarded(context)
                        loadInterstitial(context)
                    }
                }

                override fun onInitializationFailed(
                    error: UnityAds.UnityAdsInitializationError,
                    message: String
                ) {
                    // Retried on the next launch. A fallback that failed to
                    // start must not take anything else down with it.
                    handler.post {
                        initStarted = false
                        Log.w(TAG, "Initialization failed: $error $message")
                    }
                }
            }
        )
    }

    fun isRewardedReady(): Boolean = enabled && initialized && rewarded.loaded
    fun isInterstitialReady(): Boolean = enabled && initialized && interstitial.loaded

    /** Whether an interstitial could plausibly land within [windowMs]. */
    fun canServeInterstitialWithin(windowMs: Long): Boolean {
        if (!enabled || !initialized) return false
        if (interstitial.loaded) return true
        return SystemClock.uptimeMillis() + windowMs >= interstitial.nextAllowedAt
    }

    fun loadRewarded(context: Context) = load(context, rewarded)
    fun loadInterstitial(context: Context) = load(context, interstitial)

    /**
     * Plays the Unity rewarded ad. Returns false, calling nothing, if there is
     * none loaded - the caller then reports its own "no ad" answer.
     *
     * Callback order matches AdMob's: [onRewarded] (only for a completed
     * view) and then [onClosed]. A skipped ad gets [onClosed] alone.
     */
    fun showRewarded(
        activity: Activity,
        onRewarded: () -> Unit,
        onClosed: () -> Unit,
        onFailed: () -> Unit
    ): Boolean {
        if (!isRewardedReady()) return false
        show(activity, rewarded, object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String) {
                AdCadence.noteRewardedShown(activity)
            }

            override fun onUnityAdsShowClick(placementId: String) = Unit

            override fun onUnityAdsShowComplete(
                placementId: String,
                state: UnityAds.UnityAdsShowCompletionState
            ) {
                handler.post {
                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) onRewarded()
                    onClosed()
                }
            }

            override fun onUnityAdsShowFailure(
                placementId: String,
                error: UnityAds.UnityAdsShowError,
                message: String
            ) {
                Log.w(TAG, "Rewarded failed to show: $error $message")
                handler.post { onFailed() }
            }
        })
        return true
    }

    /**
     * Plays the Unity interstitial. Returns false, calling nothing, if there
     * is none loaded. Otherwise [onDone] fires exactly once, with whether an
     * ad was really displayed - the same contract as
     * [InterstitialAdManager.show].
     */
    fun showInterstitial(activity: Activity, onDone: (shown: Boolean) -> Unit): Boolean {
        if (!isInterstitialReady()) return false
        var finished = false
        val finish: (Boolean) -> Unit = { shown ->
            handler.post {
                if (!finished) {
                    finished = true
                    onDone(shown)
                }
            }
        }
        show(activity, interstitial, object : IUnityAdsShowListener {
            override fun onUnityAdsShowStart(placementId: String) = Unit
            override fun onUnityAdsShowClick(placementId: String) = Unit

            override fun onUnityAdsShowComplete(
                placementId: String,
                state: UnityAds.UnityAdsShowCompletionState
            ) = finish(true)

            override fun onUnityAdsShowFailure(
                placementId: String,
                error: UnityAds.UnityAdsShowError,
                message: String
            ) {
                Log.w(TAG, "Interstitial failed to show: $error $message")
                finish(false)
            }
        })
        return true
    }

    private fun show(activity: Activity, slot: Slot, listener: IUnityAdsShowListener) {
        // Spent the moment it is shown, and the replacement requested now -
        // the player is about to spend longer on this ad than a load takes.
        slot.loaded = false
        UnityAds.show(activity, slot.placementId, UnityAdsShowOptions(), listener)
        load(activity, slot)
    }

    /**
     * At most one request per placement, paced and backed off like the AdMob
     * managers: 5s, 10s, 20s... to a minute after failures.
     */
    private fun load(context: Context, slot: Slot) {
        appContext = context.applicationContext
        if (!enabled || !initialized) return
        if (slot.loaded) return
        if (!AdConsent.canRequestAds(context.applicationContext)) return

        val now = SystemClock.uptimeMillis()
        slot.loadStartedAt?.let { started ->
            // A load that never called back must not block the slot forever.
            if (now - started < LOAD_TIMEOUT_MS) return
            Log.w(TAG, "Abandoned a ${slot.placementId} load that never called back")
            slot.loadStartedAt = null
        }
        if (now < slot.nextAllowedAt) {
            scheduleRetry(slot, slot.nextAllowedAt - now)
            return
        }

        slot.loadStartedAt = now
        slot.nextAllowedAt = now + MIN_REQUEST_INTERVAL_MS
        Log.d(TAG, "Loading ${slot.placementId}")

        UnityAds.load(slot.placementId, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String) {
                handler.post {
                    slot.loadStartedAt = null
                    slot.loaded = true
                    slot.retryAttempt = 0
                    Log.d(TAG, "Loaded $placementId")
                }
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String,
                error: UnityAds.UnityAdsLoadError,
                message: String
            ) {
                handler.post {
                    slot.loadStartedAt = null
                    Log.w(TAG, "Failed to load $placementId: $error $message")
                    slot.retryAttempt++
                    val backoff = (RETRY_BASE_MS shl (slot.retryAttempt - 1).coerceIn(0, 5))
                        .coerceAtMost(MAX_RETRY_DELAY_MS)
                    slot.nextAllowedAt = maxOf(slot.nextAllowedAt, SystemClock.uptimeMillis() + backoff)
                    scheduleRetry(slot, backoff)
                }
            }
        })
    }

    private fun scheduleRetry(slot: Slot, delayMs: Long) {
        if (slot.retryScheduled) return
        val context = appContext ?: return
        slot.retryScheduled = true
        handler.postDelayed({
            slot.retryScheduled = false
            load(context, slot)
        }, delayMs.coerceAtLeast(0))
    }

    private const val MIN_REQUEST_INTERVAL_MS = 5_000L
    private const val LOAD_TIMEOUT_MS = 30_000L
    private const val RETRY_BASE_MS = 5_000L
    private const val MAX_RETRY_DELAY_MS = 60_000L
}
