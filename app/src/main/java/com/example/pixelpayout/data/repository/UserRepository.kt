package com.example.pixelpayout.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.example.pixelpayout.ui.redemption.ReferralResult

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData

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
            }
        }
    }

    private fun setupRealtimeUpdates(userId: String) {
        auth.currentUser?.uid?.let { userId ->
            firestore.collection(COLLECTION_USERS).document(userId)
                .addSnapshotListener { snapshot, _ ->
                    snapshot?.let {
                        _userData.postValue(
                            UserData(
                                points = it.getLong(FIELD_POINTS)?.toInt() ?: 0
                            )
                        )
                    }
                }
        }
    }

    data class UserData(
        val points: Int
    )

    data class RewardClaimResult(
        val pointsAwarded: Int,
        val totalPoints: Int
    )

    suspend fun claimGameReward(gameId: String, score: Int): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "game",
                "gameId" to gameId,
                "score" to score
            )
        )
    }

    suspend fun claimQuizReward(quizId: String, wasCorrect: Boolean): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "quiz",
                "quizId" to quizId,
                "wasCorrect" to wasCorrect
            )
        )
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
            totalPoints = data.getInt("totalPoints")
        )
    }

    private fun Map<*, *>.getInt(key: String): Int {
        return (this[key] as? Number)?.toInt()
            ?: throw IllegalStateException("Missing reward field: $key")
    }

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val FIELD_POINTS = "points"
    }
}
