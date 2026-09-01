package com.example.pixelpayout.ui.quiz

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityQuizBinding
import com.example.pixelpayout.data.api.Question
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.utils.AdCadence
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.utils.InterstitialAdManager
import com.example.pixelpayout.utils.showLevelUp
import com.example.pixelpayout.ui.main.MainActivity
import android.text.Html
import android.os.Build
import kotlinx.coroutines.launch

class QuizActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQuizBinding
    private val viewModel: QuizViewModel by viewModels()
    private var timer: CountDownTimer? = null
    private var selectedAnswerIndex: Int = -1

    /** The results dialog, while it is up, so the ad flow can drive it. */
    private var resultsDialog: QuizResultsDialog? = null

    /**
     * Whether a rewarded ad played as part of finishing this quiz.
     *
     * Read on the way out, where it suppresses the interstitial entirely - a
     * player who took the double has already watched a full-screen ad for us
     * at this exact transition. See AdCadence.
     */
    private var rewardedAdShown = false

    /** Stops a double-tap sending the offer twice while the ad opens. */
    private var doubleInFlight = false

    /**
     * Whether this attempt has already been spent.
     *
     * Three things can end a quiz - answering, the clock running out, and
     * leaving the app - and every one of them claims. Without this they can
     * claim TWICE: the timer was never cancelled on a manual answer, so
     * sitting on the results dialog past the countdown fired a second claim
     * and burned a second attempt.
     */
    private var answerSubmitted = false
    private lateinit var connectivityCheck: AndroidConnectivityCheck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // No way back out. The attempt is spent on entry, so a back gesture
        // that returned the user to the list would look like a free exit from
        // something already paid for. Registered before anything can finish
        // the activity, and deliberately does nothing at all.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        connectivityCheck = AndroidConnectivityCheck(this)
        setupConnectivityCheck()

        // Quiz is Serializable, not Parcelable - reading it as a Parcelable
        // always yields null, which silently finished this activity.
        val quiz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_QUIZ, Quiz::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_QUIZ) as? Quiz
        }

        if (quiz != null) {
            viewModel.setQuiz(quiz)
            binding.quizCategory.text = quiz.category
        } else {
            finish()
        }

        viewModel.isQuizComplete.observe(this) { isComplete ->
            if (isComplete && viewModel.totalPoints.value == null) {
                Toast.makeText(this, "Daily quiz limit reached. Try again tomorrow!", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        setupViews()
        observeViewModel()
        startTimer()
    }

    private fun setupConnectivityCheck() {
        lifecycleScope.launch {
            connectivityCheck.isConnected.collect { isConnected ->
                if (!isConnected && !isFinishing) {
                    MainActivity.handleInternetDisconnection(this@QuizActivity)
                }
            }
        }
    }

    private fun setupViews() {
        binding.quizXpNote.text = getString(R.string.quiz_xp_if_correct, QUIZ_CORRECT_XP)

        binding.timerRing.max = QUIZ_SECONDS
        binding.timerRing.progress = QUIZ_SECONDS

        binding.submitButton.setOnClickListener {
            if (selectedAnswerIndex == -1) {
                Toast.makeText(this, R.string.select_answer, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.submitButton.isEnabled = false

            checkAnswer()
        }
    }

    private fun observeViewModel() {
        viewModel.currentQuestion.observe(this) { question ->
            displayQuestion(question)
        }

        viewModel.isQuizComplete.observe(this) { isComplete ->
            if (isComplete) {
                showQuizCompleteDialog()
            }
        }

        viewModel.doubleOutcome.observe(this) { outcome ->
            doubleInFlight = false
            when (outcome) {
                is QuizViewModel.DoubleOutcome.Paid -> resultsDialog?.settleOffer(
                    getString(R.string.double_xp_done, outcome.xpAwarded),
                    viewModel.score.value
                )
                is QuizViewModel.DoubleOutcome.AlreadyPaid -> resultsDialog?.settleOffer(
                    getString(R.string.double_xp_already), null
                )
                // Saying the earned XP is safe is the point of this message.
                // It is - the answer was paid before the offer was ever shown
                // - and nothing else on screen says so.
                is QuizViewModel.DoubleOutcome.Failed -> resultsDialog?.settleOffer(
                    getString(R.string.double_xp_failed), null
                )
            }
        }

        viewModel.totalPoints.observe(this) { totalPoints ->
            // Handle total points update if needed
        }

        viewModel.levelUp.observe(this) { event ->
            event?.let { showLevelUp(it) }
        }
    }

    private fun displayQuestion(question: Question) {
        binding.apply {
            val decodedQuestion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(question.text, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(question.text)
            }
            questionText.text = decodedQuestion

            // Clear previous answers
            optionsContainer.removeAllViews()
            selectedAnswerIndex = -1  // Reset selection

            question.options.forEachIndexed { index, option ->
                val optionView = LayoutInflater.from(this@QuizActivity)
                    .inflate(R.layout.item_option, optionsContainer, false)
                val textView = optionView.findViewById<TextView>(R.id.optionText)
                val cardView = optionView.findViewById<CardView>(R.id.optionCard)

                textView.text = option
                cardView.setBackgroundResource(R.drawable.default_option_background)

                optionView.setOnClickListener {
                    resetOptions() // Reset all options before selecting a new one
                    selectedAnswerIndex = index
                    cardView.setBackgroundResource(R.drawable.selected_option_background)
                }

                optionsContainer.addView(optionView)
            }
        }
    }

    private fun resetOptions() {
        for (i in 0 until binding.optionsContainer.childCount) {
            val child = binding.optionsContainer.getChildAt(i)
            val cardView = child.findViewById<CardView>(R.id.optionCard)
            cardView.setBackgroundResource(R.drawable.default_option_background)
        }
    }

    private fun checkAnswer() {
        val correctIndex = viewModel.currentQuestion.value?.correctAnswer ?: -1

        for (i in 0 until binding.optionsContainer.childCount) {
            val child = binding.optionsContainer.getChildAt(i)
            val cardView = child.findViewById<CardView>(R.id.optionCard)

            if (i == correctIndex) {
                // Highlight correct answer in green
                cardView.setBackgroundResource(R.drawable.correct_option_background)
            } else if (i == selectedAnswerIndex) {
                // Highlight the selected wrong answer in red
                cardView.setBackgroundResource(R.drawable.wrong_option_background)
            } else {
                // Keep unselected answers in default state
                cardView.setBackgroundResource(R.drawable.default_option_background)
            }
        }
        submitOnce(selectedAnswerIndex)
    }

    /**
     * Spends the attempt, once, however the quiz ended.
     *
     * Cancels the countdown on the way through: a timer left running after a
     * manual answer eventually fires its own claim.
     */
    private fun submitOnce(answerIndex: Int) {
        if (answerSubmitted) return
        answerSubmitted = true
        timer?.cancel()
        viewModel.submitAnswer(answerIndex)
    }

    private fun startTimer() {
        timer = object : CountDownTimer(QUIZ_SECONDS * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                binding.timerText.text = getString(R.string.timer_format, secondsLeft)
                // Counts DOWN, so the ring empties as the time does.
                binding.timerRing.progress = secondsLeft.toInt()
            }

            override fun onFinish() {
                submitOnce(-1) // Time's up, wrong answer
            }
        }.start()
    }

    private fun showQuizCompleteDialog() {
        resultsDialog = QuizResultsDialog.show(
            fragmentManager = supportFragmentManager,
            points = viewModel.score.value ?: 0,
            canDouble = viewModel.canDouble(),
            onWatchAd = { watchAdToDouble() },
            onDismiss = {
                val resultIntent = Intent()
                resultIntent.putExtra("COMPLETED_QUIZ_ID", viewModel.quizId.value ?: "")
                setResult(RESULT_OK, resultIntent)
                leave()
            }
        )
    }

    /**
     * Watches a rewarded ad, then doubles the answer's XP.
     *
     * Deliberately the same shape as the game results screen: the claim fires
     * from the REWARD callback rather than from dismissal, because both
     * arrive on a normal completion but the reward comes first, which shrinks
     * the window in which a killed process loses an ad the player actually
     * sat through.
     */
    private fun watchAdToDouble() {
        if (doubleInFlight) return
        doubleInFlight = true

        // "Finding an ad" first, because showRewardedAdWhenReady waits a few
        // seconds for one rather than refusing the tap outright - the label
        // has to describe that wait before it can honestly claim to be
        // doubling anything.
        resultsDialog?.showAdInProgress(R.string.double_xp_finding)

        var rewarded = false
        AdManager.getInstance().showRewardedAdWhenReady(
            activity = this,
            onRewarded = {
                if (!rewarded) {
                    rewarded = true
                    rewardedAdShown = true
                    resultsDialog?.showAdInProgress(R.string.double_xp_claiming)
                    viewModel.claimDoubleXp()
                }
            },
            // Dismissal without a reward means the ad was closed early. The
            // offer stands - nothing was spent, so the button comes back.
            onAdClosed = {
                if (!rewarded) {
                    doubleInFlight = false
                    resultsDialog?.restoreOffer(null)
                }
            },
            onAdFailedToShow = {
                if (!rewarded) {
                    doubleInFlight = false
                    resultsDialog?.restoreOffer(R.string.double_xp_unavailable)
                }
            }
        )
    }

    /**
     * Leaves a finished quiz, showing an interstitial if one is due.
     *
     * Quizzes share ONE cadence counter with games rather than keeping their
     * own - see [AdCadence]. Somebody alternating a quiz with a game is having
     * one session, not two, and two counters would show them twice the ads the
     * interval is meant to allow.
     *
     * [rewardedAdShown] suppresses the interstitial outright when the player
     * took the double: they have already watched a full-screen ad for us at
     * this transition, and following it with a second one would spend our
     * best impression to set up our worst.
     */
    private fun leave() {
        if (AdCadence.onActivityCompleted(this, rewardedShown = rewardedAdShown)) {
            InterstitialAdManager.getInstance().show(this) { finish() }
        } else {
            finish()
        }
    }

    /**
     * Leaving the app mid-question spends the attempt, as a wrong answer.
     *
     * onStop rather than onPause: onPause also fires for things that merely
     * cover the activity - a dialog, a permission prompt, the notification
     * shade - and none of those are the user leaving. onStop is "no longer
     * visible", which is minimising, switching apps, or locking the screen.
     *
     * A rotation is not leaving either, hence isChangingConfigurations.
     *
     * The results dialog is NOT shown from here. The observer that shows it
     * is lifecycle-aware, so it holds the value while the activity is stopped
     * and delivers it when the user comes back - which is both what we want
     * and the only safe option, since showing a DialogFragment after the
     * state has been saved throws.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) submitOnce(-1)
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    companion object {
        const val EXTRA_QUIZ = "extra_quiz"

        /** Mirrors the server's QUIZ_CORRECT_XP, for the header's XP line. */
        private const val QUIZ_CORRECT_XP = 10

        /** The countdown's length, shared by the timer and the ring's max. */
        private const val QUIZ_SECONDS = 15
    }
}
