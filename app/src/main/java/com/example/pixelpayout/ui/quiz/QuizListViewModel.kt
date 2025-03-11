package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.R
import com.pixelpayout.data.repository.QuizRepository
import kotlinx.coroutines.launch

class QuizListViewModel : ViewModel() {
    private val repository = QuizRepository()

    init {
        clearCache()
    }

    private val _quizzes = MutableLiveData<List<Quiz>>()
    val quizzes: LiveData<List<Quiz>> = _quizzes

    private val _selectedQuiz = MutableLiveData<Quiz?>()
    val selectedQuiz: LiveData<Quiz?> = _selectedQuiz

    private val _loadingState = MutableLiveData<Boolean>()
    val loadingState: LiveData<Boolean> = _loadingState

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var hasLoaded = false
    private var isCurrentlyLoading = false

    fun loadQuizzes(forceRefresh: Boolean = false) {
        if (isCurrentlyLoading) return
        if (!forceRefresh && hasLoaded && _quizzes.value?.isNotEmpty() == true) return

        isCurrentlyLoading = true
        _loadingState.value = true

        viewModelScope.launch {
            try {
                val quizzes = repository.getQuizzes(forceRefresh || (repository.cachedQuizzes?.size?:0) <=5)
                _quizzes.value = quizzes
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
    fun onQuizCompleted(quizId: String) {
        viewModelScope.launch {
            try {
                repository.removeQuizFromCache(quizId)
                var remainingQuizzes = repository.cachedQuizzes ?: emptyList()
                if (remainingQuizzes.size <= 5){
                    repository.getQuizzes(forcedRefresh = true)
                    remainingQuizzes = repository.cachedQuizzes ?: emptyList()
                }
                _quizzes.value = remainingQuizzes.take(3)
                hasLoaded = false
                loadQuizzes(forceRefresh = false)
            } catch (e: Exception) {
                _error.value = "Error updating quiz: ${e.message}"
            }
        }
    }

    private fun clearCache(){
        repository.cachedQuizzes = null
    }

    data class QuizCategory(val name: String, val imageResId: Int, val apiUrl: String)

    val categories = listOf(
        QuizCategory("Vehicle", R.drawable.ic_user, "https://opentdb.com/api.php?amount=10&category=28&difficulty=easy"),
        QuizCategory("Sports", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=21&difficulty=easy&type=multiple"),
        QuizCategory("Celebrities", R.drawable.ic_user_icon, "https://opentdb.com/api.php?amount=15&category=26&difficulty=easy&type=multiple"),
        QuizCategory("Science", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=17&difficulty=easy&type=multiple"),
        QuizCategory("History", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=23&difficulty=easy&type=multiple"),
        QuizCategory("Geography", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=22&difficulty=easy&type=multiple"),
        QuizCategory("Movies", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=11&difficulty=easy&type=multiple"),
        QuizCategory("Music", R.drawable.ic_user, "https://opentdb.com/api.php?amount=15&category=12&difficulty=easy&type=multiple")
    )

    fun loadSingleQuizByCategory(apiUrl: String) {
        _loadingState.value = true

        viewModelScope.launch {
            try {
                val quiz = repository.getSingleQuizByCategory(apiUrl)
                _selectedQuiz.value = quiz
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Error: ${e.message ?: "Unknown error"}"
            } finally {
                _loadingState.value = false
            }
        }
    }
}