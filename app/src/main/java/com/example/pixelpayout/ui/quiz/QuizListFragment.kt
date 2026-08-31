package com.example.pixelpayout.ui.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.children
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.utils.SpacingItemDecoration
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.api.QuizCategory
import java.util.concurrent.TimeUnit
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.main.MAX_DAILY_QUIZ_ATTEMPTS
import com.example.pixelpayout.utils.ServerClock

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by activityViewModels()

    /**
     * Attempts and the reset countdown come from the shared user snapshot
     * rather than from QuizListViewModel's callable. QuizListViewModel still
     * owns the quiz CONTENT (categories, cached questions), which is a
     * different concern and costs nothing.
     */
    private val mainViewModel: MainViewModel by activityViewModels()
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
        // Nothing to refresh: claimReward writes the attempt counter to the
        // user document, and the snapshot listener redraws from it. This used
        // to fire checkAndResetQuizAttempts after EVERY quiz - a Firestore
        // read per quiz to learn a number the snapshot was about to deliver.
        if (result.resultCode == Activity.RESULT_OK) Unit
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

        buildPips()
        // Before the observer, which does not fire until the user snapshot
        // arrives. An unpainted card reads as an empty allowance rather than
        // as one nobody has checked yet.
        renderLoading()
        setupRecyclerView()
        observeViewModel()

        viewModel.loadCachedQuizzes(requireContext())
        viewModel.checkAndUpdateQuizzes(requireContext())
    }

    override fun onResume() {
        super.onResume()
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

    private fun observeViewModel() {
        viewModel.categories.observe(viewLifecycleOwner) { categoryList ->
            quizAdapter = QuizAdapter(categoryList) { category ->
                fetchQuizzesForCategory(category)
            }
            binding.recyclerView.adapter = quizAdapter
        }

        mainViewModel.quizAttemptsToday.observe(viewLifecycleOwner) { used ->
            renderAllowance(used.coerceIn(0, MAX_DAILY_QUIZ_ATTEMPTS))
        }

        // Timer will be updated in the timerRunnable
    }

    /**
     * One pip per attempt in the allowance, sized by weight so the row fills
     * the card whatever the cap happens to be. Built here rather than in XML
     * so a change to MAX_DAILY_QUIZ_ATTEMPTS cannot leave a stale count.
     */
    private fun buildPips() {
        val row = binding.quizPips
        val gap = resources.getDimensionPixelSize(R.dimen.game_pip_gap)
        row.removeAllViews()

        repeat(MAX_DAILY_QUIZ_ATTEMPTS) { index ->
            val pip = View(requireContext())
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) params.marginStart = gap
            pip.layoutParams = params
            row.addView(pip)
        }
    }

    /** The state before the first snapshot: no count, no pips lit. */
    private fun renderLoading() {
        binding.tvQuizzesLeft.text = getString(R.string.quizzes_attempts_loading)
        binding.quizPips.children.forEach { pip ->
            pip.setBackgroundResource(R.drawable.bg_pip_spent)
        }
    }

    private fun renderAllowance(used: Int) {
        val remaining = MAX_DAILY_QUIZ_ATTEMPTS - used

        binding.tvQuizzesLeft.text = if (remaining > 0) {
            getString(R.string.quizzes_attempts_left, remaining, MAX_DAILY_QUIZ_ATTEMPTS)
        } else {
            // True as written: games are a separate counter on the same day
            // stamp, so a spent quiz allowance leaves them untouched.
            getString(R.string.quizzes_attempts_spent)
        }

        // Spent pips grey from the left, so the violet that remains reads as
        // what is left rather than as what has been used.
        binding.quizPips.children.forEachIndexed { index, pip ->
            pip.setBackgroundResource(
                if (index < used) R.drawable.bg_pip_spent else R.drawable.bg_pip_remaining
            )
        }
    }

    private fun updateCountdownTimer() {
        if (_binding == null) return

        // The boundary is computed from the server clock, so it is always in
        // the future. The old version derived it from the stored
        // last_reset_time, which could sit in the past - leaving the
        // countdown permanently expired and, until it was guarded, firing a
        // refresh request every single second it stayed that way.
        val seconds =
            ((mainViewModel.nextAttemptsResetMillis() - ServerClock.now()) / 1_000)
                .coerceAtLeast(0)

        // HH:MM:SS in a monospace face, matching Play > Games - a ticker whose
        // digits do not shuffle sideways once a second.
        binding.tvResetTimer.text = String.format(
            "%02d:%02d:%02d",
            TimeUnit.SECONDS.toHours(seconds),
            TimeUnit.SECONDS.toMinutes(seconds) % 60,
            seconds % 60
        )
    }

    private fun fetchQuizzesForCategory(category: QuizCategory) {
        // Read from the shared snapshot, the same source the counter card draws.
        // This used to read QuizListViewModel.dailyAttempts, which is seeded to
        // MAX_DAILY_ATTEMPTS and only corrected once a callable comes back - so
        // a tap before that landed was refused on a number nobody had checked.
        if (mainViewModel.quizAttemptsNow() >= MAX_DAILY_QUIZ_ATTEMPTS) {
            Toast.makeText(requireContext(), R.string.quizzes_limit_toast, Toast.LENGTH_LONG).show()
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
