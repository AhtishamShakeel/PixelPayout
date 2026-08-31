package com.example.pixelpayout.ui.quiz

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.example.pixelpayout.utils.QuizDataManager
import com.pixelpayout.R
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.api.QuizCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

/**
 * Quiz CONTENT: the category list and the cached question set.
 *
 * The daily attempt counter used to live here too, behind a
 * checkAndResetQuizAttempts call on every cold start. Both are gone. That
 * callable reset `quiz_attempts` and re-stamped `last_reset_time` while
 * leaving `game_attempts` alone - and the two counters share that stamp, so
 * it convinced the server the day had already rolled over and froze the game
 * allowance at yesterday's count. It also put a blocking dialog in front of
 * the whole app while a network round trip answered a question the user
 * snapshot could already answer for free.
 *
 * Attempts now come from [com.example.pixelpayout.ui.main.MainViewModel],
 * which derives them from the snapshot the listener already holds.
 */
class QuizListViewModel : ViewModel() {
    private val _quizzes = MutableLiveData<List<Quiz>>()

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
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)  // Enable offline persistence
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
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
                            quiz.copy(title = category.name, category = category.name)
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
            // Select one random question from the quiz, keeping its original
            // index so the server can grade it against its own answer key.
            val randomQuestionIndex = selectedQuiz.questions.indices.random()
            selectedQuiz.copy(
                questions = listOf(selectedQuiz.questions[randomQuestionIndex]),
                questionIndex = randomQuestionIndex
            )
        } else {
            null
        }
    }
}
