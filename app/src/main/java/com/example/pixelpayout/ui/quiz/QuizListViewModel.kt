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

                // First, get the server time
                val serverTimeDoc = db.collection("metadata").document("serverTime")
                serverTimeDoc.set(hashMapOf("timestamp" to FieldValue.serverTimestamp())).await()
                val serverTimeSnapshot = serverTimeDoc.get().await()
                val currentServerTime = serverTimeSnapshot.getTimestamp("timestamp")
                    ?: throw Exception("Failed to get server time")

                val result = db.runTransaction { transaction ->
                    var snapshot = transaction.get(userRef)

                    if (!snapshot.exists()) {
                        val userData = hashMapOf(
                            "quizAttempts" to 0,
                            "lastQuizDate" to currentServerTime,
                            "lastResetTime" to currentServerTime,
                            "points" to 0,
                            "email" to (auth.currentUser?.email ?: ""),
                            "displayName" to (auth.currentUser?.displayName ?: "")
                        )
                        transaction.set(userRef, userData)
                        snapshot = transaction.get(userRef)
                    }

                    val lastResetTime = snapshot.getTimestamp("lastResetTime") ?: currentServerTime
                    val currentAttempts = snapshot.getLong("quizAttempts")?.toInt() ?: 0

                    // Calculate if 24 hours have passed since last reset using server time
                    val shouldReset = (currentServerTime.seconds - lastResetTime.seconds) >= 24 * 60 * 60

                    if (shouldReset) {
                        transaction.update(userRef,
                            mapOf(
                                "quizAttempts" to 0,
                                "lastResetTime" to currentServerTime
                            )
                        )
                        Pair(0, true)
                    } else {
                        Pair(currentAttempts, false)
                    }
                }.await()

                val attempts = result.first
                val wasReset = result.second

                val remaining = MAX_DAILY_QUIZZES - attempts
                _remainingQuizzes.value = remaining

                if (remaining > 0) {
                    _quizzes.value = repository.getQuizzes()
                    _quizLimitReached.value = false
                } else {
                    _quizLimitReached.value = true
                    _quizzes.value = emptyList()

                    // Calculate next reset time based on lastResetTime
                    val nextResetTime = userRef.get().await().getTimestamp("lastResetTime")?.let { lastReset ->
                        (lastReset.seconds + 24 * 60 * 60) * 1000 // Convert to milliseconds
                    } ?: System.currentTimeMillis()
                    _nextQuizTime.value = nextResetTime
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