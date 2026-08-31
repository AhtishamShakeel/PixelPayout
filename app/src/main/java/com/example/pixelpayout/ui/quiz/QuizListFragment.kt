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
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.main.MAX_DAILY_BONUS_ATTEMPTS
import com.example.pixelpayout.ui.main.MAX_DAILY_QUIZ_ATTEMPTS
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.ServerClock
import kotlinx.coroutines.launch

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
            // Polled here for the same reason Play > Games polls it: AdManager
            // holds one availability listener and Home owns it, so a second
            // registration would silently unsubscribe Home's.
            refreshBonusButtonState()
            timerHandler.postDelayed(this, 1000) // Update every second
        }
    }

    /** How many pips the row currently holds, so it is only rebuilt on change. */
    private var pipCount = 0

    /** True from the tap until the grant settles, so one ad buys one attempt. */
    private var bonusInFlight = false

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

        AdManager.getInstance().loadRewardedAd(requireContext())
        binding.quizBonusButton.setOnClickListener { buyBonusAttempt() }

        buildPips(MAX_DAILY_QUIZ_ATTEMPTS)
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

        mainViewModel.quizAllowance.observe(viewLifecycleOwner) { renderAllowance(it) }

        // Timer will be updated in the timerRunnable
    }

    /**
     * One pip per attempt in the allowance, sized by weight so the row fills
     * the card whatever the cap happens to be. Built here rather than in XML
     * so a change to MAX_DAILY_QUIZ_ATTEMPTS cannot leave a stale count.
     *
     * [count] is the allowance rather than the constant, because an attempt
     * bought with an ad widens it: the row grows to thirteen and shrinks back
     * at the rollover. Rebuilt only when the number changes - the snapshot
     * fires on every points or XP change too, and re-inflating the row each
     * time would flicker it.
     */
    private fun buildPips(count: Int) {
        if (pipCount == count) return
        pipCount = count

        val row = binding.quizPips
        val gap = resources.getDimensionPixelSize(R.dimen.game_pip_gap)
        row.removeAllViews()

        repeat(count) { index ->
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

    private fun renderAllowance(allowance: MainViewModel.Allowance) {
        val used = allowance.used

        buildPips(allowance.allowance)

        binding.tvQuizzesLeft.text = when {
            allowance.remaining > 0 ->
                getString(R.string.quizzes_attempts_left, allowance.remaining, allowance.allowance)

            // "Back tomorrow" stops being true while the pill is on screen.
            allowance.canBuyMore -> getString(R.string.quizzes_attempts_spent_buyable)

            // True as written: games are a separate counter on the same day
            // stamp, so a spent quiz allowance leaves them untouched.
            else -> getString(R.string.quizzes_attempts_spent)
        }

        binding.quizBonusRow.visibility =
            if (allowance.canBuyMore) View.VISIBLE else View.GONE
        binding.quizBonusNote.text = getString(
            R.string.bonus_attempt_remaining,
            MAX_DAILY_BONUS_ATTEMPTS - allowance.bonusBought
        )
        refreshBonusButtonState()

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
        if (mainViewModel.quizAllowanceNow().remaining <= 0) {
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

    /**
     * Greys the pill while an ad is unavailable or a grant is in flight. It
     * stays VISIBLE either way - it disappears only at the daily cap, so a
     * momentary fill gap does not flicker the offer in and out of the card.
     */
    private fun refreshBonusButtonState() {
        val binding = _binding ?: return
        val ready = !bonusInFlight && AdManager.getInstance().isRewardedAdReady()
        binding.quizBonusButton.isEnabled = ready
        binding.quizBonusButton.alpha = if (ready) 1f else 0.5f
    }

    /**
     * Watch an ad, then buy one attempt.
     *
     * The grant fires on the REWARD callback rather than on dismissal: both
     * arrive on a normal completion, but the reward comes first, so claiming
     * there shrinks the window in which a killed process loses an ad the user
     * actually sat through. [bonusInFlight] stops the pair of callbacks
     * buying two attempts for one ad.
     */
    private fun buyBonusAttempt() {
        if (bonusInFlight) return

        if (!AdManager.getInstance().isRewardedAdReady()) {
            toastBonus(R.string.bonus_attempt_ad_unavailable)
            AdManager.getInstance().loadRewardedAd(requireContext())
            return
        }

        bonusInFlight = true
        binding.quizBonusLabel.setText(R.string.bonus_attempt_loading)
        refreshBonusButtonState()

        var claimed = false
        AdManager.getInstance().showRewardedAd(
            activity = requireActivity(),
            onRewarded = {
                if (!claimed) {
                    claimed = true
                    submitBonusAttempt()
                }
            },
            // Dismissal without a reward means the ad was closed early: there
            // is nothing to buy and nothing to apologise for.
            onAdClosed = { if (!claimed) endBonusAttempt() },
            onAdFailedToShow = {
                if (!claimed) {
                    toastBonus(R.string.bonus_attempt_ad_unavailable)
                    endBonusAttempt()
                }
            }
        )
    }

    private fun submitBonusAttempt() {
        // The fragment's own scope: if the user leaves, this stops caring.
        // The grant still lands, and the snapshot listener brings it back.
        viewLifecycleOwner.lifecycleScope.launch {
            when (mainViewModel.buyBonusAttempt(UserRepository.BonusActivity.QUIZ)) {
                is UserRepository.BonusAttemptResult.Granted ->
                    toastBonus(R.string.bonus_attempt_added_quiz)

                UserRepository.BonusAttemptResult.AtCap ->
                    toastBonus(R.string.bonus_attempt_at_cap)

                is UserRepository.BonusAttemptResult.Error ->
                    toastBonus(R.string.bonus_attempt_failed)
            }
            // The card is repainted by the snapshot listener, which is the
            // only thing that knows what the server actually stored.
            endBonusAttempt()
        }
    }

    private fun endBonusAttempt() {
        bonusInFlight = false
        _binding?.quizBonusLabel?.setText(R.string.bonus_attempt_action)
        refreshBonusButtonState()
    }

    private fun toastBonus(resId: Int) {
        if (isAdded) Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
