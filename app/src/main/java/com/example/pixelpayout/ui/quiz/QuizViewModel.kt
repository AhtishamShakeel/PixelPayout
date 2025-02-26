package com.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.data.model.Question
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class QuizViewModel : ViewModel() {
    private lateinit var quiz: Quiz
    private var currentQuestionIndex = 0
    private var points = 0

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
        showCurrentQuestion()
    }

    fun submitAnswer(selectedAnswerIndex: Int) {
        val currentQuestion = quiz.questions[currentQuestionIndex]

        // Check if answer is correct
        if (selectedAnswerIndex == currentQuestion.correctAnswer) {
            points += quiz.pointsReward
        }

        // Move to next question or complete quiz
        currentQuestionIndex++
        if (currentQuestionIndex < quiz.questions.size) {
            showCurrentQuestion()
        } else {
            _score.value = points
            // Update points in Firebase before showing completion
            updatePoints {
                _isQuizComplete.value = true
            }
        }
    }

    private fun showCurrentQuestion() {
        _currentQuestion.value = quiz.questions[currentQuestionIndex]
    }

    fun updatePoints(onComplete: () -> Unit) {
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

    fun submitQuiz() {
        val userRef = FirebaseFirestore.getInstance()
            .collection("users")
            .document(FirebaseAuth.getInstance().currentUser?.uid ?: return)

        userRef.update(
            "quizAttempts", FieldValue.increment(1),
            "lastQuizDate", FieldValue.serverTimestamp(),
            "serverTime", FieldValue.serverTimestamp()  // Extra validation field
        )
    }
}