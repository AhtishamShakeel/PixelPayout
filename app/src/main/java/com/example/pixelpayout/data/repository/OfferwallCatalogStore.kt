package com.example.pixelpayout.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.pixelpayout.data.model.OfferwallEntry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * The offerwall catalogue, live.
 *
 * A process-level object for the same reason [RedemptionOptionsStore] is:
 * UserRepository is constructed per view model, so a member cache would be a
 * cache per screen.
 *
 * WHY THIS ONE LISTENS AT SIGN-IN AND THE REDEMPTION CATALOGUE DOES NOT.
 * That one is a collection - fifteen documents, so fifteen reads every time
 * a fresh process attaches - and most sessions never open Wallet, which is
 * why it seeds from disk instead. This is a SINGLE document, so attaching
 * costs one read per launch, and the answer is needed on every launch
 * regardless of where the user goes: the bottom bar has to decide whether
 * the Earn tab exists before the user can tap anything.
 *
 * Live rather than one-shot because the toggle is meant to be operational.
 * Flipping `enabled` in the console is how a network gets switched on the
 * hour it approves us and switched off the hour it breaks, and neither is
 * worth shipping a release for or waiting for every install to restart.
 *
 * A FAILED READ PUBLISHES AN EMPTY LIST, which hides the tab. That is the
 * honest direction to fail in: if we cannot confirm a wall exists, we cannot
 * confirm the user can earn anything there, and a tab leading to an empty
 * screen is worse than no tab. It is also the state a brand new install is
 * in before Firestore answers.
 */
object OfferwallCatalogStore {

    private const val TAG = "OfferwallCatalog"
    private const val COLLECTION = "config"
    private const val DOC = "offerwallWalls"

    /**
     * Every enabled wall, unfiltered by level.
     *
     * Level filtering is [OfferwallEntry.visibleTo] at the point of use -
     * the level is user state and this is app config, and the store has no
     * business knowing which user is signed in.
     *
     * DELIBERATELY HAS NO INITIAL VALUE, so "not answered yet" and "answered
     * with nothing" are distinguishable. An empty list would collapse them,
     * and the two demand opposite behaviour: an unanswered catalogue must
     * leave the bottom bar showing whatever it remembered from last launch,
     * while a confirmed empty one must hide the Earn tab. Emitting empty
     * first would hide the tab on every launch and then bring it back a beat
     * later, which is the exact flicker the remembered flag exists to avoid.
     *
     * Practically this also means an offline launch with a cold disk cache
     * never emits at all - and the bar keeping its last known shape is the
     * right answer there too.
     */
    private val _walls = MutableLiveData<List<OfferwallEntry>>()
    val walls: LiveData<List<OfferwallEntry>> = _walls

    private var registration: ListenerRegistration? = null

    /**
     * Starts listening, at most once.
     *
     * `config/{docId}` requires an authenticated reader (see
     * firestore.rules), so a call made before sign-in does nothing and the
     * auth-state listener in UserRepository calls it again afterwards.
     */
    fun start() {
        if (registration != null) return
        if (FirebaseAuth.getInstance().currentUser == null) return

        registration = FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .document(DOC)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listener failed: ${error.message}")
                    _walls.postValue(emptyList())
                    return@addSnapshotListener
                }
                _walls.postValue(OfferwallEntry.parseAll(snapshot?.data))
            }
    }

    /** Stops listening, so sign-out is not left reading as the old user. */
    fun stop() {
        registration?.remove()
        registration = null
        // An empty list rather than back to unanswered: this is an answer -
        // a signed-out user has no walls - and it should hide the tab rather
        // than leave the bar on a remembered yes.
        _walls.postValue(emptyList())
    }
}
