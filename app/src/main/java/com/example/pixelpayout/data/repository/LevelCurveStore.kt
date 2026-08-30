package com.example.pixelpayout.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * The published economy config: the XP curve, what each level pays, the
 * referral threshold and the daily goal pool. All one Firestore document
 * (config/levelCurve), so all one store.
 *
 * WHY THIS IS A PROCESS-LEVEL OBJECT, not a field on UserRepository - the
 * same reason RedemptionOptionsStore is: the repository is constructed per
 * view model, and there are nine `UserRepository()` call sites. As an
 * instance field this document was re-fetched by each one, so a session that
 * opened Home, Profile and Wallet paid four server reads for a document that
 * only changes when somebody deploys or edits the console. Now it is read at
 * most once per process.
 *
 * TWO READS, and the difference is what they cost:
 *
 *   * the CACHE read is free and instant, so the level card and the goals
 *     card paint on the first frame instead of sitting blank while the
 *     network answers,
 *   * the SERVER read follows it, once, and is the only billed one. It is
 *     still worth paying: `levelRewards` is editable in the Firebase console
 *     (see publishLevelCurve), so a client that only ever read its disk copy
 *     would keep showing yesterday's numbers on the Level rewards screen
 *     until app data was cleared.
 *
 * Being briefly stale is fine and being permanently stale is not, which is
 * what that pair buys. Nothing here decides what is PAID either way - the
 * server re-reads the same document when it awards, so this copy is only
 * ever what the app displays.
 */
object LevelCurveStore {

    private const val TAG = "LevelCurve"
    private const val COLLECTION_CONFIG = "config"
    private const val DOC_LEVEL_CURVE = "levelCurve"

    private const val FIELD_THRESHOLDS = "thresholds"
    private const val FIELD_MAX_LEVEL = "maxLevel"
    private const val FIELD_LEVEL_REWARDS = "levelRewards"
    private const val FIELD_REFERRAL_UNLOCK_XP = "referralUnlockXp"
    private const val FIELD_GOAL_POOL = "dailyGoalPool"
    private const val FIELD_GOAL_KINDS = "dailyGoalKinds"

    private val _curve = MutableLiveData<UserRepository.LevelCurve?>(null)
    val curve: LiveData<UserRepository.LevelCurve?> = _curve

    /**
     * The goal pool rides on the same document, so today's goals cost no read
     * of their own. Empty until the document lands, which the goals card
     * renders as "not loaded" rather than as "none".
     */
    private val _goalPool = MutableLiveData(DailyGoalEngine.GoalPool())
    val goalPool: LiveData<DailyGoalEngine.GoalPool> = _goalPool

    /** Guards the pair of reads, so nine repositories still cause one. */
    private var loadStarted = false

    /**
     * Cache first for an instant paint, then the server once for accuracy.
     * Safe to call from every repository and on every sign-in; the second
     * call onwards is a no-op.
     */
    fun load() {
        if (loadStarted) return
        loadStarted = true

        val doc = FirebaseFirestore.getInstance()
            .collection(COLLECTION_CONFIG).document(DOC_LEVEL_CURVE)

        doc.get(Source.CACHE)
            .addOnSuccessListener { publish(it) }
            .addOnCompleteListener {
                // Runs whether or not the cache had anything - a first launch
                // has an empty cache and must still reach the server.
                doc.get(Source.SERVER)
                    .addOnSuccessListener { publish(it) }
                    .addOnFailureListener { error ->
                        // The cached copy (if any) stands. A failure here just
                        // means the UI shows lifetime XP without a bar.
                        Log.w(TAG, "Curve read failed: ${error.message}")
                    }
            }
    }

    private fun publish(snapshot: DocumentSnapshot) {
        if (!snapshot.exists()) return

        val thresholds = (snapshot.get(FIELD_THRESHOLDS) as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
        val maxLevel = snapshot.getLong(FIELD_MAX_LEVEL)?.toInt()

        if (!thresholds.isNullOrEmpty() && maxLevel != null) {
            _curve.postValue(
                UserRepository.LevelCurve(
                    maxLevel = maxLevel,
                    thresholds = thresholds,
                    levelRewards = parseLevelRewards(snapshot.get(FIELD_LEVEL_REWARDS)),
                    referralUnlockXp =
                        snapshot.getLong(FIELD_REFERRAL_UNLOCK_XP)?.toInt() ?: 0
                )
            )
        }

        _goalPool.postValue(
            parseGoalPool(
                snapshot.get(FIELD_GOAL_POOL),
                snapshot.get(FIELD_GOAL_KINDS)
            )
        )
    }

    /**
     * The reward table, whose keys are level numbers Firestore stores as
     * strings.
     *
     * Written defensively because this field is hand-editable in the console:
     * a malformed entry is skipped rather than shown, so a typo costs one
     * missing row on the ladder instead of a crash. The SERVER validates the
     * same field all-or-nothing before paying anything from it.
     */
    private fun parseLevelRewards(raw: Any?): Map<Int, Int> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        return map.mapNotNull { (key, value) ->
            val level = (key as? String)?.toIntOrNull() ?: (key as? Number)?.toInt()
            val points = (value as? Number)?.toInt()
            if (level != null && points != null && points > 0) level to points else null
        }.toMap()
    }

    /**
     * The published goal pool. Anything malformed yields an empty pool, which
     * the card shows as "still loading" - never a guessed set of goals, which
     * would promise a target the server does not require.
     */
    private fun parseGoalPool(rawPool: Any?, rawKinds: Any?): DailyGoalEngine.GoalPool {
        val templates = (rawPool as? List<*>)?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val kind = map["kind"] as? String ?: return@mapNotNull null
            val target = (map["target"] as? Number)?.toInt() ?: return@mapNotNull null
            DailyGoalEngine.GoalTemplate(id, kind, target)
        }.orEmpty()

        val kinds = (rawKinds as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        return DailyGoalEngine.GoalPool(templates, kinds)
    }
}
