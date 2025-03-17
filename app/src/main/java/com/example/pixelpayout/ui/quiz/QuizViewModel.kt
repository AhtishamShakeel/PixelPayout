package com.example.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.pixelpayout.data.api.Question
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class QuizViewModel : ViewModel() {
    private lateinit var quiz: Quiz
    private var currentQuestionIndex = 0
    private var points = 0

    private val _quizId = MutableLiveData<String?>()
    val quizId: LiveData<String?> = _quizId

    private val _currentQuestion = MutableLiveData<Question>()
    val currentQuestion: LiveData<Question> = _currentQuestion

    private val _isQuizComplete = MutableLiveData<Boolean>()
    val isQuizComplete: LiveData<Boolean> = _isQuizComplete

    private val _score = MutableLiveData<Int>()
    val score: LiveData<Int> = _score

    private val _totalPoints = MutableLiveData<Int>()
    val totalPoints: LiveData<Int> = _totalPoints

    private val userRepository = UserRepository()

    fun setQuiz(quiz: Quiz) {
        this.quiz = quiz
        _quizId.postValue(quiz.id)
        showCurrentQuestion()
    }

    fun submitAnswer(selectedAnswerIndex: Int) {
        val currentQuestion = quiz.questions[currentQuestionIndex]
        val isCorrect = selectedAnswerIndex == currentQuestion.correctAnswer

        if (isCorrect) {
            points += quiz.pointsReward
            _score.postValue(points)  // 🔥 Use postValue to ensure UI updates
        }

        // Increment attempts and update points
        userRepository.updateUserPointsAndAttempts(points) {
            _totalPoints.postValue(it)
            _isQuizComplete.postValue(true)
        }
    }

    private fun showCurrentQuestion() {
        _currentQuestion.value = quiz.questions[currentQuestionIndex]
    }
}