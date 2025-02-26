package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.data.repository.QuizRepository
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.TimeZone
import android.content.Context
import com.pixelpayout.utils.AdManager

class QuizListViewModel : ViewModel() {
    private val repository = QuizRepository()
    private val userRepository = UserRepository()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // LiveData properties
    private val _quizzes = MutableLiveData<List<Quiz>>()
    val quizzes: LiveData<List<Quiz>> = _quizzes

    private val _loadingState = MutableLiveData<Boolean>()
    val loadingState: LiveData<Boolean> = _loadingState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _quizLimitReached = MutableLiveData<Boolean>()
    val quizLimitReached: LiveData<Boolean> = _quizLimitReached

    private val _nextQuizTime = MutableLiveData<Long>()
    val nextQuizTime: LiveData<Long> = _nextQuizTime

    private val _remainingQuizzes = MutableLiveData<Int>()
    val remainingQuizzes: LiveData<Int> = _remainingQuizzes

    private val _adAvailable = MutableLiveData<Boolean>()
    val adAvailable: LiveData<Boolean> = _adAvailable

    // State tracking
    private var hasLoaded = false
    private var isCurrentlyLoading = false

    companion object {
        const val MAX_DAILY_QUIZZES = 10
    }

    fun loadQuizzes(forceRefresh: Boolean = false) {
        // Don't load if already loading
        if (isCurrentlyLoading) return

        // Don't load if we have data and aren't forcing refresh
        if (!forceRefresh && hasLoaded && _quizzes.value?.isNotEmpty() == true) return

        isCurrentlyLoading = true
        _loadingState.value = true

        viewModelScope.launch {
            try {
                // Single API call
                val quizzes = repository.getQuizzes()

                // Check user attempts
                val attemptsInfo = checkUserAttempts()
                val remaining = MAX_DAILY_QUIZZES - attemptsInfo.first

                if (remaining > 0) {
                    _quizzes.value = quizzes
                    _quizLimitReached.value = false
                } else {
                    _quizLimitReached.value = true
                    _quizzes.value = emptyList()
                    _nextQuizTime.value = getMidnightUTCTimestamp()
                }

                _remainingQuizzes.value = remaining
                hasLoaded = true
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Error: ${e.message ?: "Unknown error"}"
                _quizzes.value = emptyList()
            } finally {
                isCurrentlyLoading = false
                _loadingState.value = false
            }
        }
    }

    private suspend fun checkUserAttempts(): Pair<Int, Boolean> {
        val userId = auth.currentUser?.uid ?: throw Exception("Not authenticated")
        val userRef = db.collection("users").document(userId)
        val serverTime = getServerTime()

        return db.runTransaction { transaction ->
            var snapshot = transaction.get(userRef)

            // Create user doc if missing
            if (!snapshot.exists()) {
                transaction.set(userRef, hashMapOf(
                    "quizAttempts" to 0,
                    "lastResetTime" to serverTime,
                    "extraQuizAttempts" to 0,
                    "points" to 0
                ))
                snapshot = transaction.get(userRef)
            }

            // Calculate attempts
            val lastResetTime = snapshot.getTimestamp("lastResetTime") ?: serverTime
            val currentAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0
            val extraAttempts = snapshot.getLong("extraQuizAttempts")?.toInt() ?: 0

            // Check midnight reset
            val shouldReset = isNewUTCDay(lastResetTime, serverTime)
            if (shouldReset) {
                transaction.update(userRef, mapOf(
                    "quizAttempts" to 0,
                    "lastResetTime" to serverTime,
                    "extraQuizAttempts" to 0
                ))
                Pair(0, true)
            } else {
                Pair(currentAttempts - extraAttempts, false)
            }
        }.await()
    }

    private suspend fun getServerTime(): Timestamp {
        return try {
            val serverTimeDoc = db.collection("metadata").document("serverTime")
            serverTimeDoc.set(mapOf("timestamp" to FieldValue.serverTimestamp())).await()
            serverTimeDoc.get().await().getTimestamp("timestamp") ?: Timestamp.now()
        } catch (e: Exception) {
            Timestamp.now()
        }
    }

    private fun isNewUTCDay(oldTime: Timestamp, newTime: Timestamp): Boolean {
        val oldCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = oldTime.seconds * 1000
        }
        val newCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = newTime.seconds * 1000
        }
        return oldCal[Calendar.DAY_OF_YEAR] != newCal[Calendar.DAY_OF_YEAR] ||
                oldCal[Calendar.YEAR] != newCal[Calendar.YEAR]
    }

    private fun getMidnightUTCTimestamp(): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun submitQuiz() =
        db.collection("users").document(auth.currentUser?.uid ?: throw Exception("Not authenticated"))
            .set(
                mapOf(
                    "quizAttempts" to FieldValue.increment(1),
                    "lastQuizDate" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

    fun watchAdForExtraQuiz() {
        viewModelScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: throw Exception("Not authenticated")
                db.collection("users").document(userId).update(
                    "extraQuizAttempts", FieldValue.increment(1)
                )
                _error.value = "Extra attempt added!"
                loadQuizzes() // Refresh UI
            } catch (e: Exception) {
                _error.value = "Failed: ${e.message}"
            }
        }
    }

    fun preloadAd(context: Context) {
        AdManager.getInstance().apply {
            setAdAvailabilityCallback { available ->
                _adAvailable.value = available
            }
            loadRewardedAd(context)
        }
    }

    fun resetLoadingState() {
        hasLoaded = false
    }

    fun onQuizCompleted() {
        viewModelScope.launch {
            try {
                // First submit the quiz and wait for it to complete
                submitQuiz().await() // Make submitQuiz return a Task<Void>

                // Reset loading state and force refresh
                hasLoaded = false
                loadQuizzes(forceRefresh = true)
            } catch (e: Exception) {
                _error.value = "Error updating quiz: ${e.message}"
            }
        }
    }
}