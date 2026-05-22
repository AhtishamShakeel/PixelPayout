package com.example.pixelpayout.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Transaction
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


    fun updateUserPoints(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        val userRef = firestore.collection(COLLECTION_USERS).document(userId)

        firestore.runTransaction { transaction ->
            val userDoc = transaction.get(userRef) // This read is part of the transaction, avoids double read
            val currentPoints = userDoc.getLong(FIELD_POINTS)?.toInt() ?: 0
            val referredBy = userDoc.getString(FIELD_REFERRED_BY)
            val referralRewardClaimed = userDoc.getBoolean(FIELD_REFERRAL_REWARD_CLAIMED) ?: false

            val newTotal = currentPoints + pointsToAdd
            transaction.update(userRef, FIELD_POINTS, FieldValue.increment(pointsToAdd.toLong()))

            applyReferralRewardIfEligible(transaction, userRef, referredBy, referralRewardClaimed, newTotal)

            newTotal
        }.addOnSuccessListener { newTotal ->
            onComplete(newTotal) // Update UI
        }.addOnFailureListener {
            onComplete(0) // Handle errors
        }
    }

    fun updateUserPointsAndAttempts(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        val userRef = firestore.collection(COLLECTION_USERS).document(userId)

        firestore.runTransaction { transaction ->
            val userDoc = transaction.get(userRef) // This read is part of the transaction, avoids double read
            val currentPoints = userDoc.getLong(FIELD_POINTS)?.toInt() ?: 0
            val referredBy = userDoc.getString(FIELD_REFERRED_BY)
            val referralRewardClaimed = userDoc.getBoolean(FIELD_REFERRAL_REWARD_CLAIMED) ?: false

            val newTotal = currentPoints + pointsToAdd
            transaction.update(userRef, mapOf(
                FIELD_POINTS to FieldValue.increment(pointsToAdd.toLong()),
                FIELD_QUIZ_ATTEMPTS to FieldValue.increment(1)
            ))
            applyReferralRewardIfEligible(transaction, userRef, referredBy, referralRewardClaimed, newTotal)

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
            val userDoc = firestore.collection(COLLECTION_USERS)
                .document(currentUser.uid)
                .get()
                .await()

            if (userDoc.getBoolean(FIELD_HAS_USED_REFERRAL) == true) {
                return ReferralResult.AlreadyUsed
            }

            // Look up the referral code
            val referralQuery = firestore.collection(COLLECTION_USERS)
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

            // Store referral relationship and give the referred user their signup bonus.
            val userRef = firestore.collection(COLLECTION_USERS).document(currentUser.uid)
            firestore.runTransaction { transaction ->
                transaction.update(
                    userRef,
                    mapOf(
                        FIELD_HAS_USED_REFERRAL to true,
                        FIELD_REFERRED_BY to referrerId
                    )
                )
                transaction.update(userRef, FIELD_POINTS, FieldValue.increment(REFERRED_USER_REWARD_POINTS.toLong()))
            }.await()

            ReferralResult.Success
        } catch (e: Exception) {
            ReferralResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    private fun applyReferralRewardIfEligible(
        transaction: Transaction,
        userRef: DocumentReference,
        referredBy: String?,
        referralRewardClaimed: Boolean,
        newTotal: Int
    ) {
        if (newTotal >= REFERRAL_REWARD_UNLOCK_POINTS && referredBy != null && !referralRewardClaimed) {
            val referrerRef = firestore.collection(COLLECTION_USERS).document(referredBy)
            transaction.update(referrerRef, FIELD_POINTS, FieldValue.increment(REFERRER_REWARD_POINTS.toLong()))
            transaction.update(userRef, FIELD_REFERRAL_REWARD_CLAIMED, true)
        }
    }

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val FIELD_POINTS = "points"
        private const val FIELD_QUIZ_ATTEMPTS = "quiz_attempts"
        private const val FIELD_HAS_USED_REFERRAL = "hasUsedReferral"
        private const val FIELD_REFERRED_BY = "referredBy"
        private const val FIELD_REFERRAL_REWARD_CLAIMED = "referralRewardClaimed"

        private const val REFERRED_USER_REWARD_POINTS = 50
        private const val REFERRER_REWARD_POINTS = 100
        private const val REFERRAL_REWARD_UNLOCK_POINTS = 100
    }
}
