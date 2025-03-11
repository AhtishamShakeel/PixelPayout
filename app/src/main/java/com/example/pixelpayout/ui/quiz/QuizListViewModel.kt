package com.pixelpayout.ui.quiz

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.pixelpayout.utils.QuizDataManager
import com.pixelpayout.R
import com.pixelpayout.data.model.Quiz
import kotlinx.coroutines.launch

class QuizListViewModel : ViewModel() {
    private val _quizzes = MutableLiveData<List<Quiz>>()
    val quizzes: LiveData<List<Quiz>> = _quizzes

    private val _selectedQuiz = MutableLiveData<Quiz?>()
    val selectedQuiz: LiveData<Quiz?> = _selectedQuiz

    private val _loadingState = MutableLiveData<Boolean>()
    val loadingState: LiveData<Boolean> = _loadingState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _categories = MutableLiveData<List<QuizCategory>>() // ✅ Fix missing variable
    val categories: LiveData<List<QuizCategory>> = _categories


    // ✅ Fixed list of categories

    // ✅ Load quizzes from cache
    fun loadCachedQuizzes(context: Context) {
        val json = QuizDataManager.loadCachedQuizzes(context)
        if (!json.isNullOrEmpty()) {
            val quizData = Gson().fromJson(json, QuizData::class.java)

            // ✅ Store categories
            _categories.value = quizData.categories.map { category ->
                QuizCategory(
                    name = category.name,
                    imageResId = R.drawable.ic_user, // Placeholder
                    apiUrl = "" // No API needed since quizzes are local
                )
            }

            // ✅ Store quizzes with correct mapping
            _quizzes.value = quizData.categories.flatMap { category ->
                category.quizzes.map { quiz -> quiz.copy(title = category.name) } // Assign category name to quiz title
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

    // ✅ Load a single quiz from GitHub for a selected category
    fun loadSingleQuizByCategory(apiUrl: String) {
        _loadingState.value = true

        viewModelScope.launch {
            try {
                val quiz = QuizDataManager.getSingleQuizByCategory(apiUrl)
                _selectedQuiz.value = quiz
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Error: ${e.message ?: "Unknown error"}"
            } finally {
                _loadingState.value = false
            }
        }
    }

    fun getQuizByCategory(categoryName: String): Quiz? {
        return _quizzes.value?.firstOrNull { it.title.equals(categoryName, ignoreCase = true) }
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



}
