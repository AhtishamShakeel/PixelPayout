package com.example.pixelpayout.ui.quiz

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import com.example.pixelpayout.utils.QuizDataManager
import com.pixelpayout.R
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.api.QuizCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

class QuizListViewModel : ViewModel() {
    private val _quizzes = MutableLiveData<List<Quiz>>()

    private val _dailyAttempts = MutableLiveData<Int>()
    val dailyAttempts: LiveData<Int> = _dailyAttempts
    val MAX_DAILY_ATTEMPTS = 10

    // Add LiveData for timer-related values
    private val _lastResetTime = MutableLiveData<Long>()
    val lastResetTime: LiveData<Long> = _lastResetTime
    private val _nextResetTime = MutableLiveData<Long>()
    val nextResetTime: LiveData<Long> = _nextResetTime

    private val _showLoadingDialog = MutableLiveData<Boolean>()
    val showLoadingDialog: LiveData<Boolean> = _showLoadingDialog

    private val _errorState = MutableLiveData<String?>()
    val errorState: LiveData<String?> = _errorState

    // Server time tracking
    private var lastFetchedServerTime: Long = 0L
    private var deviceTimeAtFetch: Long = 0L

    // Cache control variables
    private var lastCheckTimestamp: Long = 0
    private val CHECK_INTERVAL = 5 * 60 * 1000 // 5 minutes in milliseconds
    
    // App state tracking
    private var hasCompletedInitialLoad = false

    private val defaultCategories = listOf(
        QuizCategory("Animals", R.raw.animal_quiz_animation, ""),
        QuizCategory("Sports", R.raw.sports_quiz_animation, ""),
        QuizCategory("Science", R.raw.science_quiz_animation, ""),
        QuizCategory("Riddles", R.raw.riddles_quiz_animation, ""),
        QuizCategory("Geography", R.raw.geography_quiz_animation, ""),
        QuizCategory("Math Fun", R.raw.math_quiz_animation, ""),
        QuizCategory("Video Games", R.raw.games_quiz_animation, ""),
        QuizCategory("GK", R.raw.general_quiz_animation, "")
    )
    private val _categories = MutableLiveData<List<QuizCategory>>().apply {
        value = defaultCategories  // Set categories immediately
    }

    val categories: LiveData<List<QuizCategory>> = _categories

