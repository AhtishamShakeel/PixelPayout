package com.example.pixelpayout.ui.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.utils.SpacingItemDecoration
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.api.QuizCategory
import java.util.concurrent.TimeUnit

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by activityViewModels()
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateCountdownTimer()
            timerHandler.postDelayed(this, 1000) // Update every second
        }
    }

    // Add activity result launcher to listen for quiz completion
    private val quizLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Quiz was completed, refresh the attempts
            viewModel.fetchDailyAttempts(forceRefresh = true)
        }
    }

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
        setupSwipeRefresh()
        observeViewModel()

        viewModel.loadCachedQuizzes(requireContext())
        viewModel.checkAndUpdateQuizzes(requireContext())

        // Remove the fetch here - it's now done at app startup in MainActivity
        // viewModel.fetchDailyAttempts()
    }

    override fun onResume() {
        super.onResume()
        // Don't force refresh every time - only start the timer
        // viewModel.fetchDailyAttempts(forceRefresh = true)
        
        // Start the countdown timer
        timerHandler.post(timerRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        // Stop the timer when fragment is paused
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter(emptyList()) { category ->
            fetchQuizzesForCategory(category)
        }

        binding.recyclerView.apply {
            adapter = quizAdapter
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
            addItemDecoration(SpacingItemDecoration(43))
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
            binding.tvQuizzesLeft.text = "Quizzes Left: $remaining"
        }

        // Timer will be updated in the timerRunnable
    }
    
    private fun updateCountdownTimer() {
        val nextResetTime = viewModel.nextResetTime.value ?: return
        val currentTime = System.currentTimeMillis()
        
        val timeUntilReset = nextResetTime - currentTime
        
        if (timeUntilReset <= 0) {
            binding.tvResetTimer.text = "Resetting soon..."
            // Could trigger a refresh here
            viewModel.fetchDailyAttempts(forceRefresh = true)
            return
        }
        
        // Format the time remaining
        val hours = TimeUnit.MILLISECONDS.toHours(timeUntilReset)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeUntilReset) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeUntilReset) % 60
        
        // Display different formats based on time remaining
        val timerText = when {
            hours > 0 -> "Resets in: ${hours}h ${minutes}m"
            minutes > 0 -> "Resets in: ${minutes}m ${seconds}s"
            else -> "Resets in: ${seconds}s"
        }
        
        binding.tvResetTimer.text = timerText
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
        // Use the launcher instead of startActivity to get the result
        quizLauncher.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
