package com.example.pixelpayout.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.pixelpayout.config.AppConfig
import com.tapjoy.TJActionRequest
import com.tapjoy.TJConnectListener
import com.tapjoy.TJError
import com.tapjoy.TJPlacement
import com.tapjoy.TJPlacementListener
import com.tapjoy.TJSetUserIDListener
import com.tapjoy.Tapjoy
import com.tapjoy.TapjoyConnectFlag
import java.util.Hashtable

/**
 * The Tapjoy offerwall, kept in one place.
 *
 * Tapjoy is the odd one out in the catalogue: every other wall is a URL and
 * needs no code at all, while this one is an SDK with a connect step, an
 * async user-id step and a request/ready/show dance. Isolating it here is
 * what keeps that shape out of RewardsFragment, where the previous version
 * of this integration lived and went wrong in three separate ways.
 *
 * WHAT WENT WRONG BEFORE, since the same mistakes are easy to make again:
 *
 *   * `Tapjoy.connect` ran on every visit to the Rewards screen. Connecting
 *     is a process-wide, once-per-launch operation; doing it per fragment
 *     meant reconnecting behind an already-live session.
 *   * `onRequestSuccess` called `requestContent()` again - re-entering the
 *     request it was reporting the success of.
 *   * The placement held an Activity across rotation, which leaks it.
 *
 * WHAT IS NOT HERE, deliberately: any crediting. `onRewardRequest` is not
 * implemented, because the client must never be the thing that says a
 * completion happened. Tapjoy's self-managed currency callback signs a call
 * to `offerwallCallback` and the server credits it there. That is why this
 * file can be this small.
 */
object TapjoyOfferwall {

    private const val TAG = "TapjoyOfferwall"

    /** Set once connect succeeds, so a second call is a no-op rather than a
     *  reconnect. */
    @Volatile
    private var connecting = false

    /**
     * The uid currently handed to Tapjoy, if any.
     *
     * Tracked because `setUserID` is asynchronous and must have landed
     * BEFORE the wall opens: the id it carries is what the callback reports
     * as `snuid`, so showing content early credits the wrong account - or
     * nobody. Comparing against the signed-in uid is also what makes a
     * second sign-in on the same device re-issue it.
     */
    @Volatile
    private var appliedUid: String? = null

    private var placement: TJPlacement? = null

    /**
     * Whether a content request is on the wire.
     *
     * Tapjoy refuses a second request for a placement it is already fetching,
     * and refuses it by discarding the request rather than by calling back -
     * so without this flag, taps during a fetch produce complete silence.
     */
    @Volatile
    private var requesting = false

    /** Callbacks arrive off the main thread; UI work has to be posted back. */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Connects at app start, before any screen asks for a wall.
     *
     * Safe to call when Tapjoy is unconfigured or unreachable - a failure
     * here must never take the app down, because the offerwall is one tab
     * and everything else keeps working without it.
     */
    fun connect(context: Context) {
        if (Tapjoy.isConnected() || connecting) return
        if (AppConfig.TAPJOY_SDK_KEY.isBlank()) return

        connecting = true
        // Logging follows the debuggable flag rather than being hardcoded
        // off: Tapjoy's integration problems - a wrong SDK key, a placement
        // that does not exist, a device not on the test list - are all
        // invisible without it, and all of them are found during testing.
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val flags = Hashtable<String, Any>().apply {
            put(TapjoyConnectFlag.ENABLE_LOGGING, debuggable.toString())
        }

        Tapjoy.connect(
            context.applicationContext,
            AppConfig.TAPJOY_SDK_KEY,
            flags,
            object : TJConnectListener() {
                override fun onConnectSuccess() {
                    connecting = false
                    Log.d(TAG, "Connected")
                }

                override fun onConnectFailure(code: Int, message: String?) {
                    connecting = false
                    Log.e(TAG, "Connect failed: $code $message")
                }

                override fun onConnectWarning(code: Int, message: String?) {
                    Log.w(TAG, "Connect warning: $code $message")
                }
            }
        )
    }

