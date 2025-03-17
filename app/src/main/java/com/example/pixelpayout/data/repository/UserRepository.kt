package com.example.pixelpayout.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import com.example.pixelpayout.ui.redemption.ReferralResult

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
                                points = it.getLong("points")?.toInt() ?: 0
                            )
                        )
                    }
                }
        }
    }

    data class UserData(
        val points: Int
    )


    fun updateUserPoints(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        val userRef = firestore.collection("users").document(userId)

        firestore.runTransaction { transaction ->
            val userDoc = transaction.get(userRef) // This read is part of the transaction, avoids double read
            val currentPoints = userDoc.getLong("points")?.toInt() ?: 0
            val referredBy = userDoc.getString("referredBy")
            val referralRewardClaimed = userDoc.getBoolean("referralRewardClaimed") ?: false

            val newTotal = currentPoints + pointsToAdd
            transaction.update(userRef, "points", FieldValue.increment(pointsToAdd.toLong()))

            // Referral condition check within the transaction
            if (newTotal >= 100 && referredBy != null && !referralRewardClaimed) {
                val referrerRef = firestore.collection("users").document(referredBy)
                val referrerDoc = transaction.get(referrerRef) // This read only happens if needed
                val referrerPoints = referrerDoc.getLong("points") ?: 0

                transaction.update(referrerRef, "points", referrerPoints + 100)
                transaction.update(userRef, "referralRewardClaimed", true)
            }

            newTotal
        }.addOnSuccessListener { newTotal ->
            onComplete(newTotal) // Update UI
        }.addOnFailureListener {
            onComplete(0) // Handle errors
        }
    }

    fun updateUserPointsAndAttempts(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        val userRef = firestore.collection("users").document(userId)

        firestore.runTransaction { transaction ->
            val userDoc = transaction.get(userRef) // This read is part of the transaction, avoids double read
            val currentPoints = userDoc.getLong("points")?.toInt() ?: 0
            val referredBy = userDoc.getString("referredBy")
            val referralRewardClaimed = userDoc.getBoolean("referralRewardClaimed") ?: false

            val newTotal = currentPoints + pointsToAdd
            transaction.update(userRef, mapOf(
                "points" to FieldValue.increment(pointsToAdd.toLong()),
                "quiz_attempts" to FieldValue.increment(1)
            ))
            // Referral condition check within the transaction
            if (newTotal >= 100 && referredBy != null && !referralRewardClaimed) {
                val referrerRef = firestore.collection("users").document(referredBy)
                val referrerDoc = transaction.get(referrerRef) // This read only happens if needed
                val referrerPoints = referrerDoc.getLong("points") ?: 0

                transaction.update(referrerRef, "points", referrerPoints + 100)
                transaction.update(userRef, "referralRewardClaimed", true)
            }

            newTotal
        }.addOnSuccessListener { newTotal ->
            onComplete(newTotal) // Update UI
        }.addOnFailureListener {
            onComplete(0) // Handle errors
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

    fun getDailyAttempts(onResult: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                val attempts = document.getLong("quiz_attempts")?.toInt() ?: 0
                onResult(attempts)
            }
    }

    fun incrementDailyAttempts(onComplete: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("users").document(userId)
            .update("quiz_attempts", FieldValue.increment(1))
            .addOnSuccessListener { onComplete() }
    }

}