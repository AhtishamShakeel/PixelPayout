package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.pixelpayout.data.model.Question
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.repository.UserRepository
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

        // 🔥 Increment attempts first before marking quiz as complete
        userRepository.incrementDailyAttempts {
            updatePoints {
                _isQuizComplete.postValue(true)  // ✅ Now mark quiz as complete
            }
        }
    }





    private fun showCurrentQuestion() {
        _currentQuestion.value = quiz.questions[currentQuestionIndex]
    }

    private fun updatePoints(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                userRepository.updateUserPoints(points) {
                    _totalPoints.value = it
                    onComplete()
                }
            } catch (e: Exception) {
                // Handle error if needed
                onComplete()
            }
        }
    }
}
