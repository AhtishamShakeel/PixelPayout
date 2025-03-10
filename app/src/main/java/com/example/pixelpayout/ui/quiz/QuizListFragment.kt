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
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.utils.SpacingItemDecoration
import java.util.concurrent.TimeUnit
import com.pixelpayout.utils.AdManager

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
            // Show loading state
            binding.progressIndicator.visibility = View.VISIBLE
            binding.contentLayout.visibility = View.GONE

            // Trigger quiz completion and reload
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

        // Initial load only if we haven't loaded before
        loadQuizzes()

        // Preload ad
        viewModel.preloadAd(requireContext())

        binding.watchAdButton.setOnClickListener {
            showRewardedAd()
        }
    }

    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter { quiz ->
            startQuiz(quiz)
        }

        binding.recyclerView.apply {
            adapter = quizAdapter
            layoutManager = StaggeredGridLayoutManager(2, GridLayoutManager.VERTICAL)
            addItemDecoration(SpacingItemDecoration(43))
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            loadQuizzes(forceRefresh = true)
        }
    }

    private fun observeViewModel() {
        viewModel.quizzes.observe(viewLifecycleOwner) { quizzes ->
            quizAdapter.submitList(quizzes)
        }

        viewModel.loadingState.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading && !binding.swipeRefresh.isRefreshing) View.VISIBLE else View.GONE
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
            if (!isLoading) {
                binding.swipeRefresh.isRefreshing = false
            }
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

        viewModel.adAvailable.observe(viewLifecycleOwner) { available ->
            binding.watchAdButton.isEnabled = available
        }
    }

    private fun loadQuizzes(forceRefresh: Boolean = false) {
        viewModel.loadQuizzes(forceRefresh)
    }

    private fun startQuiz(quiz: Quiz) {
        val intent = Intent(requireContext(), QuizActivity::class.java).apply {
            putExtra(QuizActivity.EXTRA_QUIZ, quiz)
        }
        quizLauncher.launch(intent)
    }

    private fun showRewardedAd() {
        AdManager.getInstance().showRewardedAd(
            requireActivity(),
            onRewarded = {
                viewModel.watchAdForExtraQuiz()
            },
            onAdClosed = {
                // Ad closed without reward
            },
            onAdFailedToShow = {
                Toast.makeText(context, R.string.ad_not_available, Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_QUIZ = 1001
    }
}