    init {
        _categories.value = defaultCategories  // Ensure categories load immediately
        _dailyAttempts.value = MAX_DAILY_ATTEMPTS
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)  // Enable offline persistence
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
    }

    fun fetchDailyAttempts(forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()

        if (!forceRefresh &&
            hasCompletedInitialLoad &&
            now - lastCheckTimestamp <= CHECK_INTERVAL &&
            !shouldRefreshDueToResetTime()
        ) {
            return
        }

        if (FirebaseAuth.getInstance().currentUser == null) {
            _showLoadingDialog.postValue(false)
            _errorState.postValue("Please sign in again.")
            return
        }

        lastCheckTimestamp = now  // Ensure timestamp updates on every attempt

        _showLoadingDialog.postValue(true) // Show loading dialog before starting
        _errorState.postValue(null) // Reset error state before retrying

        FirebaseFunctions.getInstance()
            .getHttpsCallable("checkAndResetQuizAttempts")
            .call()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    try {
                        val data = task.result?.data as? Map<*, *>
                        if (data != null) {
                            val attempts = (data["attempts"] as? Number)?.toInt() ?: throw Exception("Invalid attempts value")
                            val lastResetTime = (data["lastResetTime"] as? Number)?.toLong()
                                ?: throw Exception("Invalid lastResetTime")
                            val serverTime = (data["serverTime"] as? Number)?.toLong() ?: System.currentTimeMillis()
                            lastFetchedServerTime = serverTime
                            deviceTimeAtFetch = System.currentTimeMillis()

                            val nextResetTime = calculateNextResetTime(lastResetTime)
                            _dailyAttempts.postValue(attempts)
                            _lastResetTime.postValue(lastResetTime)
                            _nextResetTime.postValue(nextResetTime)
                            hasCompletedInitialLoad = true
                            Log.d("QuizDebug", "Fetched daily attempts")

                            _showLoadingDialog.postValue(false) // Hide dialog only if successful
                        } else {
                            throw Exception("No data received from server")
                        }
                    } catch (e: Exception) {
                        hasCompletedInitialLoad = false
                        Log.e("QuizDebug", "Error processing server response: ${e.message}")
                        _showLoadingDialog.postValue(false)
                        _errorState.postValue("Error processing response")
                    }
                } else {
                    hasCompletedInitialLoad = false
                    Log.e("QuizDebug", "Error checking quiz attempts", task.exception)
                    _showLoadingDialog.postValue(false)
                    _errorState.postValue("Failed to fetch data. Please retry.") // Show retry button
                }
            }
    }

    /**
     * Refreshes attempts only if needed - at app startup or after completing a quiz
     */
    fun refreshAttemptsIfNeeded() {
        val shouldRefresh = !hasCompletedInitialLoad || 
                           System.currentTimeMillis() - lastCheckTimestamp > CHECK_INTERVAL ||
                           shouldRefreshDueToResetTime()
        
        if (shouldRefresh) {
            fetchDailyAttempts(forceRefresh = true)
        }
    }

    /**
     * Checks if we should refresh attempts because we've passed the reset time
     */
    private fun shouldRefreshDueToResetTime(): Boolean {
        val nextReset = _nextResetTime.value
        return if (nextReset != null) {
            System.currentTimeMillis() >= nextReset
        } else {
            false
        }
    }

    /**
     * Calculate the next reset time (midnight UTC of next day after the last reset)
     */
    private fun calculateNextResetTime(lastResetTimeMillis: Long): Long {
        // Create midnight UTC for the current day of the last reset
        val midnightCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        midnightCalendar.timeInMillis = lastResetTimeMillis
        midnightCalendar.set(Calendar.HOUR_OF_DAY, 0)
        midnightCalendar.set(Calendar.MINUTE, 0)
        midnightCalendar.set(Calendar.SECOND, 0)
        midnightCalendar.set(Calendar.MILLISECOND, 0)
        
        // Add one day to get the next day's midnight
        midnightCalendar.add(Calendar.DAY_OF_MONTH, 1)
        
        return midnightCalendar.timeInMillis
    }

    fun loadCachedQuizzes(context: Context) {
        Log.d("QuizDebug", "Loading quizzes from cache...")

        viewModelScope.launch(Dispatchers.IO) {
            val json = QuizDataManager.loadCachedQuizzes(context)
            if (!json.isNullOrEmpty()) {
                val quizData = Gson().fromJson(json, QuizData::class.java)

                withContext(Dispatchers.Main) {
                    // Map cached categories to our default categories to maintain consistent images
                    _categories.value = quizData.categories.map { category ->
                        defaultCategories.find { it.name.equals(category.name, ignoreCase = true) }
                            ?: QuizCategory(category.name, R.raw.default_quiz_animation, "")
                    }
                    _quizzes.value = quizData.categories.flatMap { category ->
                        category.quizzes.map { quiz ->
                            quiz.copy(title = category.name)
                        }
                    }

                    Log.d("QuizDebug", "Loaded categories: ${quizData.categories.size}")
                    Log.d("QuizDebug", "Loaded quizzes: ${_quizzes.value?.size ?: 0}")
                }
            } else {
                Log.d("QuizDebug", "No cached quizzes found.")
                withContext(Dispatchers.Main) {
                    _categories.value = defaultCategories
                }
            }
        }
    }

    fun checkAndUpdateQuizzes(context: Context) {
        viewModelScope.launch {
            val isUpdated = QuizDataManager.fetchQuizzesFromFirebase(context.applicationContext)
            if (isUpdated) {
                loadCachedQuizzes(context)
            }
        }
    }

    fun getQuizByCategory(categoryName: String): Quiz? {
        val categoryQuizzes = _quizzes.value?.filter { it.title.equals(categoryName, ignoreCase = true) }

        Log.d("QuizDebug", "Searching for quizzes in category: $categoryName")
        Log.d("QuizDebug", "Found ${categoryQuizzes?.size ?: 0} quizzes in this category")

        val validQuizzes = categoryQuizzes?.filter { quiz ->
            quiz.questions.isNotEmpty() && quiz.questions.all { it.text.isNotEmpty() }
        }

        Log.d("QuizDebug", "Valid quizzes count: ${validQuizzes?.size ?: 0}")

        return if (!validQuizzes.isNullOrEmpty()) {
            val selectedQuiz = validQuizzes.random()
            // Select one random question from the quiz
            val randomQuestion = selectedQuiz.questions.random()
            // Return a new quiz with only the selected question
            selectedQuiz.copy(questions = listOf(randomQuestion))
        } else {
            null
        }
    }

    /**
     * Returns the current server time, adjusted for elapsed time since last fetch
     */
    fun getCurrentServerTime(): Long {
        return lastFetchedServerTime + (System.currentTimeMillis() - deviceTimeAtFetch)
    }
}
