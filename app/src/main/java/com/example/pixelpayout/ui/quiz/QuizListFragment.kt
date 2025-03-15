package com.pixelpayout.ui.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.pixelpayout.ui.quiz.QuizCategory

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.utils.SpacingItemDecoration
import com.pixelpayout.data.model.Quiz

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by activityViewModels()

    private lateinit var quizAdapter: QuizAdapter // ✅ Declare but don't initialize

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
        setupSwipeRefresh()
        observeViewModel()

        viewModel.loadCachedQuizzes(requireContext())
        viewModel.checkAndUpdateQuizzes(requireContext())

        viewModel.fetchDailyAttempts() // 🔥 Fetch attempts when fragment loads
    }


    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter(emptyList()) { category -> // ✅ Initialize with empty list
            fetchQuizzesForCategory(category)
        }

        binding.recyclerView.apply {
            adapter = quizAdapter
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            addItemDecoration(SpacingItemDecoration(43) )
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            // Force refresh attempts when user manually pulls to refresh
            viewModel.fetchDailyAttempts(forceRefresh = true)
            viewModel.checkAndUpdateQuizzes(requireContext())
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun observeViewModel() {
        viewModel.categories.observe(viewLifecycleOwner) { categoryList ->
            quizAdapter = QuizAdapter(categoryList) { category ->
                fetchQuizzesForCategory(category)
            }
            binding.recyclerView.adapter = quizAdapter
        }

        viewModel.dailyAttempts.observe(viewLifecycleOwner) { attempts ->
            val remaining = maxOf(viewModel.MAX_DAILY_ATTEMPTS - attempts, 0)  // Using the constant from ViewModel
            binding.tvQuizzesLeft.text = "Quizzes Left:  $remaining"
        }
    }

    private fun fetchQuizzesForCategory(category: QuizCategory) {
        // Get the current attempts value directly instead of creating a new observer
        val attempts = viewModel.dailyAttempts.value ?: 0
        if (attempts >= viewModel.MAX_DAILY_ATTEMPTS) {
            Toast.makeText(requireContext(), "Daily quiz limit reached. Try again tomorrow!", Toast.LENGTH_LONG).show()
            return
        }

        val selectedQuiz = viewModel.getQuizByCategory(category.name)
        if (selectedQuiz != null) {
            startQuiz(selectedQuiz)
        } else {
            Toast.makeText(requireContext(), "No quizzes found for ${category.name}", Toast.LENGTH_SHORT).show()
        }
    }



    private fun startQuiz(quiz: Quiz) {
        if (quiz.questions.isEmpty() || quiz.questions.any { it.text.isNullOrEmpty() }) {
            Toast.makeText(requireContext(), "Quiz data is incomplete!", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(requireContext(), QuizActivity::class.java).apply {
            putExtra(QuizActivity.EXTRA_QUIZ, quiz)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Force refresh the daily attempts when returning from a quiz
        viewModel.fetchDailyAttempts(forceRefresh = true)
    }





}
