package com.pixelpayout.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import com.pixelpayout.data.model.RedemptionRequest
import com.pixelpayout.data.model.RedemptionOption
import com.pixelpayout.data.model.RedemptionType
import java.util.*
import java.util.Calendar
import com.google.firebase.firestore.FieldValue

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun updateUserPoints(points: Int, onComplete: () -> Unit) {
        auth.currentUser?.let { user ->
            val userRef = firestore.collection("users").document(user.uid)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentPoints = snapshot.getLong("points") ?: 0
                transaction.update(userRef, "points", currentPoints + points)
            }.await()

            // Increment quiz attempts after points are updated
            incrementQuizAttempts()
            onComplete()
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
        auth.currentUser?.let { user ->
            val userRef = firestore.collection("users").document(user.uid)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                val currentAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0
                val lastQuizDate = snapshot.getTimestamp("lastQuizDate")
                val serverTime = snapshot.getTimestamp("serverTime") ?: Timestamp.now()

                // Use server timestamp for updates
                val updates = mutableMapOf<String, Any>(
                    "serverTime" to FieldValue.serverTimestamp()
                )

                if (lastQuizDate == null || !isSameServerDay(lastQuizDate, serverTime)) {
                    // New day, reset attempts
                    updates["quizAttempts"] = 1
                    updates["lastQuizDate"] = FieldValue.serverTimestamp()
                } else {
                    // Same day, increment attempts
                    updates["quizAttempts"] = currentAttempts + 1
                    updates["lastQuizDate"] = FieldValue.serverTimestamp()
                }

                transaction.update(userRef, updates)
            }.await()
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
        return auth.currentUser?.let { user ->
            val snapshot = firestore.collection("users")
                .document(user.uid)
                .get()
                .await()

            val lastQuizDate = snapshot.getTimestamp("lastQuizDate")
            val serverTime = snapshot.getTimestamp("serverTime")
            val quizAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0

            // Reset attempts if it's a new day based on server time
            if (lastQuizDate == null || serverTime == null ||
                !isSameServerDay(lastQuizDate, serverTime)) {
                return 0
            }

            quizAttempts
        } ?: 0
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
}

class InsufficientPointsException : Exception("Insufficient points for redemption")