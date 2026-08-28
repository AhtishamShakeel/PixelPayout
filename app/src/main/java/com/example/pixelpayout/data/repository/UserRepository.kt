package com.example.pixelpayout.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.example.pixelpayout.data.model.RedemptionOption
import com.example.pixelpayout.data.model.RedemptionType
import com.example.pixelpayout.ui.redemption.RedemptionResult
import com.example.pixelpayout.ui.redemption.ReferralResult
import com.example.pixelpayout.utils.ServerClock

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData

    private val _levelCurve = MutableLiveData<LevelCurve?>()
    val levelCurve: LiveData<LevelCurve?> = _levelCurve

    init {
        waitForUserLogin()
    }

    fun getCurrentUserId(): String? {
        return auth.currentUser?.uid
    }
    private fun waitForUserLogin() {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            auth.currentUser?.uid?.let { userId ->
                setupRealtimeUpdates(userId)  // ✅ Ensure setup runs AFTER login
                fetchLevelCurve()
            }
        }
    }

    /**
     * The XP thresholds are published by the server so the client never
     * duplicates the curve (which would drift the moment it's retuned).
     * Fetched once per session; a failure just means the UI falls back to
     * showing lifetime XP without a progress bar.
     */
    private fun fetchLevelCurve() {
        if (_levelCurve.value != null) return

        firestore.collection(COLLECTION_CONFIG).document(DOC_LEVEL_CURVE).get()
            .addOnSuccessListener { snapshot ->
                val thresholds = (snapshot.get(FIELD_THRESHOLDS) as? List<*>)
                    ?.mapNotNull { (it as? Number)?.toInt() }
                val maxLevel = snapshot.getLong(FIELD_MAX_LEVEL)?.toInt()

                if (!thresholds.isNullOrEmpty() && maxLevel != null) {
                    _levelCurve.postValue(LevelCurve(maxLevel, thresholds))
                }
            }
    }

    data class LevelCurve(
        val maxLevel: Int,
        /** Cumulative XP required to reach level (index + 2). */
        val thresholds: List<Int>
    ) {
        /** Total XP needed to reach [level]; 0 for level 1. */
        fun xpRequiredFor(level: Int): Int = when {
            level <= 1 -> 0
            else -> thresholds.getOrElse(minOf(level, maxLevel) - 2) { thresholds.last() }
        }
    }

    private fun setupRealtimeUpdates(userId: String) {
        auth.currentUser?.uid?.let { userId ->
            firestore.collection(COLLECTION_USERS).document(userId)
                .addSnapshotListener { snapshot, error ->

                    Log.d("BUFF_DEBUG", "Snapshot listener fired")
                    Log.d("BUFF_DEBUG", "Error = $error")
                    Log.d("BUFF_DEBUG", "Snapshot exists = ${snapshot?.exists()}")
                    Log.d(
                        "BUFF_DEBUG",
                        "Raw activeBuff = ${snapshot?.get(FIELD_ACTIVE_BUFF)}"
                    )

                    snapshot?.let {
                        _userData.postValue(
                            UserData(
                                points = it.getLong(FIELD_POINTS)?.toInt() ?: 0,
                                xp = it.getLong(FIELD_XP)?.toInt() ?: 0,
                                level = it.getLong(FIELD_LEVEL)?.toInt() ?: 1,
                                activeBuff = parseBuff(it.get(FIELD_ACTIVE_BUFF)),
                                activeXpBuff = parseBuff(it.get(FIELD_ACTIVE_XP_BUFF))
                            )
                        )
                    }
                }
        }
    }

    data class UserData(
        val points: Int,
        val xp: Int = 0,
        val level: Int = 1,
        val activeBuff: TimedBuff? = null,
        val activeXpBuff: TimedBuff? = null
    )

    /**
     * A temporary multiplier with an expiry. The same shape backs both the
     * Points buff and the XP buff - which one it is comes from the field it
     * was read out of, not from anything in here.
     *
     * Named for its shape rather than its effect on purpose: it was called
     * PointsBuff while also being labelled an "XP boost" in the UI, and the
     * two drifted apart precisely because the type claimed to know.
     *
     * Server-side eligibility decides what a buff actually reaches:
     * MULTIPLIER_ELIGIBLE for Points, XP_MULTIPLIER_ELIGIBLE for XP.
     */
    data class TimedBuff(
        val multiplier: Double,
        val expiresAtMillis: Long
    ) {
        /**
         * Measured against the server's clock, not the device's: expiresAt was
         * issued by the server, so comparing it to device time makes a wrong
         * device clock look like an expired buff.
         */
        fun isActive(nowMillis: Long = ServerClock.now()): Boolean =
            multiplier > 1.0 && expiresAtMillis > nowMillis
    }

    private fun parseBuff(raw: Any?): TimedBuff? {
        val map = raw as? Map<*, *> ?: return null
        val multiplier = (map["multiplier"] as? Number)?.toDouble() ?: return null
        val expiresAt = (map["expiresAt"] as? Number)?.toLong() ?: return null
        return TimedBuff(multiplier, expiresAt)
    }

    data class RewardClaimResult(
        val pointsAwarded: Int,
        val totalPoints: Int,
        val xpAwarded: Int = 0,
        val totalXp: Int = 0,
        val level: Int = 1,
        val leveledUp: Boolean = false,
        /** One-time Points bonus from any milestone level reached by this claim. */
        val milestonePoints: Int = 0,
        val wasCorrect: Boolean = false
    )

    suspend fun startGameSession(gameId: String): String {
        val result = functions
            .getHttpsCallable("startGameSession")
            .call(mapOf("gameId" to gameId))
            .await()

        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected game session response")

        return data["sessionId"] as? String
            ?: throw IllegalStateException("Missing game session id")
    }

    suspend fun claimGameReward(gameId: String, score: Int, sessionId: String): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "game",
                "gameId" to gameId,
                "score" to score,
                "sessionId" to sessionId
            )
        )
    }

    /**
     * Correctness is decided by the server against its own answer key - the
     * client no longer reports whether the answer was right.
     */
    suspend fun claimQuizReward(
        category: String,
        quizId: String,
        questionIndex: Int,
        selectedAnswer: Int
    ): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "quiz",
                "category" to category,
                "quizId" to quizId,
                "questionIndex" to questionIndex,
                "selectedAnswer" to selectedAnswer
            )
        )
    }

    /** Redemption options are server-managed; the client only reads them. */
    suspend fun getRedemptionOptions(): List<RedemptionOption> {
        val snapshot = firestore.collection(COLLECTION_REDEMPTION_OPTIONS).get().await()
        return snapshot.documents.mapNotNull { doc ->
            val title = doc.getString("title") ?: return@mapNotNull null
            val cost = doc.getLong("pointsCost")?.toInt() ?: return@mapNotNull null
            if (doc.getBoolean("enabled") != true) return@mapNotNull null

            RedemptionOption(
                id = doc.id,
                title = title,
                description = doc.getString("description").orEmpty(),
                pointsCost = cost,
                type = when (doc.getString("type")) {
                    "EASYPAISA" -> RedemptionType.EASYPAISA
                    else -> RedemptionType.GAME_CURRENCY
                },
                imageUrl = doc.getString("imageUrl"),
                minLevel = doc.getLong("minLevel")?.toInt() ?: 1
            )
        }.sortedBy { it.pointsCost }
    }

    /**
     * Spends points on an option. The cost is decided server-side from the
     * option document - nothing about the price is sent from here.
     */
    suspend fun redeem(optionId: String, payoutNumber: String?): RedemptionResult {
        return try {
            val payload = mutableMapOf<String, Any>("optionId" to optionId)
            payoutNumber?.let { payload["payoutNumber"] = it }

            val result = functions.getHttpsCallable("redeemReward").call(payload).await()
            val data = result.data as? Map<*, *>
                ?: return RedemptionResult.Error("Unexpected response")

            RedemptionResult.Success(
                pointsSpent = (data["pointsSpent"] as? Number)?.toInt() ?: 0,
                remainingPoints = (data["remainingPoints"] as? Number)?.toInt() ?: 0
            )
        } catch (e: FirebaseFunctionsException) {
            RedemptionResult.Error(redemptionErrorMessage(e.message))
        } catch (e: Exception) {
            RedemptionResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    private fun redemptionErrorMessage(raw: String?): String = when (raw) {
        "insufficient_points" -> "You don't have enough stars yet."
        "level_too_low" -> "Reach a higher level to unlock this reward."
        "option_disabled" -> "This reward is no longer available."
        "payout_details_required" -> "Enter a valid phone number."
        "unknown_option" -> "This reward could not be found."
        else -> raw ?: "Redemption failed"
    }

    suspend fun submitReferral(referralCode: String): ReferralResult {
        return try {
            val result = functions
                .getHttpsCallable("submitReferral")
                .call(mapOf("referralCode" to referralCode))
                .await()

            val data = result.data as? Map<*, *>
            when (data?.get("status") as? String) {
                "success" -> ReferralResult.Success
                "invalid_code" -> ReferralResult.InvalidCode
                else -> ReferralResult.Error("Unexpected referral response")
            }
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                ReferralResult.AlreadyUsed
            } else {
                ReferralResult.Error(e.message ?: "Unknown error occurred")
            }
        } catch (e: Exception) {
            ReferralResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    private suspend fun claimReward(payload: Map<String, Any>): RewardClaimResult {
        val result = functions
            .getHttpsCallable("claimReward")
            .call(payload)
            .await()

        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected reward response")

        return RewardClaimResult(
            pointsAwarded = data.getInt("pointsAwarded"),
            totalPoints = data.getInt("totalPoints"),
            xpAwarded = data.optInt("xpAwarded"),
            totalXp = data.optInt("totalXp"),
            level = data.optInt("level", default = 1),
            leveledUp = data["leveledUp"] as? Boolean ?: false,
            milestonePoints = data.optInt("milestonePoints"),
            wasCorrect = data["wasCorrect"] as? Boolean ?: false
        )
    }

    private fun Map<*, *>.getInt(key: String): Int {
        return (this[key] as? Number)?.toInt()
            ?: throw IllegalStateException("Missing reward field: $key")
    }

    private fun Map<*, *>.optInt(key: String, default: Int = 0): Int {
        return (this[key] as? Number)?.toInt() ?: default
    }

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_CONFIG = "config"
        private const val COLLECTION_REDEMPTION_OPTIONS = "redemptionOptions"
        private const val DOC_LEVEL_CURVE = "levelCurve"
        private const val FIELD_POINTS = "points"
        private const val FIELD_XP = "xp"
        private const val FIELD_LEVEL = "level"
        private const val FIELD_THRESHOLDS = "thresholds"
        private const val FIELD_MAX_LEVEL = "maxLevel"
        private const val FIELD_ACTIVE_BUFF = "activeBuff"
        private const val FIELD_ACTIVE_XP_BUFF = "activeXpBuff"
    }
}
