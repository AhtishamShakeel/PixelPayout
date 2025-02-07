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

                if (lastQuizDate == null || !isSameDay(lastQuizDate.toDate(), Date())) {
                    // New day, reset attempts
                    transaction.update(userRef, mapOf(
                        "quizAttempts" to 1,
                        "lastQuizDate" to Timestamp.now()
                    ))
                } else {
                    // Same day, increment attempts
                    transaction.update(userRef, mapOf(
                        "quizAttempts" to (currentAttempts + 1),
                        "lastQuizDate" to Timestamp.now()
                    ))
                }
            }.await()
        }
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }
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
            val quizAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0

            // Reset attempts if it's a new day
            if (lastQuizDate == null || !isSameDay(lastQuizDate.toDate(), Date())) {
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

            val lastQuizDate = snapshot.getTimestamp("lastQuizDate")?.toDate()
            if (lastQuizDate != null) {
                val calendar = Calendar.getInstance().apply {
                    time = lastQuizDate
                    // Set to next day at midnight
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                calendar.timeInMillis
            } else {
                System.currentTimeMillis()
            }
        } ?: System.currentTimeMillis()
    }
}

class InsufficientPointsException : Exception("Insufficient points for redemption")