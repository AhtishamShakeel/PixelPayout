package com.example.pixelpayout.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentHomeBinding
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.example.pixelpayout.ui.play.PlayFragment
import com.example.pixelpayout.ui.quiz.QuizListViewModel
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val quizViewModel: QuizListViewModel by activityViewModels()
    
    // Timer handler for countdown if needed
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateQuizStatusText()
            updateBuffBadge()
            timerHandler.postDelayed(this, 1000) // Update every second
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()

    }
    
    override fun onResume() {
        super.onResume()
        // Start the timer to update the quiz status if needed
        timerHandler.post(timerRunnable)
    }
    
    override fun onPause() {
        super.onPause()
        // Stop the timer when fragment is paused
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun observeViewModel(){
        mainViewModel.points.observe(viewLifecycleOwner) { points ->
            binding.totalPoints.text = points.toString()
        }

        // Fills toward the cheapest redemption not yet affordable. Null means
        // there is no such target, so the bar is hidden rather than shown full.
        mainViewModel.nextRedemption.observe(viewLifecycleOwner) { next ->
            if (next == null) {
                binding.nextTierGroup.visibility = View.GONE
            } else {
                binding.nextTierGroup.visibility = View.VISIBLE
                binding.redemptionProgress.progress = next.percent
                binding.balanceTarget.text =
                    getString(R.string.balance_target, next.pointsCost)
                // Derived rather than read from points separately: taking both
                // halves of the ratio from the same snapshot stops the bar and
                // the numbers under it disagreeing mid-update.
                binding.balanceRatio.text = getString(
                    R.string.balance_ratio,
                    next.pointsCost - next.pointsShort,
                    next.pointsCost
                )
                binding.nextTierText.text =
                    getString(R.string.next_tier, next.pointsShort, next.title)
            }
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.levelChip.text = progress.level.toString()
            binding.levelTitle.text = getString(R.string.level_card_title, progress.level)

            when {
                progress.isMaxLevel -> {
                    // No next level to fill toward. A full bar says "nothing
                    // left to earn here", which is the truth; an empty one
                    // would read as no progress at all.
                    binding.levelProgressBar.progress = 100
                    binding.levelXpRatio.setText(R.string.level_xp_max)
                    binding.levelFooter.setText(R.string.level_reached_max)
                }

                // The curve is fetched once per session and can still be in
                // flight, or have failed. Level is known either way; the XP
                // figures are not, so they are left blank rather than shown
                // as 0 / 0.
                progress.xpForNextLevel <= 0 -> {
                    binding.levelProgressBar.progress = 0
                    binding.levelXpRatio.text = ""
                    binding.levelFooter.text = ""
                }

                else -> {
                    binding.levelProgressBar.progress =
                        (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
                    binding.levelXpRatio.text = getString(
                        R.string.level_xp_ratio,
                        progress.xpIntoLevel,
                        progress.xpForNextLevel
                    )
                    binding.levelFooter.text = getString(
                        R.string.level_to_next,
                        progress.xpForNextLevel - progress.xpIntoLevel,
                        progress.level + 1
                    )
                }
            }
        }

        // The badge only appears while a buff is running; the countdown is
        // driven by the existing per-second timer below.
        mainViewModel.activeBuff.observe(viewLifecycleOwner) { updateBuffBadge() }

        // Observe quiz attempts
        quizViewModel.dailyAttempts.observe(viewLifecycleOwner) { attempts ->
            updateQuizStatusText()
        }
        
        // Observe next reset time to update timer when needed
        quizViewModel.nextResetTime.observe(viewLifecycleOwner) { nextResetTime ->
            updateQuizStatusText()
        }
    }
    
    private fun updateBuffBadge() {
        val binding = _binding ?: return
        val buff = mainViewModel.activeBuff.value

        if (buff == null || !buff.isActive()) {
            binding.buffCard.visibility = View.GONE
            return
        }

        // HH:MM:SS rather than the previous single largest unit. The card is
        // now built around the clock, and "1h" sitting at 24sp reads as a
        // static label rather than something counting down.
        val remainingMs = (buff.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val remaining = String.format(
            Locale.US,
            "%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(remainingMs),
            TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60,
            TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        )

        // Multipliers are whole-ish; drop a trailing .0 so it reads "2x".
        val multiplier = if (buff.multiplier % 1.0 == 0.0) {
            buff.multiplier.toInt().toString()
        } else {
            buff.multiplier.toString()
        }

        binding.buffText.text = getString(R.string.buff_label, multiplier)
        binding.buffTimer.text = remaining
        binding.buffCard.visibility = View.VISIBLE
    }

    /**
     * The quiz tile's subtitle, refreshed once a second.
     *
     * The condition here used to be inverted: the reset countdown was computed
     * inside the `remaining > 0` branch and overwrote the attempts text, so
     * "3 quizzes left" was written and then immediately replaced on the same
     * pass and never actually appeared. Meanwhile the branch where the user
     * has NO attempts left - the one case the countdown is for, and the one
     * the original comment described - set no text at all, leaving the
     * subtitle empty. On a slim row that read as a blank line; on the tile it
     * reads as a rendering fault.
     *
     * So: attempts remaining is what a player needs while they can still
     * play, and the countdown is what they need once they cannot.
     */
    private fun updateQuizStatusText() {
        val attempts = quizViewModel.dailyAttempts.value ?: 0
        val remaining = maxOf(quizViewModel.MAX_DAILY_ATTEMPTS - attempts, 0)

        binding.quizTileSubtitle.text = if (remaining > 0) {
            resources.getQuantityString(R.plurals.quizzes_left, remaining, remaining)
        } else {
            resetCountdownText()
        }
    }

    /**
     * Time until the daily quiz allowance resets, measured against the
     * server's clock rather than the device's - changing the phone's date
     * cannot buy extra attempts.
     */
    private fun resetCountdownText(): String {
        val nextResetTime = quizViewModel.nextResetTime.value
            ?: return getString(R.string.quiz_reset_unknown)

        val remainingMs = nextResetTime - quizViewModel.getCurrentServerTime()
        if (remainingMs <= 0) return getString(R.string.quiz_resetting)

        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60

        return when {
            hours > 0 -> getString(R.string.quiz_reset_in_hm, hours, minutes)
            minutes > 0 -> getString(R.string.quiz_reset_in_ms, minutes, seconds)
            else -> getString(R.string.quiz_reset_in_s, seconds)
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            // The tiles are fully described in XML now - only the quiz
            // subtitle is dynamic, and the per-second timer owns that.
            playTile.setOnClickListener { navigateToGame() }
            quizTile.setOnClickListener { navigateToQuizzes() }

            earnAction.setOnClickListener { navigateToRewards() }
            // Both the card and its button land on Earn; tapping the card body
            // is what most people try first.
            offerCard.setOnClickListener { navigateToRewards() }
            btnOffer.setOnClickListener { navigateToRewards() }
            referAction.setOnClickListener { showReferralDialog() }

            btnPayout.setOnClickListener { navigateToRedemption()}
        }
    }

    private fun navigateToQuizzes() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                

            // Quizzes is a tab inside Play now, so land there rather than on
            // a destination of its own.
            val args = bundleOf(PlayFragment.ARG_START_TAB to PlayFragment.TAB_QUIZZES)
            findNavController().navigate(R.id.navigation_play, args, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to quizzes: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    private fun navigateToGame() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()

            findNavController().navigate(R.id.navigation_play, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to game: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    private fun navigateToRewards() {
        try {
            findNavController().navigate(R.id.navigation_rewards, null, defaultNavOptions())
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to rewards: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_rewards
        }
    }

    /**
     * Referral has no destination of its own - it is the same dialog the
     * activity shows on first run, reached here on demand.
     */
    private fun showReferralDialog() {
        ReferralDialogFragment().show(parentFragmentManager, "ReferralDialog")
    }

    private fun defaultNavOptions() = NavOptions.Builder()
        .setEnterAnim(R.anim.fade_in)
        .setExitAnim(R.anim.fade_out)
        .setPopEnterAnim(R.anim.fade_in)
        .setPopExitAnim(R.anim.fade_out)
        .build()

    private fun navigateToDetails(type: String) {
        try {
            val action = HomeFragmentDirections.actionHomeToDetails(type)
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                
            findNavController().navigate(action, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to details: ${e.message}")
        }
    }

    private fun navigateToRedemption() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()

            findNavController().navigate(R.id.navigation_redemption, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to redemption: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_redemption
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 