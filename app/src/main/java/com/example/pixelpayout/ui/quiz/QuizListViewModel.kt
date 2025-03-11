package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.data.repository.QuizRepository
import kotlinx.coroutines.launch

class QuizListViewModel : ViewModel() {
    private val repository = QuizRepository()

    init {
        clearCache()
    }

    private val _quizzes = MutableLiveData<List<Quiz>>()
    val quizzes: LiveData<List<Quiz>> = _quizzes

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
}