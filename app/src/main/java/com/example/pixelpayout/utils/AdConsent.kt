package com.example.pixelpayout.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ad consent through Google's User Messaging Platform (UMP).
 *
 * AdMob requires a certified consent message for users in the EEA, the UK and
 * Switzerland before ads are requested. UMP decides from the user's location
 * whether the form is needed at all - everywhere else it answers "can request
 * ads" straight away without showing anything. The consent form itself is
 * configured in the AdMob console under Privacy & messaging.
 *
 * Every ad request in the app checks [canRequestAds] first, so nothing loads
 * before the answer is known. The stored answer from a previous session counts,
 * which keeps a returning user's ads warm at launch.
 */
object AdConsent {

    private const val TAG = "AdConsent"
    private val gathering = AtomicBoolean(false)

    fun canRequestAds(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context.applicationContext).canRequestAds()

    /** Whether Profile should offer "Privacy choices" to change the answer. */
    fun privacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context.applicationContext)
            .privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    /**
     * Refreshes consent and shows the form if this user needs one. [onDone]
     * runs once the answer is settled, whatever it is - including on error, so
     * the app never waits on it.
     */
    fun gather(activity: Activity, onDone: () -> Unit) {
        if (!gathering.compareAndSet(false, true)) return
        val info = UserMessagingPlatform.getConsentInformation(activity.applicationContext)
        info.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    error?.let { Log.w(TAG, "Consent form: ${it.message}") }
                    gathering.set(false)
                    onDone()
                }
            },
            { error ->
                Log.w(TAG, "Consent update failed: ${error.message}")
                gathering.set(false)
                onDone()
            }
        )
    }

    /** Re-opens the consent form so the user can change their choice. */
    fun showPrivacyOptions(activity: Activity, onDone: () -> Unit = {}) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            error?.let { Log.w(TAG, "Privacy options: ${it.message}") }
            onDone()
        }
    }
}
