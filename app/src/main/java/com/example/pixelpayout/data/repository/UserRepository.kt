package com.pixelpayout.data.repository

import android.util.Log
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
import com.google.firebase.firestore.ListenerRegistration
import com.pixelpayout.ui.redemption.ReferralResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UserRepository private constructor() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData
    private var listenerRegistration: ListenerRegistration? = null
    private var isListenerSetup = false
    private var readCount = 0
    private var writeCount = 0

    companion object {
        @Volatile
        private var instance: UserRepository? = null

        fun getInstance(): UserRepository {
            return instance ?: synchronized(this) {
                instance ?: UserRepository().also { instance = it }
            }
        }
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
                if (!isListenerSetup) {
                    setupRealtimeUpdates(userId)
                    isListenerSetup = true
                }
            }
        }
    }

    private fun setupRealtimeUpdates(userId: String) {
        // Remove existing listener if any
        listenerRegistration?.remove()

        Log.d("FirebaseOps", "Setting up realtime listener for user: $userId")
        listenerRegistration = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.let {
                    readCount++
                    Log.d("FirebaseOps", "Realtime update received for user: $userId (Read #$readCount)")
                    val points = it.getLong("points")?.toInt() ?: 0
                    Log.d("UIUpdate", "UserRepository received points update: $points")
                    _userData.postValue(
                        UserData(
                            points = points,
                            quizAttempts = it.getLong("quizAttempts")?.toInt() ?: 0,
                            lastQuizDate = it.getTimestamp("lastQuizDate")
                        )
                    )
                }
            }
    }

    fun cleanup() {
        listenerRegistration?.remove()
        listenerRegistration = null
        isListenerSetup = false
        Log.d("FirebaseOps", "Total reads: $readCount, Total writes: $writeCount")
        readCount = 0
        writeCount = 0
    }

    data class UserData(
        val points: Int,
        val quizAttempts: Int,
        val lastQuizDate: Timestamp?
    )

    fun updateUserPoints(pointsToAdd: Int, onComplete: (Int) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        Log.d("FirebaseOps", "Starting points update for user: $userId, points to add: $pointsToAdd")

        // Use a single transaction instead of separate read and write
        firestore.runTransaction { transaction ->
            val userRef = firestore.collection("users").document(userId)
            val userDoc = transaction.get(userRef)
            readCount++
            Log.d("FirebaseOps", "Transaction read for points update (Read #$readCount)")

            val currentPoints = userDoc.getLong("points")?.toInt() ?: 0
            val newTotal = currentPoints + pointsToAdd
            val referredBy = userDoc.getString("referredBy")
            val referralRewardClaimed = userDoc.getBoolean("referralRewardClaimed") ?: false

            transaction.update(userRef, "points", FieldValue.increment(pointsToAdd.toLong()))
            writeCount++
            Log.d("FirebaseOps", "Transaction write for points update (Write #$writeCount)")

            // Return data needed for post-transaction operations
            Triple(newTotal, referredBy, referralRewardClaimed)
        }
            .addOnSuccessListener { (newTotal, referredBy, referralRewardClaimed) ->
                Log.d("FirebaseOps", "Points update successful for user: $userId, new total: $newTotal")
                Log.d("UIUpdate", "UserRepository posting points update: $newTotal")
                _userData.postValue(UserData(
                    points = newTotal,
                    quizAttempts = _userData.value?.quizAttempts ?: 0,
                    lastQuizDate = _userData.value?.lastQuizDate
                ))
                onComplete(newTotal)

                // Handle referral reward if needed
                if (newTotal >= 100 && referredBy != null && !referralRewardClaimed) {
                    giveReferralReward(referredBy, userId)
                }
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseOps", "Failed to update points for user: $userId", e)
            }
    }

    // Function to give 100 points to the referrer
    private fun giveReferralReward(referrerId: String, referredUserId: String) {
        Log.d("FirebaseOps", "Starting referral reward transaction for referrer: $referrerId, referred: $referredUserId")
        val referrerRef = firestore.collection("users").document(referrerId)
        val referredUserRef = firestore.collection("users").document(referredUserId)
        firestore.runTransaction { transaction ->
            val referrerDoc = transaction.get(referrerRef)
            readCount++
            Log.d("FirebaseOps", "Referral reward transaction read (Read #$readCount)")

            val currentPoints = referrerDoc.getLong("points") ?: 0
            transaction.update(referrerRef, "points", currentPoints + 100)
            writeCount++
            Log.d("FirebaseOps", "Referral reward transaction write (Write #$writeCount)")

            transaction.update(referredUserRef, "referralRewardClaimed", true)
            writeCount++
            Log.d("FirebaseOps", "Referral reward transaction write (Write #$writeCount)")
        }
            .addOnSuccessListener {
                Log.d("FirebaseOps", "Referral reward transaction successful")
            }
            .addOnFailureListener { e ->
                Log.e("FirebaseOps", "Referral reward transaction failed", e)
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