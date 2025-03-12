package com.pixelpayout.ui.quiz

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.pixelpayout.utils.QuizDataManager
import com.pixelpayout.R
import com.pixelpayout.data.model.Quiz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuizListViewModel : ViewModel() {
    private val _quizzes = MutableLiveData<List<Quiz>>()

    private val _categories = MutableLiveData<List<QuizCategory>>() // ✅ Fix missing variable
    val categories: LiveData<List<QuizCategory>> = _categories


    // ✅ Fixed list of categories

    // ✅ Load quizzes from cache
    fun loadCachedQuizzes(context: Context) {
        Log.d("QuizDebug", "Loading quizzes from cache...")

        viewModelScope.launch(Dispatchers.IO) {
            val json = QuizDataManager.loadCachedQuizzes(context)
            if (!json.isNullOrEmpty()) {
                val quizData = Gson().fromJson(json, QuizData::class.java)

                withContext(Dispatchers.Main) {
                    _categories.value = quizData.categories.map {
                        QuizCategory(it.name, getCategoryImage(it.name), "")
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
            }
        }
    }







    // ✅ Check for updates & refresh if needed
    fun checkAndUpdateQuizzes(context: Context) {
        QuizDataManager.fetchQuizzesFromGitHub(context) { isUpdated ->
            if (isUpdated) {
                loadCachedQuizzes(context) // Load new quizzes
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




    fun getCategories(): List<QuizCategory> = listOf(
        QuizCategory("Animals", R.drawable.ic_user_icon, ""),
        QuizCategory("Sports", R.drawable.ic_user_icon, ""),
        /*QuizCategory("Celebrities", R.drawable.ic_celebrities, ""),
        QuizCategory("Science", R.drawable.ic_science, ""),
        QuizCategory("History", R.drawable.ic_history, ""),
        QuizCategory("Geography", R.drawable.ic_geography, ""),
        QuizCategory("Movies", R.drawable.ic_movies, ""),
        QuizCategory("Music", R.drawable.ic_music, "")*/
    )

    private fun getCategoryImage(categoryName: String): Int {
        return when (categoryName.lowercase()) {
            "animals" -> R.drawable.ic_user
            "sports" -> R.drawable.ic_user_icon
           /* "celebrities" -> R.drawable.ic_celebrities
            "science" -> R.drawable.ic_science
            "history" -> R.drawable.ic_history
            "geography" -> R.drawable.ic_geography
            "movies" -> R.drawable.ic_movies
            "music" -> R.drawable.ic_music*/
            else -> R.drawable.ic_quiz // Default image
        }
    }




}