    /**
     * Opens the wall for [uid], reporting failures through [onUnavailable]
     * rather than silently doing nothing.
     *
     * Silence was the old behaviour and it is the worst option: a tap that
     * produces no screen and no message is indistinguishable from a frozen
     * app, and the user's only recourse is to tap again.
     *
     * The sequence is connect -> set user -> request -> show, and each step
     * is skipped when it has already happened, so a second open is
     * immediate.
     */
    fun show(activity: Activity, uid: String, onUnavailable: (String) -> Unit) {
        if (!Tapjoy.isConnected()) {
            connect(activity)
            onUnavailable(REASON_CONNECTING)
            return
        }

        // Required before showing: Tapjoy renders content into the current
        // activity, and without this it can hold a stale one.
        Tapjoy.setActivity(activity)

        if (appliedUid == uid) {
            requestAndShow(activity, onUnavailable)
            return
        }

        Tapjoy.setUserID(uid, object : TJSetUserIDListener {
            override fun onSetUserIDSuccess() {
                appliedUid = uid
                requestAndShow(activity, onUnavailable)
            }

            // Both failure paths post back to the main thread for the same
            // reason the placement callbacks do: these arrive on a Tapjoy
            // thread, and a toast raised there never appears.
            override fun onSetUserIDFailure(message: String?) {
                // Refuse rather than open anyway. A wall opened without the
                // right user id lets somebody complete an offer that credits
                // nobody, which is worse than not opening it.
                Log.e(TAG, "setUserID failed: $message")
                main.post { onUnavailable(REASON_USER_ID) }
            }

            override fun onSetUserIDFailure(code: Int, message: String?) {
                Log.e(TAG, "setUserID failed: $code $message")
                main.post { onUnavailable(REASON_USER_ID) }
            }
        })
    }

    private fun requestAndShow(activity: Activity, onUnavailable: (String) -> Unit) {
        placement?.takeIf { it.isContentReady }?.let {
            it.showContent()
            return
        }

        // A REQUEST ALREADY IN FLIGHT IS NOT A REASON TO START ANOTHER.
        //
        // Without this the second tap builds a second TJPlacement for the
        // same name, Tapjoy answers "Placement offerwall is already
        // requesting content" and drops it - and because it dropped the
        // REQUEST, no callback ever fires for it. So every tap after the
        // first was silent by construction: no wall, no message, nothing in
        // the log but a refusal. Telling the user it is still loading is
        // both true and the only honest thing available.
        if (requesting) {
            onUnavailable(REASON_LOADING)
            return
        }

        // Callbacks arrive on a Tapjoy background thread. Everything the
        // caller does with them touches the UI - a toast, a dialog - and a
        // toast posted off the main thread simply never appears, which is
        // why the "no offers" case looked like nothing happening at all.
        val report: (String) -> Unit = { reason ->
            main.post { onUnavailable(reason) }
        }

        val listener = object : TJPlacementListener {
            override fun onRequestSuccess(p: TJPlacement?) {
                // NOT requestContent() again - that is the re-entrancy bug
                // the old version had. Success here only means the request
                // was accepted; readiness arrives at onContentReady.
                if (p?.isContentAvailable != true) {
                    // A 204 from Tapjoy: the request worked, there is simply
                    // nothing to show. Common on a new app with no traffic.
                    Log.w(TAG, "No content available (no fill)")
                    requesting = false
                    // Dropped so the next tap asks again rather than reusing
                    // a placement that has already been told there is
                    // nothing. Fill changes minute to minute.
                    placement = null
                    report(REASON_NO_CONTENT)
                }
            }

            override fun onRequestFailure(p: TJPlacement?, error: TJError?) {
                Log.e(TAG, "Request failed: ${error?.message}")
                requesting = false
                placement = null
                report(REASON_NO_CONTENT)
            }

            override fun onContentReady(p: TJPlacement?) {
                requesting = false
                if (activity.isFinishing || activity.isDestroyed) return
                main.post { p?.showContent() }
            }

            override fun onContentShow(p: TJPlacement?) = Unit

            override fun onContentDismiss(p: TJPlacement?) {
                // Dropped on dismiss so the next open fetches a fresh wall
                // rather than redisplaying a stale one.
                requesting = false
                placement = null
            }

            override fun onPurchaseRequest(
                p: TJPlacement?,
                request: TJActionRequest?,
                sku: String?
            ) = Unit

            /**
             * Intentionally does nothing.
             *
             * This is the client asserting that a reward is owed, and the
             * client is exactly the thing that must not be trusted with
             * that. The signed server callback is the only path that credits.
             */
            override fun onRewardRequest(
                p: TJPlacement?,
                request: TJActionRequest?,
                currency: String?,
                amount: Int
            ) {
                Log.d(TAG, "onRewardRequest ignored (server credits): $amount $currency")
            }
        }

        // Application context, not the Activity: the placement outlives any
        // single one of them, and holding an Activity here is how the old
        // version leaked across rotation. setActivity above is what tells
        // Tapjoy where to draw.
        requesting = true
        placement = TJPlacement(
            activity.applicationContext,
            AppConfig.TAPJOY_OFFERWALL_PLACEMENT,
            listener
        ).also { it.requestContent() }
    }

    const val REASON_CONNECTING = "connecting"
    const val REASON_LOADING = "loading"
    const val REASON_USER_ID = "user_id"
    const val REASON_NO_CONTENT = "no_content"
}
