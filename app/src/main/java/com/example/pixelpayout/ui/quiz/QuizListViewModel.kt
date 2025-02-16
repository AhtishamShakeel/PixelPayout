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

    private val _quizzes = MutableLiveData<List<QuizListItem>>()
    val quizzes: LiveData<List<QuizListItem>> = _quizzes

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

    private val MAX_DAILY_ATTEMPTS = 10

    init {
        loadQuizzes()
    }

    private suspend fun getServerTime(): Timestamp {
        try {
            // Try up to 3 times to get server time
            repeat(3) { attempt ->
                try {
                    val serverTimeDoc = db.collection("metadata").document("serverTime")

                    // Set server timestamp
                    serverTimeDoc.set(mapOf("timestamp" to FieldValue.serverTimestamp())).await()

                    // Wait a bit to ensure the server timestamp is set
                    kotlinx.coroutines.delay(500)

                    // Get the document
                    val snapshot = serverTimeDoc.get().await()
                    val timestamp = snapshot.getTimestamp("timestamp")

                    if (timestamp != null) {
                        return timestamp
                    }
                } catch (e: Exception) {
                    if (attempt == 2) throw e // Throw on last attempt
                    kotlinx.coroutines.delay(1000) // Wait before retry
                }
            }
            throw Exception("Failed to get server time after 3 attempts")
        } catch (e: Exception) {
            // If all else fails, create a timestamp from current time
            _error.value = "Warning: Using device time as fallback"
            return Timestamp.now()
        }
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

    fun loadQuizzes() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                // Get user's quiz attempts
                val attempts = userRepository.getQuizAttempts()
                val remainingAttempts = MAX_DAILY_ATTEMPTS - attempts

                if (remainingAttempts > 0) {
                    // Create a single quiz option that leads to random questions
                    val quizList = listOf(
                        QuizListItem(
                            id = "random_quiz",
                            title = "Random Quiz",
                            description = "Test your knowledge with random questions!",
                            difficulty = "Mixed",
                            pointsReward = "10-30 points per question"
                        )
                    )
                    _quizzes.value = quizList
                    _remainingQuizzes.value = remainingAttempts
                } else {
                    _quizLimitReached.value = true
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
                _dataLoaded.value = true
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
                    val extraAttempts = snapshot.getLong("extraQuizAttempts")?.toInt() ?: 0

                    // Add an extra attempt without affecting the timer
                    transaction.update(userRef,
                        mapOf(
                            "extraQuizAttempts" to (extraAttempts + 1)
                        )
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

    fun canAttemptQuiz(): Boolean {
        return (_remainingQuizzes.value ?: 0) > 0
    }

    companion object {
        const val MAX_DAILY_ATTEMPTS = 10
        private const val MAX_DAILY_RESETS = 10
    }
}

data class QuizListItem(
    val id: String,
    val title: String,
    val description: String,
    val difficulty: String,
    val pointsReward: String
)