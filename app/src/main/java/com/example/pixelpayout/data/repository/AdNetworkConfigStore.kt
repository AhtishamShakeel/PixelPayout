package com.example.pixelpayout.data.repository

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

    @Volatile var primary: Network = Network.ADMOB
        private set
    @Volatile var admobEnabled: Boolean = true
        private set
    @Volatile var unityEnabled: Boolean = true
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
                Log.d(TAG, "primary=$primary admob=$admobEnabled unity=$unityEnabled")
            }
    }

    fun stop() {
        registration?.remove()
        registration = null
    }
}
