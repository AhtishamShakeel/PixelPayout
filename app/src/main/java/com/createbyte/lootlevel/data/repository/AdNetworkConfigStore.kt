package com.createbyte.lootlevel.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Which ad network plays first, and which are switched on - from Firestore.
 *
 * `config/ads`, edited in the Firebase console:
 *
 *   primary       "admob" or "unity"   which network is tried first
 *   admobEnabled  true / false         false stops every AdMob request
 *   unityEnabled  true / false         false stops every Unity request
 *
 * And how often interstitials may appear (see AdCadence):
 *
 *   maxInterstitialsPerSession  number   0 turns interstitials off; default 8
 *   momentGapSeconds            number   min gap before a "moment" ad; default 90
 *   tabSwitchGapSeconds         number   min gap before a tab-switch ad; default 180
 *
 * Every field is optional; a missing document or field means the defaults
 * below - AdMob first, both on - which is also what a fresh install uses
 * before Firestore answers.
 *
 * THIS IS THE EMERGENCY SWITCH. If AdMob suspends the account, setting
 * `admobEnabled` to false stops the app hammering a dead account within
 * seconds on every running install, with no release. Live rather than
 * one-shot for exactly that reason: one document, one listener, one read per
 * launch plus one per change.
 *
 * Read on the main thread by the ad managers at show time, so the fields are
 * plain volatiles rather than LiveData.
 */
object AdNetworkConfigStore {

    enum class Network { ADMOB, UNITY }

    private const val TAG = "AdNetworkConfig"
    private const val COLLECTION = "config"
    private const val DOC = "ads"

    // Clamped so a console typo can't turn the app into an ad wall: the gaps
    // never drop under the 45s floor AdCadence already uses, and the session
    // cap has a ceiling.
    private const val DEFAULT_MAX_PER_SESSION = 8
    private const val MAX_PER_SESSION_CEILING = 20
    private const val DEFAULT_MOMENT_GAP_S = 90L
    private const val DEFAULT_TAB_SWITCH_GAP_S = 180L
    private const val MIN_GAP_S = 45L
    private const val MAX_GAP_S = 3600L

    @Volatile var primary: Network = Network.ADMOB
        private set
    @Volatile var admobEnabled: Boolean = true
        private set
    @Volatile var unityEnabled: Boolean = true
        private set
    @Volatile var maxInterstitialsPerSession: Int = DEFAULT_MAX_PER_SESSION
        private set
    @Volatile var momentGapMs: Long = DEFAULT_MOMENT_GAP_S * 1000L
        private set
    @Volatile var tabSwitchGapMs: Long = DEFAULT_TAB_SWITCH_GAP_S * 1000L
        private set

    private var registration: ListenerRegistration? = null

    /** Starts listening, at most once. `config` needs a signed-in reader. */
    fun start() {
        if (registration != null) return
        if (FirebaseAuth.getInstance().currentUser == null) return

        registration = FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(DOC)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Keep whatever we had. Failing to read the switch is not
                    // a reason to turn ads off.
                    Log.w(TAG, "Listener failed: ${error.message}")
                    return@addSnapshotListener
                }
                primary = when (snapshot?.getString("primary")?.trim()?.lowercase()) {
                    "unity" -> Network.UNITY
                    else -> Network.ADMOB
                }
                admobEnabled = snapshot?.getBoolean("admobEnabled") ?: true
                unityEnabled = snapshot?.getBoolean("unityEnabled") ?: true
                maxInterstitialsPerSession = (snapshot?.getLong("maxInterstitialsPerSession")
                    ?: DEFAULT_MAX_PER_SESSION.toLong()).coerceIn(0, MAX_PER_SESSION_CEILING.toLong()).toInt()
                momentGapMs = (snapshot?.getLong("momentGapSeconds") ?: DEFAULT_MOMENT_GAP_S)
                    .coerceIn(MIN_GAP_S, MAX_GAP_S) * 1000L
                tabSwitchGapMs = (snapshot?.getLong("tabSwitchGapSeconds") ?: DEFAULT_TAB_SWITCH_GAP_S)
                    .coerceIn(MIN_GAP_S, MAX_GAP_S) * 1000L
                Log.d(TAG, "primary=$primary admob=$admobEnabled unity=$unityEnabled")
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
    }
}
