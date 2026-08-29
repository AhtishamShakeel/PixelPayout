package com.example.pixelpayout.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.model.RedemptionPack
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * The redemption catalogue, read once per process and kept fresh after that.
 *
 * Why a process-level object rather than a method on UserRepository: the
 * repository is constructed per view model (MainViewModel, ReferralViewModel
 * and the referral dialog each construct their own), so a member cache would
 * be a cache per screen - which is exactly the repeated loading this replaces.
 *
 * How the caching works, and why nothing is written to DataStore here:
 *
 *   * Firestore keeps its own on-disk copy of every document it has seen.
 *     A snapshot listener fires from that copy FIRST, so on the second and
 *     every later visit to Wallet the grid is painted before any network call
 *     resolves.
 *   * The same listener then delivers whatever the server has. Editing a
 *     document in the Firebase console - a price, a name, `enabled` - pushes
 *     through to the open screen without a restart, and only the documents
 *     that actually changed are billed as reads.
 *
 * A hand-rolled JSON cache in DataStore would duplicate the first half and
 * lose the second, so there isn't one.
 */
object RedemptionOptionsStore {

    private const val TAG = "RedemptionOptions"
    private const val COLLECTION = "redemptionOptions"

    private val _games = MutableLiveData<List<RedemptionGame>>(emptyList())
    val games: LiveData<List<RedemptionGame>> = _games

    /** True until the first snapshot - cached or otherwise - has arrived. */
    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    private var registration: ListenerRegistration? = null

    /**
     * Starts listening, at most once.
     *
     * Safe to call from every screen that shows the catalogue and on every
     * onViewCreated; the second call onwards is a no-op. Listing the
     * collection requires an authenticated user (see firestore.rules), so a
     * call made before sign-in does nothing and the auth listener in
     * [UserRepository] picks it up instead.
     */
    fun start() {
        if (registration != null) return
        if (FirebaseAuth.getInstance().currentUser == null) return

        _isLoading.value = _games.value.isNullOrEmpty()

        registration = FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e(TAG, "Listener failed: ${error?.message}")
                    _isLoading.postValue(false)
                    return@addSnapshotListener
                }

                val games = snapshot.documents
                    .mapNotNull(::parseGame)
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))

                _games.postValue(games)

                // An empty snapshot off a COLD disk cache means "not asked
                // yet", not "nothing on offer" - Firestore delivers that
                // before it has spoken to the server at all. Staying in the
                // loading state until the server answers is what stops the
                // first launch flashing an empty catalogue at a user whose
                // games are about to arrive.
                _isLoading.postValue(snapshot.metadata.isFromCache && snapshot.isEmpty)
            }
    }

    /**
     * Drops the listener. Nothing calls this today - the catalogue is small
     * and wanted by two tabs - but it exists so sign-out has a way to stop
     * listening with someone else's credentials.
     */
    fun stop() {
        registration?.remove()
        registration = null
        _games.value = emptyList()
        _isLoading.value = true
    }

    private fun parseGame(doc: DocumentSnapshot): RedemptionGame? {
        val name = doc.getString("name") ?: return null

        // Absent `enabled` counts as disabled: a half-written game should not
        // go on sale on the strength of a missing field. The server applies
        // the same rule when the redemption is actually attempted.
        if (doc.getBoolean("enabled") != true) return null

        val packs = parsePacks(doc.get("packs"))
        // A game with nothing to buy is not a game the grid should offer.
        if (packs.isEmpty()) return null

        return RedemptionGame(
            id = doc.id,
            name = name,
            code = doc.getString("code")?.trim().orEmpty().ifEmpty { name.take(2).uppercase() },
            subtitle = doc.getString("subtitle").orEmpty(),
            packs = packs,
            imageUrl = doc.getString("imageUrl"),
            minLevel = doc.getLong("minLevel")?.toInt() ?: 1,
            sortOrder = doc.getLong("sortOrder")?.toInt() ?: 0,
            idLabel = doc.getString("idLabel")?.trim().orEmpty().ifEmpty { "Player ID" },
            idHint = doc.getString("idHint").orEmpty(),
            idMinLength = doc.getLong("idMinLength")?.toInt()
                ?: RedemptionGame.DEFAULT_ID_MIN_LENGTH,
            requiresUsername = doc.getBoolean("requiresUsername") == true,
            usernameLabel = doc.getString("usernameLabel")?.trim().orEmpty()
                .ifEmpty { "In-game username" },
            servers = (doc.get("servers") as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                .orEmpty()
        )
    }

    /**
     * `packs` is a MAP keyed by pack id, not an array.
     *
     * A map so the server can resolve `packs[packId]` in one lookup, and so a
     * single pack can be repriced or switched off in the console without
     * rewriting the whole list - Firestore cannot partially update an array
     * element.
     */
    private fun parsePacks(raw: Any?): List<RedemptionPack> {
        val map = raw as? Map<*, *> ?: return emptyList()

        return map.entries.mapNotNull { (key, value) ->
            val id = key as? String ?: return@mapNotNull null
            val fields = value as? Map<*, *> ?: return@mapNotNull null

            // A pack is live unless explicitly disabled - the opposite
            // default to the game itself. A pack only exists because someone
            // typed it into a game that had to be explicitly enabled, so the
            // half-written-document risk is already covered one level up.
            if (fields["enabled"] == false) return@mapNotNull null

            val cost = (fields["pointsCost"] as? Number)?.toInt() ?: return@mapNotNull null
            if (cost <= 0) return@mapNotNull null

            val amount = (fields["amount"] as? String)?.trim().orEmpty()
            if (amount.isEmpty()) return@mapNotNull null

            RedemptionPack(
                id = id,
                amount = amount,
                pointsCost = cost,
                note = (fields["note"] as? String).orEmpty(),
                tag = (fields["tag"] as? String)?.trim()?.takeIf(String::isNotEmpty),
                sortOrder = (fields["sortOrder"] as? Number)?.toInt() ?: 0,
                firstRedeemCost = (fields["firstRedeemCost"] as? Number)?.toInt()
                    ?.takeIf { it >= 0 }
            )
        }.sortedWith(compareBy({ it.sortOrder }, { it.pointsCost }))
    }
}
