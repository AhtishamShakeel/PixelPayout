package com.pixelpayout.ui.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.test.isEnabled
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.pixelpayout.data.model.Quiz
import java.util.concurrent.TimeUnit

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by viewModels()
    private lateinit var quizAdapter: QuizAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuizListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        // Load quizzes whenever fragment becomes visible
        loadQuizzes()
    }

    override fun onResume() {
        super.onResume()
        // Reload quizzes when returning to this fragment
        loadQuizzes()
    }

    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter { quiz ->
            startActivityForResult(
                Intent(requireContext(), QuizActivity::class.java)
                    .putExtra(QuizActivity.EXTRA_QUIZ, quiz),
                REQUEST_QUIZ
            )
        }

        binding.recyclerView.apply {
            adapter = quizAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun observeViewModel() {
        viewModel.quizzes.observe(viewLifecycleOwner) { quizzes ->
            quizAdapter.submitList(quizzes)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.quizLimitReached.observe(viewLifecycleOwner) { limitReached ->
            binding.apply {
                if (limitReached) {
                    limitReachedLayout.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                    swipeRefresh.isEnabled = false
                } else {
                    limitReachedLayout.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    swipeRefresh.isEnabled = true
                }
            }
        }

        viewModel.nextQuizTime.observe(viewLifecycleOwner) { nextQuizTime ->
            if (nextQuizTime > 0) {
                val remainingTime = nextQuizTime - System.currentTimeMillis()
                if (remainingTime > 0) {
                    val hours = TimeUnit.MILLISECONDS.toHours(remainingTime)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60

                    binding.nextQuizText.text = getString(
                        R.string.next_quiz_time,
                        hours,
                        minutes
                    )
                    binding.nextQuizText.visibility = View.VISIBLE
                } else {
                    binding.nextQuizText.visibility = View.GONE
                    // Time has passed, reload quizzes
                    loadQuizzes()
                }
            }
        }

        viewModel.remainingQuizzes.observe(viewLifecycleOwner) { remaining ->
            binding.remainingQuizzesText.text = getString(
                R.string.remaining_quizzes,
                remaining
            )
        }
    }

    private fun loadQuizzes() {
        viewModel.loadQuizzes()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_QUIZ && resultCode == Activity.RESULT_OK) {
            // Reload quizzes to update attempts count
            viewModel.loadQuizzes()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_QUIZ = 1001
    }
} 