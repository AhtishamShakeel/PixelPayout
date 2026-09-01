package com.example.pixelpayout.utils

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import com.pixelpayout.R

/**
 * The short pause between finishing an activity and being shown an
 * interstitial.
 *
 * It does two jobs, and both of them are the reason it exists rather than the
 * ad being shown the instant the player taps Continue:
 *
 *   1. A LAST CHANCE TO LOAD. A request is issued as the hold starts, so a
 *     cache that happened to be empty - a cold start, a failed load still in
 *     backoff, an ad that just expired - has [HOLD_MS] to fill before we give
 *     up on the slot. Fill is the scarce resource here, and this converts a
 *     measurable share of empty slots into impressions for the cost of a
 *     pause the player reads as loading.
 *
 *   2. BREATHING ROOM SO THE FIRST TAP IS NOT ON THE AD. The player has just
 *     tapped Continue, and their finger is on the screen and moving. An
 *     interstitial that appears in that instant collects a tap that was meant
 *     for the button underneath it. Those are worth nothing - accidental
 *     clicks are exactly what invalid-traffic filtering strips out, and a
 *     high rate of them on a placement is how an account gets flagged - so
 *     buying a moment of separation protects revenue rather than costing it.
 *
 * THE SLOT IS ONLY SPENT IF AN AD ACTUALLY APPEARS. If the hold ends with
 * nothing cached, the player carries on and [AdCadence] keeps the slot open
 * for the next completion, so a no-fill costs them this pause and nothing
 * more.
 */
object AdHold {

    private const val TAG = "AdHold"

    /**
     * How long the hold lasts.
     *
     * Long enough to break the tap-through from the button the player just
     * pressed and to let a request land, short enough not to read as the app
     * having frozen. Past about a second it stops feeling like a transition
     * and starts feeling like a stall.
     */
    private const val HOLD_MS = 800L

    /**
     * Runs the hold, shows an interstitial if one is available by the end of
     * it, then calls [onContinue].
     *
     * [onContinue] fires exactly once on every path - ad shown, no ad, or the
     * activity going away underneath us - because the caller finishes the
     * screen from it. Nothing here may strand somebody on a dead results
     * panel.
     */
    fun showInterstitialThen(activity: Activity, onContinue: () -> Unit) {
        val interstitials = InterstitialAdManager.getInstance()

        // Issued at the START of the hold so the request has the whole pause
        // to land. A no-op when one is already cached or already on the wire.
        interstitials.load(activity)

        val dialog = buildHoldDialog(activity)
        if (dialog == null) {
            // No window to hold in - the activity is already going. Skip
            // straight to the ad attempt rather than dropping the slot.
            finishHold(activity, null, onContinue)
            return
        }

        dialog.show()
        Handler(Looper.getMainLooper()).postDelayed(
            { finishHold(activity, dialog, onContinue) },
            HOLD_MS
        )
    }

    private fun finishHold(activity: Activity, dialog: Dialog?, onContinue: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) {
            // The player left during the hold. Nothing to show an ad over,
            // and dismissing against a dead window throws.
            dismissQuietly(dialog)
            onContinue()
            return
        }

        InterstitialAdManager.getInstance().show(activity) { shown ->
            if (shown) {
                AdCadence.noteInterstitialShown(activity)
            } else {
                Log.d(TAG, "Hold elapsed with no interstitial - slot left open")
            }
            // Dismissed AFTER the ad rather than before it. Between the two
            // there would otherwise be a frame of the finished results screen,
            // which is both a flash of the wrong thing and one more instant in
            // which a stray tap lands somewhere it should not.
            dismissQuietly(dialog)
            onContinue()
        }
    }

    /**
     * A bare, uncancellable window for the hold view.
     *
     * Not cancellable and not dismissable by back: the whole point is a
     * period in which input does nothing, so a player who taps or swipes
     * through it would defeat it exactly when it matters most.
     */
    private fun buildHoldDialog(activity: Activity): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null

        return try {
            Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(R.layout.view_ad_hold)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
        } catch (e: Exception) {
            // A window that cannot be built is not worth failing the exit
            // over - the player still leaves, and the ad still gets its try.
            Log.w(TAG, "Could not build the hold window: ${e.message}")
            null
        }
    }

    private fun dismissQuietly(dialog: Dialog?) {
        try {
            dialog?.dismiss()
        } catch (e: Exception) {
            // Dismissing against a window that has already gone throws, and
            // there is nothing useful to do about it at this point.
            Log.w(TAG, "Could not dismiss the hold window: ${e.message}")
        }
    }
}
