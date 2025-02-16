package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.data.repository.UserRepository
import com.pixelpayout.data.model.QuizQuestion
import com.pixelpayout.data.model.Result
import kotlinx.coroutines.launch

class QuizViewModel : ViewModel() {
    private var _activeQuestion: QuizQuestion? = null
    private var points = 0
    private var lastAnswerCorrect = false
    private var lastQuestionPoints = 0
    private var quizId: Int = -1

    private val _questionLiveData = MutableLiveData<QuizQuestion>()
    val questionLiveData: LiveData<QuizQuestion> = _questionLiveData

    private val _isQuizComplete = MutableLiveData<Boolean>()
    val isQuizComplete: LiveData<Boolean> = _isQuizComplete

    private val _score = MutableLiveData<Int>()
    val score: LiveData<Int> = _score

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val userRepository = UserRepository()

    init {
        loadNewQuestion()
    }

    fun submitAnswer(selectedAnswerIndex: Int) {
        _activeQuestion?.let { question ->
            lastAnswerCorrect = selectedAnswerIndex == question.correctAnswerIndex
            lastQuestionPoints = if (lastAnswerCorrect) question.pointsReward else 0

            if (lastAnswerCorrect) {
                points += lastQuestionPoints
                _score.value = points
            }
        }
    }

    fun isLastAnswerCorrect() = lastAnswerCorrect
    fun getLastQuestionPoints() = lastQuestionPoints

    fun setQuizId(id: Int) {
        quizId = id
        loadNewQuestion()
    }

    fun loadNewQuestion() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val result = userRepository.fetchRandomQuestion(quizId)
                when (result) {
                    is Result.Success -> {
                        _activeQuestion = result.data
                        _questionLiveData.value = result.data
                    }
                    is Result.Failure -> {
                        _error.value = result.exception.message ?: "Failed to load question"
                    }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updatePoints(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                userRepository.updateUserPoints(points, onComplete)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to update points"
            }
        }
    }

    fun submitQuiz() {
        viewModelScope.launch {
            try {
                userRepository.updateUserPoints(points) {
                    _isQuizComplete.value = true
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }
}