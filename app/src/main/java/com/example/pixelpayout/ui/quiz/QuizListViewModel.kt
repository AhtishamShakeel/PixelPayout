package com.pixelpayout.ui.quiz

// Add these imports at the top
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.auth.FirebaseAuth
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.repository.QuizRepository
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.Pair
import android.content.Context
import com.pixelpayout.utils.AdManager

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

    private val _adAvailable = MutableLiveData<Boolean>()
    val adAvailable: LiveData<Boolean> = _adAvailable

    private val MAX_DAILY_RESETS = 10

    init {
        loadQuizzes()
    }

    fun loadQuizzes() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                _dataLoaded.value = false

                val userId = auth.currentUser?.uid ?: throw Exception("User not authenticated")
                val userRef = db.collection("users").document(userId)

                // Fix: Handle everything in a single transaction
                val result: Pair<Int, Boolean> = db.runTransaction { transaction: Transaction ->
                    var snapshot = transaction.get(userRef)

                    if (!snapshot.exists()) {
                        val userData: Map<String, Any> = hashMapOf(
                            "quizAttempts" to 0,
                            "lastQuizDate" to FieldValue.serverTimestamp(),
                            "serverTime" to FieldValue.serverTimestamp(),
                            "points" to 0,
                            "email" to (auth.currentUser?.email ?: ""),
                            "displayName" to (auth.currentUser?.displayName ?: ""),
                            "dailyResetCount" to 0,
                            "lastResetDate" to FieldValue.serverTimestamp()
                        )
                        transaction.set(userRef, userData)
                        snapshot = transaction.get(userRef)
                    }

                    val lastQuizDate = snapshot.getTimestamp("lastQuizDate")?.toDate()
                    val currentAttempts = (snapshot.getLong("quizAttempts") ?: 0).toInt()
                    val resetCount = (snapshot.getLong("dailyResetCount") ?: 0).toInt()
                    val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

                    val shouldReset = lastQuizDate?.let {
                        !isSameUTCDay(it, now)
                    } ?: true

                    if (shouldReset) {
                        if (resetCount >= MAX_DAILY_RESETS) {
                            throw Exception("Daily reset limit reached")
                        }

                        transaction.update(userRef,
                            "quizAttempts", 0,
                            "lastQuizDate", FieldValue.serverTimestamp(),
                            "serverTime", FieldValue.serverTimestamp(),
                            "dailyResetCount", FieldValue.increment(1),
                            "lastResetDate", FieldValue.serverTimestamp()
                        )
                    }

                    Pair<Int, Boolean>(currentAttempts, shouldReset)
                }.await()

                val attempts = result.first
                val shouldReset = result.second

                val remaining = MAX_DAILY_QUIZZES - if (shouldReset) 0 else attempts
                _remainingQuizzes.value = remaining

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
    fun submitQuiz() {
        val userRef = FirebaseFirestore.getInstance()
            .collection("users")
            .document(FirebaseAuth.getInstance().currentUser?.uid ?: return)

        // Use set() with merge instead of update() to create document if missing
        userRef.set(
            hashMapOf(
                "quizAttempts" to FieldValue.increment(1),
                "lastQuizDate" to FieldValue.serverTimestamp(),
                "serverTime" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
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

    fun watchAdForExtraQuiz() {
        viewModelScope.launch {
            try {
                val userId = auth.currentUser?.uid ?: throw Exception("User not authenticated")
                val userRef = db.collection("users").document(userId)

                db.runTransaction { transaction ->
                    val snapshot = transaction.get(userRef)
                    val currentAttempts = snapshot.getLong("quizAttempts") ?: 0

                    // Decrease attempts by 1 (giving an extra attempt)
                    transaction.update(userRef,
                        "quizAttempts", currentAttempts - 1,
                        "lastQuizDate", FieldValue.serverTimestamp()
                    )
                }.await()

                // Show success message
                _error.value = "Extra quiz attempt added!"

                // Reload quizzes to update UI
                loadQuizzes()
            } catch (e: Exception) {
                _error.value = "Failed to add extra attempt: ${e.message}"
            }
        }
    }

    // Call this when entering the quiz list screen
    fun preloadAd(context: Context) {
        AdManager.getInstance().apply {
            setAdAvailabilityCallback { available ->
                _adAvailable.value = available
            }
            loadRewardedAd(context)
        }
    }

    companion object {
        const val MAX_DAILY_QUIZZES = 10
    }
}