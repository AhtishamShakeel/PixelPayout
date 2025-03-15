package com.pixelpayout.ui.quiz

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.gson.Gson
import com.pixelpayout.utils.QuizDataManager
import com.pixelpayout.R
import com.pixelpayout.data.model.Quiz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuizListViewModel : ViewModel() {
    private val _quizzes = MutableLiveData<List<Quiz>>()

    private val _dailyAttempts = MutableLiveData<Int>()
    val dailyAttempts: LiveData<Int> = _dailyAttempts
    val MAX_DAILY_ATTEMPTS = 10

    // Cache control variables
    private var lastCheckTimestamp: Long = 0
    private val CHECK_INTERVAL = 5 * 60 * 1000 // 5 minutes in milliseconds
    
    private val _categories = MutableLiveData<List<QuizCategory>>().apply {
        value = defaultCategories  // Set categories immediately
    }


    val categories: LiveData<List<QuizCategory>> = _categories

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

    init {
        _categories.value = defaultCategories  // Ensure categories load immediately
    }

    fun fetchDailyAttempts(forceRefresh: Boolean = false) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        
        // Only check if it's been more than 5 minutes since last check or forceRefresh is true
        if (!forceRefresh && now - lastCheckTimestamp < CHECK_INTERVAL && _dailyAttempts.value != null) {
            Log.d("QuizDebug", "Using cached attempts value, last checked ${(now - lastCheckTimestamp) / 1000} seconds ago")
            return
        }
        
        lastCheckTimestamp = now
        Log.d("QuizDebug", "Fetching attempts from server")

        // Call our checkAndResetQuizAttempts function
        FirebaseFunctions.getInstance()
            .getHttpsCallable("checkAndResetQuizAttempts")
            .call()
            .addOnSuccessListener { result ->
                val data = result.data as? Map<*, *>
                val attempts = (data?.get("attempts") as? Number)?.toInt() ?: 0
                val resetPerformed = data?.get("resetPerformed") as? Boolean ?: false
                
                _dailyAttempts.postValue(attempts)
                
                if (resetPerformed) {
                    Log.d("QuizDebug", "Quiz attempts were reset to 0 for a new day")
                }
            }
            .addOnFailureListener { e ->
                Log.e("QuizDebug", "Failed to check/reset attempts: ${e.message}")
                // Fall back to direct Firestore access if function fails
                FirebaseFirestore.getInstance().collection("users").document(userId)
                    .get()
                    .addOnSuccessListener { document ->
                        val attempts = document.getLong("quiz_attempts")?.toInt() ?: 0
                        _dailyAttempts.postValue(attempts)
                    }
                    .addOnFailureListener {
                        _dailyAttempts.postValue(0) // Default to 0 if error
                    }
            }
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
        QuizDataManager.fetchQuizzesFromGitHub(context) { isUpdated ->
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

    fun getCategories(): List<QuizCategory> = defaultCategories
}
