package com.pixelpayout.data.repository

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

data class UserData(
    val points: Int,
    val quizAttempts: Int,
    val lastQuizDate: Timestamp? = null
)

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData

    init {
        setupRealtimeUpdates()
    }

    private fun setupRealtimeUpdates() {
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
            .update("points", FieldValue.increment(pointsToAdd.toLong()))
            .addOnSuccessListener {
                // Get updated points
                firestore.collection("users").document(userId)
                    .get()
                    .addOnSuccessListener { document ->
                        val totalPoints = document.getLong("points")?.toInt() ?: 0
                        onComplete(totalPoints)
                    }
            }
    }

    suspend fun getUserPoints(): Int {
        return auth.currentUser?.let { user ->
            val snapshot = firestore.collection("users")
                .document(user.uid)
                .get()
                .await()

            (snapshot.getLong("points") ?: 0).toInt()
        } ?: 0
    }

    suspend fun getRedemptionOptions(): List<RedemptionOption> {
        return listOf(
            RedemptionOption(
                id = "easypaisa_500",
                title = "500 PKR Easypaisa",
                description = "Get 500 PKR transferred to your Easypaisa account",
                pointsCost = 5000,
                type = RedemptionType.EASYPAISA
            ),
            RedemptionOption(
                id = "pubg_uc_600",
                title = "600 UC PUBG Mobile",
                description = "Get 600 UC for PUBG Mobile",
                pointsCost = 3000,
                type = RedemptionType.GAME_CURRENCY
            )
            // Add more options as needed
        )
    }

    suspend fun submitRedemptionRequest(
        option: RedemptionOption,
        paymentDetails: Map<String, String>
    ): Boolean {
        return auth.currentUser?.let { user ->
            // First check if user has enough points
            val currentPoints = getUserPoints()
            if (currentPoints < option.pointsCost) {
                throw InsufficientPointsException()
            }

            // Create redemption request
            val request = RedemptionRequest(
                userId = user.uid,
                optionId = option.id,
                pointsCost = option.pointsCost,
                paymentDetails = paymentDetails
            )

            // Deduct points and create request in a transaction
            firestore.runTransaction { transaction ->
                val userRef = firestore.collection("users").document(user.uid)
                transaction.update(userRef, "points", currentPoints - option.pointsCost)

                val requestRef = firestore.collection("redemptionRequests").document()
                transaction.set(requestRef, request)
            }.await()

            true
        } ?: false
    }

    suspend fun incrementQuizAttempts() {
        withContext(Dispatchers.IO) {
            try {
                val user = auth.currentUser ?: throw Exception("User not logged in")
                val userRef = firestore.collection("users").document(user.uid)

                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(userRef)
                    val currentAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0
                    transaction.update(userRef, "quizAttempts", currentAttempts + 1)
                }.await()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun isSameServerDay(date1: Timestamp, date2: Timestamp): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1.toDate() }
        val cal2 = Calendar.getInstance().apply { time = date2.toDate() }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
                cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
    }

    suspend fun getQuizAttempts(): Int {
        return withContext(Dispatchers.IO) {
            try {
                val user = auth.currentUser ?: throw Exception("User not logged in")
                val snapshot = firestore.collection("users")
                    .document(user.uid)
                    .get()
                    .await()

                snapshot.getLong("quizAttempts")?.toInt() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }
    // Add to UserRepository
    suspend fun getCurrentUserDoc() = auth.currentUser?.uid?.let {
        FirebaseFirestore.getInstance().collection("users").document(it)
    }

    suspend fun getServerTimestamp(): Timestamp {
        val docRef = FirebaseFirestore.getInstance().collection("metadata").document("server")
        val snapshot = docRef.get().await()
        return snapshot.getTimestamp("timestamp") ?: Timestamp.now()
    }

    suspend fun getNextQuizTime(): Long {
        return auth.currentUser?.let { user ->
            val snapshot = firestore.collection("users")
                .document(user.uid)
                .get()
                .await()

            val lastQuizDate = snapshot.getTimestamp("lastQuizDate")
            val serverTime = snapshot.getTimestamp("serverTime")

            if (lastQuizDate != null && serverTime != null) {
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = lastQuizDate.toDate().time
                    // Set to next day at midnight
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // Calculate remaining time based on server time
                val nextResetTime = calendar.timeInMillis
                val currentServerTime = serverTime.toDate().time
                val remainingTime = nextResetTime - currentServerTime

                System.currentTimeMillis() + remainingTime
            } else {
                System.currentTimeMillis()
            }
        } ?: System.currentTimeMillis()
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

            // Update both users in a transaction
            firestore.runTransaction { transaction ->
                // Update referrer's points
                val referrerRef = firestore.collection("users").document(referrerId)
                val currentPoints = referrerDoc.getLong("points") ?: 0
                transaction.update(referrerRef, "points", currentPoints + 100)

                // Update current user
                val userRef = firestore.collection("users").document(currentUser.uid)
                transaction.update(userRef,
                    mapOf(
                        "hasUsedReferral" to true,
                        "referredBy" to referrerId
                    )
                )
            }.await()

            ReferralResult.Success
        } catch (e: Exception) {
            ReferralResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    // Generate a unique referral code for new users
    fun generateReferralCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

}

class InsufficientPointsException : Exception("Insufficient points for redemption")