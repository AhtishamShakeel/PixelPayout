package com.pixelpayout.ui.quiz


import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.pixelpayout.R
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.databinding.ActivityQuizBinding
import android.text.Html
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.data.model.Question
import com.pixelpayout.ui.quiz.QuizListViewModel.Companion.MAX_DAILY_QUIZZES
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import com.google.firebase.Timestamp

class QuizActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQuizBinding
    private val viewModel: QuizViewModel by viewModels()
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Replace the quiz retrieval code in onCreate()
        val quiz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_QUIZ, Quiz::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_QUIZ)
        }

        if (quiz != null) {
            viewModel.setQuiz(quiz)
        } else {
            finish()
        }

        setupViews()
        observeViewModel()
        startTimer()
    }

    private fun setupViews() {
        binding.submitButton.setOnClickListener {
            binding.submitButton.isEnabled = false
            val selectedId = binding.optionsGroup.checkedRadioButtonId
            if (selectedId == -1) {
                Toast.makeText(this, R.string.select_answer, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedOption = findViewById<RadioButton>(selectedId)
            val answerIndex = binding.optionsGroup.indexOfChild(selectedOption)
            viewModel.submitAnswer(answerIndex)
        }
    }

    private fun observeViewModel() {
        viewModel.currentQuestion.observe(this) { question ->
            displayQuestion(question)
        }

        viewModel.isQuizComplete.observe(this) { isComplete ->
            if (isComplete) {
                timer?.cancel()
                showQuizCompleteDialog()
            }
        }

        viewModel.totalPoints.observe(this) { totalPoints ->
            // Points have been updated in Firebase
            // We can now show the results dialog
        }
    }

    private fun displayQuestion(question: Question) {
        binding.apply {
            // Decode HTML entities in the question text
            val decodedQuestion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(question.text, Html.FROM_HTML_MODE_LEGACY).toString()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(question.text)
            }
            questionText.text = decodedQuestion

            // Clear previous answers
            optionsGroup.removeAllViews()

            // Add radio buttons for answers
            question.options.forEachIndexed { index, option ->
                val radioButton = RadioButton(this@QuizActivity).apply {
                    id = View.generateViewId()
                    // Decode HTML entities in the answer options
                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Html.fromHtml(option, Html.FROM_HTML_MODE_LEGACY)
                    } else {
                        @Suppress("DEPRECATION")
                        Html.fromHtml(option)
                    }
                    textSize = 16f
                    setPadding(0, 16, 0, 16)
                }
                optionsGroup.addView(radioButton)
            }
        }
    }

    private fun startTimer() {
        timer = object : CountDownTimer(30000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.timerText.text = getString(
                    R.string.timer_format,
                    millisUntilFinished / 1000
                )
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
                resultIntent.putExtra("COMPLETED_QUIZ_ID", viewModel.quizId.value)
                setResult(Activity.RESULT_OK,  resultIntent)
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