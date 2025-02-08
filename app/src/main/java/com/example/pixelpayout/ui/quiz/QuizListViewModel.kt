package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.repository.QuizRepository
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class QuizListViewModel : ViewModel() {
    private val repository = QuizRepository()
    private val userRepository = UserRepository()

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

    init {
        loadQuizzes()
    }

    fun loadQuizzes() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                // Check quiz attempts
                val attempts = userRepository.getQuizAttempts()
                _remainingQuizzes.value = MAX_DAILY_QUIZZES - attempts

                if (attempts >= MAX_DAILY_QUIZZES) {
                    _quizLimitReached.value = true
                    _nextQuizTime.value = userRepository.getNextQuizTime()
                    _quizzes.value = emptyList()
                } else {
                    _quizLimitReached.value = false
                    _quizzes.value = repository.getQuizzes()
                }
            } catch (e: Exception) {
                _error.value = e.message
                _quizzes.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    companion object {
        const val MAX_DAILY_QUIZZES = 10
    }
} 