package com.example.pixelpayout.ui.quiz

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityQuizBinding
import com.example.pixelpayout.data.api.Question
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.utils.AndroidConnectivityCheck
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
    private lateinit var connectivityCheck: AndroidConnectivityCheck

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
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
        viewModel.submitAnswer(selectedAnswerIndex)
    }

    private fun startTimer() {
        timer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.timerText.text = getString(R.string.timer_format, millisUntilFinished / 1000)
            }

            override fun onFinish() {
                viewModel.submitAnswer(-1) // Time's up, wrong answer
            }
        }.start()
    }

    private fun showQuizCompleteDialog() {
        QuizResultsDialog.show(
            fragmentManager = supportFragmentManager,
            points = viewModel.score.value ?: 0,
            onDismiss = {
                val resultIntent = Intent()
                resultIntent.putExtra("COMPLETED_QUIZ_ID", viewModel.quizId.value ?: "")
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
    }

    companion object {
        const val EXTRA_QUIZ = "extra_quiz"
    }
}
