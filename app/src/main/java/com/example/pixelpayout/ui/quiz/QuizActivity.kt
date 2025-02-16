package com.pixelpayout.ui.quiz

import android.os.Bundle
import android.view.View
import android.widget.RadioButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityQuizBinding
import com.pixelpayout.data.model.QuizQuestion
import android.app.AlertDialog

class QuizActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQuizBinding
    private val viewModel: QuizViewModel by viewModels()
    private var selectedAnswerIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get the quiz ID from intent
        val quizId = intent.getIntExtra(EXTRA_QUIZ_ID, -1)
        if (quizId == -1) {
            finish()
            return
        }

        // Set the quiz ID in ViewModel
        viewModel.setQuizId(quizId)

        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.optionsGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedAnswerIndex = when (checkedId) {
                R.id.option1 -> 0
                R.id.option2 -> 1
                R.id.option3 -> 2
                R.id.option4 -> 3
                else -> -1
            }
            binding.submitButton.isEnabled = selectedAnswerIndex != -1
        }

        binding.submitButton.setOnClickListener {
            if (selectedAnswerIndex != -1) {
                viewModel.submitAnswer(selectedAnswerIndex)
                showAnswerResultDialog()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.questionLiveData.observe(this) { question ->
            updateQuestion(question)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.score.observe(this) { score ->
            supportActionBar?.subtitle = getString(R.string.points_value, score)
        }

        viewModel.isQuizComplete.observe(this) { isComplete ->
            if (isComplete) {
                showQuizComplete()
            }
        }
    }

    private fun updateQuestion(question: QuizQuestion) {
        binding.questionText.text = question.questionText
        updateOptions(question.options)
    }

    private fun updateOptions(options: List<String>) {
        val optionViews = listOf(
            binding.option1,
            binding.option2,
            binding.option3,
            binding.option4
        )

        options.forEachIndexed { index, text ->
            optionViews.getOrNull(index)?.text = text
        }
    }

    private fun showQuizComplete() {
        viewModel.score.value?.let { score ->
            QuizResultsDialog.newInstance(score) {
                viewModel.updatePoints {
                    finish()
                }
            }.show(supportFragmentManager, "quiz_results")
        }
    }

    private fun showAnswerResultDialog() {
        val isCorrect = viewModel.isLastAnswerCorrect()
        val points = viewModel.getLastQuestionPoints()

        AlertDialog.Builder(this)
            .setTitle(if (isCorrect) "Correct!" else "Incorrect")
            .setMessage(if (isCorrect) "You earned $points points!" else "Better luck next time!")
            .setPositiveButton("Next") { _, _ ->
                viewModel.loadNewQuestion()
            }
            .setCancelable(false)
            .show()
    }

    companion object {
        const val EXTRA_QUIZ_ID = "extra_quiz_id"
    }
}