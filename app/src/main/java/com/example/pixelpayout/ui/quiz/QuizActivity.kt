package com.pixelpayout.ui.quiz


import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.pixelpayout.R
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.databinding.ActivityQuizBinding
import android.text.Html
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.data.model.Question
import com.pixelpayout.ui.main.MainActivity
import com.pixelpayout.ui.quiz.QuizListViewModel.Companion.MAX_DAILY_QUIZZES
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import kotlin.math.abs

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
                showResults()
            }
        }

        viewModel.score.observe(this) { score ->
            // Update points in Firebase
            // Will implement in next step
        }
    }

    private fun displayQuestion(question: Question) {
        binding.apply {
            // Decode HTML entities in the question text
            val decodedQuestion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(question.text, Html.FROM_HTML_MODE_LEGACY)
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

    private fun showResults() {
        viewModel.updatePoints {
            // Set a result to notify MainActivity that points were updated
            setResult(RESULT_OK)
        }

        QuizResultsDialog.newInstance(
            pointsEarned = viewModel.score.value ?: 0
        ) {
            finish()  // Finish QuizActivity after dialog is dismissed
        }.show(supportFragmentManager, "results")
    }

    private fun updateScore(@Suppress("UNUSED_PARAMETER") score: Int) {
        // ...
    }

    private fun updateOptions(options: List<String>, @Suppress("UNUSED_PARAMETER") index: Int) {
        // ...
    }
    private suspend fun validateAttempt(): Boolean {
        return try {
            FirebaseFirestore.getInstance().runTransaction { transaction ->
                val user = FirebaseAuth.getInstance().currentUser
                    ?: throw Exception("Not authenticated")

                val userRef = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(user.uid)

                val snapshot = transaction.get(userRef)
                val lastAttempt = snapshot.getTimestamp("lastQuizDate")?.toDate()
                val attempts = (snapshot.getLong("quizAttempts") ?: 0).toInt()

                val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val serverTime = snapshot.getTimestamp("serverTime")?.toDate()
                    ?: Date() // Fallback to device time

                // Validate server time vs device time
                val timeDiff = abs(now.timeInMillis - serverTime.time)
                if (timeDiff > 300000) { // 5 minutes tolerance
                    throw Exception("Time synchronization failed")
                }

                if (lastAttempt != null && isSameUTCDay(lastAttempt, now)) {
                    if (attempts >= MAX_DAILY_QUIZZES) false else {
                        transaction.update(userRef,
                            "quizAttempts", attempts + 1,
                            "lastQuizDate", FieldValue.serverTimestamp(),
                            "serverTime", FieldValue.serverTimestamp()
                        )
                        true
                    }
                } else {
                    transaction.update(userRef,
                        "quizAttempts", 1,
                        "lastQuizDate", FieldValue.serverTimestamp(),
                        "serverTime", FieldValue.serverTimestamp()
                    )
                    true
                }
            }.await()
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        timer?.cancel()
    }
    // Add this inside QuizActivity class
    private fun isSameUTCDay(date: Date, calendar: Calendar): Boolean {
        val compareCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = date
        }
        return compareCal.get(Calendar.YEAR) == calendar.get(Calendar.YEAR) &&
                compareCal.get(Calendar.DAY_OF_YEAR) == calendar.get(Calendar.DAY_OF_YEAR)
    }


    companion object {
        const val EXTRA_QUIZ = "extra_quiz"
    }

}