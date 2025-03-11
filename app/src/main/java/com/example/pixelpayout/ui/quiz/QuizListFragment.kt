package com.pixelpayout.ui.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.utils.SpacingItemDecoration

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by activityViewModels()
    private lateinit var quizAdapter: QuizAdapter
    private val quizLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val completedQuizId = result.data?.getStringExtra("COMPLETED_QUIZ_ID") ?: return@registerForActivityResult
            viewModel.onQuizCompleted(completedQuizId)
        }
    }

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
        loadQuizzes()
    }

    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter(viewModel.categories) { category ->
            fetchQuizzesForCategory(category)
        }

        binding.recyclerView.apply {
            adapter = quizAdapter
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL) // 2 columns
            addItemDecoration(SpacingItemDecoration(24)) // 24dp spacing (adjust as needed)
        }

    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            loadQuizzes(forceRefresh = true)
        }
    }

    private fun observeViewModel() {
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.selectedQuiz.observe(viewLifecycleOwner) { quiz ->
            quiz?.let {
                startQuiz(it)
            }
        }


        viewModel.loadingState.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefresh.isRefreshing = isLoading
        }

        // Instead of observing quizzes, we directly use the category list
        quizAdapter = QuizAdapter(viewModel.categories) { category ->
            fetchQuizzesForCategory(category)
        }
        binding.recyclerView.adapter = quizAdapter
        binding.recyclerView.visibility = View.VISIBLE

    }

    private fun startQuiz(quiz: Quiz) {
        val intent = Intent(requireContext(), QuizActivity::class.java).apply {
            putExtra(QuizActivity.EXTRA_QUIZ, quiz)
        }
        startActivity(intent)
    }


    private fun loadQuizzes(forceRefresh: Boolean = false) {
        viewModel.loadQuizzes(forceRefresh)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun fetchQuizzesForCategory(category: QuizListViewModel.QuizCategory) {
        viewModel.loadSingleQuizByCategory(category.apiUrl)
    }


}