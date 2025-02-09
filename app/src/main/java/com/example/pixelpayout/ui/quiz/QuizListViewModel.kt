package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.repository.QuizRepository
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class QuizListViewModel : ViewModel() {
    private val repository = QuizRepository()
    private val userRepository = UserRepository()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _quizzes = MutableLiveData<List<Quiz>>()
    val quizzes: LiveData<List<Quiz>> = _quizzes

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _quizLimitReached = MutableLiveData<Boolean>()
    val quizLimitReached: LiveData<Boolean> = _quizLimitReached

    private val _nextQuizTime = MutableLiveData<Long>()
    val nextQuizTime: LiveData<Long> = _nextQuizTime

    private val _remainingQuizzes = MutableLiveData<Int>()
    val remainingQuizzes: LiveData<Int> = _remainingQuizzes

    private val _dataLoaded = MutableLiveData<Boolean>()
    val dataLoaded: LiveData<Boolean> = _dataLoaded

    private val MAX_DAILY_RESETS = 10

    init {
        loadQuizzes()
    }

    fun loadQuizzes() {
        viewModelScope.launch {
            val resetCountRef = FirebaseFirestore.getInstance()
                .collection("metadata")
                .document("daily_resets")

            db.runTransaction { transaction ->
                val snapshot = transaction.get(resetCountRef)
                val count = snapshot.getLong("count") ?: 0

                if (count >= MAX_DAILY_RESETS) throw Exception("Daily reset limit reached")

                transaction.update(resetCountRef,
                    "count", FieldValue.increment(1),
                    "date", FieldValue.serverTimestamp()
                )
            }.await()
            try {
                _isLoading.value = true
                _error.value = null
                _dataLoaded.value = false

                // Get current user document
                val userId = auth.currentUser?.uid ?: throw Exception("User not authenticated")
                val userRef = db.collection("users").document(userId)

                // Transaction to check/reset attempts
                val (attempts, shouldReset) = db.runTransaction { transaction ->
                    val snapshot = transaction.get(userRef)
                    val lastQuizDate = snapshot.getTimestamp("lastQuizDate")
                    val currentAttempts = (snapshot.getLong("quizAttempts") ?: 0).toInt()  // Convert to Int

                    // Check if should reset
                    val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    val shouldReset = lastQuizDate?.let {
                        !isSameUTCDay(it.toDate(), now)
                    } ?: true

                    if (shouldReset) {
                        transaction.update(userRef,
                            "quizAttempts", 0,
                            "lastQuizDate", FieldValue.serverTimestamp(),
                            "serverTime", FieldValue.serverTimestamp()
                        )
                    }

                    Pair(currentAttempts, shouldReset)
                }.await()

                // Update remaining attempts
                val remaining = MAX_DAILY_QUIZZES - if (shouldReset) 0 else attempts
                _remainingQuizzes.value = remaining

                // Load quizzes if allowed
                if (remaining > 0) {
                    _quizzes.value = repository.getQuizzes()
                    _quizLimitReached.value = false
                } else {
                    _quizLimitReached.value = true
                    _quizzes.value = emptyList()
                    _nextQuizTime.value = calculateNextQuizTime()
                }

                _dataLoaded.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error occurred"
                _quizzes.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun calculateNextQuizTime(): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun isSameUTCDay(date: Date, calendar: Calendar): Boolean {
        val compareCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = date
        }
        return compareCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                compareCal.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }


    companion object {
        const val MAX_DAILY_QUIZZES = 10
    }
}