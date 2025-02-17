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
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentQuizListBinding
import com.example.pixelpayout.data.api.Quiz
import java.util.concurrent.TimeUnit
import com.pixelpayout.utils.AdManager

class QuizListFragment : Fragment() {
    private var _binding: FragmentQuizListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: QuizListViewModel by viewModels()
    private lateinit var quizAdapter: QuizAdapter
    private val quizLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.loadQuizzes()
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
        observeViewModel()
        // Load quizzes whenever fragment becomes visible
        loadQuizzes()

        // Preload ad
        viewModel.preloadAd(requireContext())

        binding.watchAdButton.setOnClickListener {
            showRewardedAd()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload quizzes when returning to this fragment
        loadQuizzes()
    }

    private fun setupRecyclerView() {
        quizAdapter = QuizAdapter { quiz ->
            startQuiz(quiz)
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
            binding.contentLayout.visibility = if (isLoading) View.GONE else View.VISIBLE
        }

        viewModel.dataLoaded.observe(viewLifecycleOwner) { loaded ->
            if (loaded) {
                binding.contentLayout.visibility = View.VISIBLE
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

    private fun loadQuizzes() {
        binding.contentLayout.visibility = View.GONE
        binding.progressIndicator.visibility = View.VISIBLE
        viewModel.loadQuizzes()
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