package com.example.pixelpayout.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import com.example.pixelpayout.ui.redemption.ReferralResult

class UserRepository private constructor() {
    companion object {
        private var instance: UserRepository? = null
        fun getInstance(): UserRepository {
            return instance ?: synchronized(this) {
                instance ?: UserRepository().also  { instance = it}
            }
        }
    }
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData

    private var _referredBy: String? = null
    private var _referralRewardClaimed: Boolean = false
    val referredBy: String?
        get() = _referredBy
    val referralRewardClaimed: Boolean
        get() = _referralRewardClaimed

    fun updateReferralData(newReferredBy: String?, newReferralRewardClaimed: Boolean) {
        _referredBy = newReferredBy
        _referralRewardClaimed = newReferralRewardClaimed
        Log.d("ReferralDebug", "Updated referral data: referred=$_referredBy, claimed=$_referralRewardClaimed")
    }

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
        userRef.update("points", FieldValue.increment(pointsToAdd.toLong()))
            .addOnSuccessListener {
                onComplete(pointsToAdd) // UI updates automatically via Firestore snapshot

                // Check referral condition and apply reward if needed
                if (referredBy != null && !referralRewardClaimed) {
                    Log.d("ReferralCheck", "Referred By: $referredBy, Reward Claimed: $referralRewardClaimed")
                    checkAndApplyReferralReward(userId, referredBy!!)
                } else {
                    Log.d("ReferralCheck", "Else: Referred By: $referredBy, Reward Claimed: $referralRewardClaimed")
                }
            }
            .addOnFailureListener {
                onComplete(0) // Handle failure
            }
    }

    fun checkAndApplyReferralReward(userId: String, referredBy: String) {
        val firestore = FirebaseFirestore.getInstance()
        val userRef = firestore.collection("users").document(userId)

        // First, fetch only the user document
        userRef.get().addOnSuccessListener { userDoc ->
            if (!userDoc.exists()) {
                Log.d("Referral", "User document does not exist: $userId")
                return@addOnSuccessListener
            }

            val currentPoints = userDoc.getLong("points") ?: 0
            val referralRewardClaimed = userDoc.getBoolean("referralRewardClaimed") ?: false

            Log.d("Referral", "User points: $currentPoints, Referral claimed: $referralRewardClaimed")

            // Only proceed if the user meets the reward conditions
            if (currentPoints >= 100 && !referralRewardClaimed) {
                val referrerRef = firestore.collection("users").document(referredBy)

                firestore.runTransaction { transaction ->
                    val referrerDoc = transaction.get(referrerRef)

                    if (!referrerDoc.exists()) {
                        Log.d("Referral", "Referrer document does not exist: $referredBy")
                        return@runTransaction
                    }

                    val referrerPoints = referrerDoc.getLong("points") ?: 0

                    // Update referrer points and mark referral as claimed
                    transaction.update(referrerRef, "points", referrerPoints + 100)
                    transaction.update(userRef, "referralRewardClaimed", true)

                    Log.d("Referral", "Referral reward applied! Referrer ($referredBy) gets +100 points.")
                }.addOnSuccessListener {
                    Log.d("Referral", "Transaction successful.")
                }.addOnFailureListener { e ->
                    Log.e("Referral", "Transaction failed: ${e.message}")
                }
            } else {
                Log.d("Referral", "Referral reward NOT applied. Conditions not met.")
            }
        }.addOnFailureListener { e ->
            Log.e("Referral", "Failed to fetch user document: ${e.message}")
        }
    }

    fun updateUserPointsAndAttempts(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        val userRef = firestore.collection("users").document(userId)
        userRef.update(
            mapOf(
                "points" to FieldValue.increment(pointsToAdd.toLong()),
                "quiz_attempts" to FieldValue.increment(1)
            )
        )
            .addOnSuccessListener {
                onComplete(pointsToAdd)

                if (referredBy != null && !referralRewardClaimed) {
                    Log.d("ReferralCheck", "Referred By: $referredBy, Reward Claimed: $referralRewardClaimed")
                    checkAndApplyReferralReward(userId, referredBy!!)
                } else {
                    Log.d("ReferralCheck", "Else: Referred By: $referredBy, Reward Claimed: $referralRewardClaimed")
                }
            }
            .addOnFailureListener {
                onComplete(0) // Handle failure
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