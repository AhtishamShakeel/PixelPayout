package com.example.pixelpayout.ui.home

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentHomeBinding
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.quiz.QuizListViewModel
import android.os.Handler
import android.os.Looper
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
                binding.nextTierText.text =
                    getString(R.string.next_tier, next.pointsShort, next.title)
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

        val remainingMs = buff.expiresAtMillis - System.currentTimeMillis()
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
        val remaining = if (minutes >= 60) {
            "${TimeUnit.MILLISECONDS.toHours(remainingMs)}h"
        } else if (minutes >= 1) {
            "${minutes}m"
        } else {
            "${TimeUnit.MILLISECONDS.toSeconds(remainingMs)}s"
        }

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

    private fun updateQuizStatusText() {
        val attempts = quizViewModel.dailyAttempts.value ?: 0
        val maxAttempts = quizViewModel.MAX_DAILY_ATTEMPTS
        val remaining = maxOf(maxAttempts - attempts, 0)
        
        if (remaining > 0) {
            // Show remaining quizzes
            binding.quizRow.rowSubtitle.text = "$remaining quizzes left"
        
            // Show reset timer if no quizzes left
            val nextResetTime = quizViewModel.nextResetTime.value
            if (nextResetTime != null) {
                val currentTime = quizViewModel.getCurrentServerTime()
                val timeUntilReset = nextResetTime - currentTime
                
                if (timeUntilReset <= 0) {
                    binding.quizRow.rowSubtitle.text = "Resetting soon..."
                    /*// Refresh attempts when timer reaches zero
                    quizViewModel.fetchDailyAttempts(forceRefresh = true)*/
                } else {
                    val hours = TimeUnit.MILLISECONDS.toHours(timeUntilReset)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeUntilReset) % 60
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(timeUntilReset) % 60
                    val timerText = when {
                        hours > 0 -> "${hours}h ${minutes}m left for reset"
                        minutes > 0 -> "${minutes}m ${seconds}s left for reset"
                        else -> "${seconds}s left for reset"
                    }
                    
                    binding.quizRow.rowSubtitle.text = timerText
                }
            } else {
                binding.quizRow.rowSubtitle.text = "Quizzes available soon"
            }
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            // Game and quiz are the same row component with different content,
            // so their static parts are filled in here rather than in XML.
            gameRow.rowIcon.setImageResource(R.drawable.ic_game)
            gameRow.rowTitle.setText(R.string.game_title)
            gameRow.rowSubtitle.setText(R.string.game_reward_rate)
            gameRow.root.setOnClickListener { navigateToGame() }

            quizRow.rowIcon.setImageResource(R.drawable.ic_quiz)
            quizRow.rowTitle.setText(R.string.quiz_title)
            quizRow.root.setOnClickListener { navigateToQuizzes() }

            btnPayout.setOnClickListener { navigateToRedemption() }
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
                

            findNavController().navigate(R.id.navigation_quizzes, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to quizzes: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_quizzes
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