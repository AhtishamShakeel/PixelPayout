package com.example.pixelpayout.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
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
                listenToPendingRedemptions(userId)
                listenToPayoutFeed()
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
                                activeXpBuff = parseBuff(it.get(FIELD_ACTIVE_XP_BUFF)),
                                streak = Streak(
                                    count = it.getLong(FIELD_STREAK_COUNT)?.toInt() ?: 0,
                                    lastClaimedDayUtc = it.getLong(FIELD_LAST_STREAK_DAY),
                                    lastRewardedDayUtc =
                                        it.getLong(FIELD_LAST_STREAK_REWARD_DAY)
                                )
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
        val activeXpBuff: TimedBuff? = null,
        val streak: Streak = Streak()
    )

    /**
     * The daily streak, as stored on the user document.
     *
     * [lastClaimedDayUtc] is whole UTC days since the epoch, matching the
     * server's own representation. An integer rather than a timestamp because
     * the only question asked of it is how many days ago - and because it
     * makes a streak trivial to set up by hand while testing.
     */
    data class Streak(
        val count: Int = 0,
        val lastClaimedDayUtc: Long? = null,
        /**
         * Tracked apart from [lastClaimedDayUtc] on purpose. The streak
         * advances whether or not an ad played; the reward does not. Until
         * this catches up, today's reward is still there to be claimed.
         */
        val lastRewardedDayUtc: Long? = null
    ) {
        /**
         * A streak survives one missed midnight, not two: yesterday still
         * counts, anything older has already lapsed.
         */
        fun isAlive(todayUtc: Long): Boolean =
            lastClaimedDayUtc != null && todayUtc - lastClaimedDayUtc <= 1

        /**
         * The streak has already advanced for today.
         *
         * `>=`, not `==`, to mirror resolveStreakClaim server-side: a last
         * claim dated in the future is treated as already claimed rather than
         * as a lapse, so a corrupt field cannot wipe a real streak. Testing
         * with a hand-set future date lands here too, and the client has to
         * agree with the server about it or the dialog names a day the claim
         * will not pay for.
         */
        fun movedOn(todayUtc: Long): Boolean =
            lastClaimedDayUtc != null && lastClaimedDayUtc >= todayUtc

        /** Today's reward has been paid. */
        fun rewardedOn(todayUtc: Long): Boolean = lastRewardedDayUtc == todayUtc

        /** Days completed in the current seven-day cycle, 0 when lapsed. */
        fun cyclePosition(todayUtc: Long, cycleDays: Int): Int {
            if (!isAlive(todayUtc) || count <= 0) return 0
            return (count - 1) % cycleDays + 1
        }
    }

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

    /** One day of the streak cycle, as the server describes it. */
    data class StreakDayReward(val points: Int, val xp: Int)

    sealed class StreakClaimResult {
        /** The reward was paid. */
        data class Rewarded(
            val day: Int,
            val pointsAwarded: Int,
            val xpAwarded: Int
        ) : StreakClaimResult()

        /**
         * The streak moved on but nothing was paid - no ad was watched, or
         * today was already rewarded. Not an error: with no ad the reward is
         * simply still waiting, and the user can try again all day.
         */
        data class NotRewarded(val day: Int, val reason: String?) : StreakClaimResult()

        data class Error(val message: String) : StreakClaimResult()
    }

    /**
     * Advances the streak and, if [adWatched], pays today's reward.
     *
     * [adWatched] is only ever true when AdMob's onRewarded callback fired.
     * The server treats it as a claim, not proof - see resolveStreakReward for
     * why that is still worth gating on.
     */
    suspend fun claimDailyStreak(adWatched: Boolean): StreakClaimResult {
        return try {
            // Well under the 70s default. This call happens with the user
            // watching the button, and offline it would otherwise hang for
            // over a minute with the button disabled and nothing to explain
            // why. Failing fast lets them retry - the reward is still there,
            // because a call that never reached the server changed nothing.
            val result = functions
                .getHttpsCallable("claimDailyStreak")
                .withTimeout(20, TimeUnit.SECONDS)
                .call(mapOf("adWatched" to adWatched))
                .await()
            val data = result.data as? Map<*, *>
                ?: return StreakClaimResult.Error("Unexpected response")

            val day = (data["day"] as? Number)?.toInt() ?: 0

            if (data["rewarded"] == true) {
                StreakClaimResult.Rewarded(
                    day = day,
                    pointsAwarded = (data["pointsAwarded"] as? Number)?.toInt() ?: 0,
                    xpAwarded = (data["xpAwarded"] as? Number)?.toInt() ?: 0
                )
            } else {
                StreakClaimResult.NotRewarded(day, data["reason"] as? String)
            }
        } catch (e: Exception) {
            StreakClaimResult.Error(e.message ?: "Could not claim your streak")
        }
    }

    /**
     * The reward for every day of the cycle, so the strip can show what each
     * day pays before anything is claimed. Read from the server rather than
     * duplicated here, for the same reason the level curve is.
     */
    suspend fun getStreakConfig(): List<StreakDayReward> {
        return try {
            val result = functions.getHttpsCallable("getStreakConfig").call().await()
            parseCycle((result.data as? Map<*, *>)?.get("cycle"))
        } catch (e: Exception) {
            Log.e("Streak", "Could not load streak config: ${e.message}")
            emptyList()
        }
    }

    private fun parseCycle(raw: Any?): List<StreakDayReward> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            StreakDayReward(
                points = (map["points"] as? Number)?.toInt() ?: 0,
                xp = (map["xp"] as? Number)?.toInt() ?: 0
            )
        }
    }

    /**
     * What the user has redeemed and is waiting on.
     *
     * [title] and [requestedAtMillis] describe the OLDEST outstanding request -
     * the one that will clear first, and so the one a countdown should track.
     */
    data class PendingRedemptions(
        val count: Int,
        val title: String,
        val requestedAtMillis: Long?
    )

    private val _pendingRedemptions = MutableLiveData(PendingRedemptions(0, "", null))
    val pendingRedemptions: LiveData<PendingRedemptions> = _pendingRedemptions

    /** One entry in the public payout feed. The name arrives already masked. */
    data class PayoutFeedEntry(val name: String, val optionTitle: String, val atMillis: Long)

    private val _payoutFeed = MutableLiveData<List<PayoutFeedEntry>>(emptyList())
    val payoutFeed: LiveData<List<PayoutFeedEntry>> = _payoutFeed

    /**
     * Listens to this user's unresolved redemptions.
     *
     * Two equality filters and no ordering, deliberately: Firestore serves
     * equality-only queries from its automatic single-field indexes, so this
     * needs no composite index to be deployed alongside. The handful of
     * documents involved are sorted here instead.
     *
     * The uid filter is also what makes the query legal - the rules allow a
     * read only where the document's uid matches the caller, and a query has
     * to prove that up front rather than per document.
     */
    private fun listenToPendingRedemptions(userId: String) {
        firestore.collection(COLLECTION_REDEMPTIONS)
            .whereEqualTo(FIELD_UID, userId)
            .whereEqualTo(FIELD_STATUS, STATUS_PENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e("Redemption", "Pending listener failed: ${error?.message}")
                    return@addSnapshotListener
                }

                // The oldest request is the one closest to being ready, so it
                // is the one the row describes.
                val oldest = snapshot.documents
                    .filter { it.getTimestamp(FIELD_CREATED_AT) != null }
                    .minByOrNull { it.getTimestamp(FIELD_CREATED_AT)!!.toDate().time }

                _pendingRedemptions.postValue(
                    PendingRedemptions(
                        count = snapshot.size(),
                        title = oldest?.getString(FIELD_OPTION_TITLE).orEmpty(),
                        requestedAtMillis =
                            oldest?.getTimestamp(FIELD_CREATED_AT)?.toDate()?.time
                    )
                )
            }
    }

    /**
     * The public payout feed - other people's approved redemptions, names
     * already masked server-side. Nothing identifying is in this collection;
     * see economy/payoutFeed.ts for why it is separate from `redemptions`.
     */
    private fun listenToPayoutFeed() {
        firestore.collection(COLLECTION_PAYOUT_FEED)
            .orderBy(FIELD_APPROVED_AT, Query.Direction.DESCENDING)
            .limit(PAYOUT_FEED_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e("PayoutFeed", "Feed listener failed: ${error?.message}")
                    return@addSnapshotListener
                }

                _payoutFeed.postValue(
                    snapshot.documents.mapNotNull { doc ->
                        val at = doc.getTimestamp(FIELD_APPROVED_AT)?.toDate()?.time
                            ?: return@mapNotNull null
                        PayoutFeedEntry(
                            name = doc.getString("name").orEmpty(),
                            optionTitle = doc.getString("optionTitle").orEmpty(),
                            atMillis = at
                        )
                    }
                )
            }
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
        private const val FIELD_STREAK_COUNT = "streakCount"
        private const val FIELD_LAST_STREAK_DAY = "lastStreakDayUtc"
        private const val FIELD_LAST_STREAK_REWARD_DAY = "lastStreakRewardDayUtc"

        private const val COLLECTION_REDEMPTIONS = "redemptions"
        private const val COLLECTION_PAYOUT_FEED = "payoutFeed"
        private const val FIELD_UID = "uid"
        private const val FIELD_STATUS = "status"
        private const val STATUS_PENDING = "pending"
        private const val FIELD_OPTION_TITLE = "optionTitle"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_APPROVED_AT = "approvedAt"
        private const val PAYOUT_FEED_LIMIT = 20L
    }
}
