package com.example.pixelpayout.ui.quiz

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.pixelpayout.data.api.Question
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.model.LevelUpEvent
import com.example.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.launch

class QuizViewModel : ViewModel() {
    private lateinit var quiz: Quiz
    private var currentQuestionIndex = 0
    // Quizzes award XP, not redeemable points.
    private var earnedXp = 0

    private val _quizId = MutableLiveData<String?>()
    val quizId: LiveData<String?> = _quizId

    private val _currentQuestion = MutableLiveData<Question>()
    val currentQuestion: LiveData<Question> = _currentQuestion

    private val _isQuizComplete = MutableLiveData<Boolean>()
    val isQuizComplete: LiveData<Boolean> = _isQuizComplete

    /** XP earned in this quiz session. */
    private val _score = MutableLiveData<Int>()
    val score: LiveData<Int> = _score

    /** The user's running XP total, as reported by the server. */
    private val _totalPoints = MutableLiveData<Int>()
    val totalPoints: LiveData<Int> = _totalPoints

    private val _levelUp = MutableLiveData<LevelUpEvent?>()
    val levelUp: LiveData<LevelUpEvent?> = _levelUp

    private val userRepository = UserRepository()

    fun setQuiz(quiz: Quiz) {
        this.quiz = quiz
        _quizId.postValue(quiz.id)
        showCurrentQuestion()
    }

    fun submitAnswer(selectedAnswerIndex: Int) {
        viewModelScope.launch {
            try {
                // Correctness is graded server-side; the client only reports
                // which option was picked.
                val result = userRepository.claimQuizReward(
                    category = quiz.category,
                    quizId = quiz.id,
                    questionIndex = quiz.questionIndex,
                    selectedAnswer = selectedAnswerIndex
                )
                earnedXp += result.xpAwarded
                _score.postValue(earnedXp)
                _totalPoints.postValue(result.totalXp)
                if (result.leveledUp) {
                    _levelUp.postValue(
                        LevelUpEvent(result.level, result.milestonePoints)
                    )
                }
                _isQuizComplete.postValue(true)
            } catch (e: Exception) {
                _isQuizComplete.postValue(true)
            }
        }
    }

    private fun showCurrentQuestion() {
        _currentQuestion.value = quiz.questions[currentQuestionIndex]
    }
}
