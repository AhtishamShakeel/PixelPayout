package com.pixelpayout.data.repository

import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.pixelpayout.data.model.RedemptionRequest
import com.pixelpayout.data.model.RedemptionOption
import com.pixelpayout.data.model.RedemptionType
import java.util.*
import java.util.Calendar
import com.google.firebase.firestore.FieldValue
import com.pixelpayout.ui.redemption.ReferralResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
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
            firestore.collection("users").document(userId)
                .addSnapshotListener { snapshot, _ ->
                    snapshot?.let {
                        _userData.postValue(
                            UserData(
                                points = it.getLong("points")?.toInt() ?: 0,
                                quizAttempts = it.getLong("quizAttempts")?.toInt() ?: 0,
                                lastQuizDate = it.getTimestamp("lastQuizDate")
                            )
                        )
                    }
                }
        }
    }

    data class UserData(
        val points: Int,
        val quizAttempts: Int,
        val lastQuizDate: Timestamp?
    )


    fun updateUserPoints(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                val currentPoints = document.getLong("points")?.toInt() ?: 0
                val newTotal = currentPoints + pointsToAdd
                val referredBy = document.getString("referredBy") // Get referrer ID
                val referralRewardClaimed = document.getBoolean("referralRewardClaimed") ?: false


                // Update points
                firestore.collection("users").document(userId)
                    .update("points", FieldValue.increment(pointsToAdd.toLong()))
                    .addOnSuccessListener {
                        onComplete(newTotal)

                        // If user was referred and has reached 50 points, reward referrer
                        if (newTotal >= 100 && referredBy != null && !referralRewardClaimed) {
                            giveReferralReward(referredBy, userId)
                        }
                    }
            }
    }

    // Function to give 100 points to the referrer
    private fun giveReferralReward(referrerId: String, referredUserId: String) {
        val referrerRef = firestore.collection("users").document(referrerId)
        val referredUserRef = firestore.collection("users").document(referredUserId)
        firestore.runTransaction { transaction ->
            val referrerDoc = transaction.get(referrerRef)
            val currentPoints = referrerDoc.getLong("points") ?: 0
            transaction.update(referrerRef, "points", currentPoints + 100)

            transaction.update(referredUserRef, "referralRewardClaimed", true)
        }
    }


    suspend fun submitReferral(referralCode: String): ReferralResult {
        return try {
            val currentUser = auth.currentUser ?: throw Exception("User not logged in")

            // Check if user has already used a referral code
            val userDoc = firestore.collection("users")
                .document(currentUser.uid)
                .get()
                .await()

            if (userDoc.getBoolean("hasUsedReferral") == true) {
                return ReferralResult.AlreadyUsed
            }

            // Look up the referral code
            val referralQuery = firestore.collection("users")
                .whereEqualTo("referralCode", referralCode)
                .get()
                .await()

            if (referralQuery.isEmpty) {
                return ReferralResult.InvalidCode
            }

            val referrerDoc = referralQuery.documents.first()
            val referrerId = referrerDoc.id

            // Don't allow self-referral
            if (referrerId == currentUser.uid) {
                return ReferralResult.InvalidCode
            }

            // Store referral relationship (without giving points yet)
            val userRef = firestore.collection("users").document(currentUser.uid)
            firestore.runTransaction { transaction ->
                transaction.update(
                    userRef,
                    mapOf(
                        "hasUsedReferral" to true,
                        "referredBy" to referrerId
                    )
                )
                val currentPoints = userDoc.getLong("points") ?: 0
                transaction.update(userRef, "points", currentPoints + 50)
            }.await()

            ReferralResult.Success
        } catch (e: Exception) {
            ReferralResult.Error(e.message ?: "Unknown error occurred")
        }
    }
}