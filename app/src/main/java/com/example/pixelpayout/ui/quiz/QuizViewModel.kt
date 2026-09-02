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

    /**
     * Marks the level-up as announced.
     *
     * LiveData re-delivers its last value to a new observer, so without this
     * a rotation - which recreates the activity and re-subscribes - would put
     * the level-up dialog back on screen, offering an ad for a reward that may
     * already have been claimed. Harmless when it was a toast; not harmless
     * now that it is a modal offer.
     */
    fun clearLevelUp() {
        _levelUp.value = null
    }


    /** How the "double it" offer ended. Mirrors the game results screen. */
    sealed class DoubleOutcome {
        data class Paid(val xpAwarded: Int) : DoubleOutcome()

        /**
         * The double had already landed - a previous call got through and its
         * response was lost. The XP is banked, so this is a success with no
         * number to show, not a failure.
         */
        data object AlreadyPaid : DoubleOutcome()

        data object Failed : DoubleOutcome()
    }

    private val _doubleOutcome = MutableLiveData<DoubleOutcome>()
    val doubleOutcome: LiveData<DoubleOutcome> = _doubleOutcome

    /**
     * The ledger entry this attempt can still be doubled against.
     *
     * A quiz attempt is ONE question, so there is exactly one entry to double
     * - the same shape a game run has, which is why both go through the same
     * server call. Cleared the instant the double is requested, so a
     * double-tap cannot send two.
     */
    private var doubleableEventId: String? = null

    /** Whether there is a paid answer to offer a double on. */
    fun canDouble(): Boolean = doubleableEventId != null

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
                // A wrong answer earns nothing, and the server refuses a
                // double on it - so the offer is not made rather than made
                // and then refused.
                doubleableEventId =
                    if (result.xpAwarded > 0 && result.eventId.isNotEmpty()) result.eventId else null
                _totalPoints.postValue(result.totalXp)
                _isQuizComplete.postValue(true)
                // AFTER the results, deliberately - see the same ordering in
                // GamePlayViewModel. postValue preserves the order it was
                // called in, so the dialog lands on top of the results sheet.
                if (result.leveledUp) {
                    _levelUp.postValue(
                        LevelUpEvent(result.level, result.milestonePoints)
                    )
                }
            } catch (e: Exception) {
                doubleableEventId = null
                _isQuizComplete.postValue(true)
            }
        }
    }

    /**
     * Claims the doubled XP, the rewarded ad having been watched.
     *
     * Called from the ad's REWARD callback rather than on dismissal: both
     * arrive on a normal completion but the reward comes first, which shrinks
     * the window in which a killed process loses an ad the player actually
     * sat through.
     */
    fun claimDoubleXp() {
        val eventId = doubleableEventId ?: return
        doubleableEventId = null

        viewModelScope.launch {
            when (val result = userRepository.claimDoubleXp(eventId)) {
                is UserRepository.DoubleXpResult.Paid -> {
                    earnedXp += result.xpAwarded
                    _score.postValue(earnedXp)
                    if (result.leveledUp) {
                        _levelUp.postValue(
                            LevelUpEvent(result.level, result.milestonePoints)
                        )
                    }
                    _doubleOutcome.postValue(DoubleOutcome.Paid(result.xpAwarded))
                }
                is UserRepository.DoubleXpResult.AlreadyDoubled ->
                    _doubleOutcome.postValue(DoubleOutcome.AlreadyPaid)
                is UserRepository.DoubleXpResult.Error ->
                    _doubleOutcome.postValue(DoubleOutcome.Failed)
            }
        }
    }

    private fun showCurrentQuestion() {
        _currentQuestion.value = quiz.questions[currentQuestionIndex]
    }
}
