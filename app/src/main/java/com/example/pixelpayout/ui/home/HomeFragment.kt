package com.pixelpayout.ui.home

import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.pixelpayout.R
import com.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.FragmentHomeBinding
import com.pixelpayout.ui.main.MainActivity
import com.pixelpayout.ui.main.MainViewModel
import com.pixelpayout.ui.quiz.QuizListViewModel
import kotlinx.coroutines.launch
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
        mainViewModel.points.observe(viewLifecycleOwner){ points -> 
            binding.totalPoints.text = "Total Stars: $points"
        }
        
        // Observe quiz attempts
        quizViewModel.dailyAttempts.observe(viewLifecycleOwner) { attempts ->
            updateQuizStatusText()
        }
        
        // Observe next reset time to update timer when needed
        quizViewModel.nextResetTime.observe(viewLifecycleOwner) { nextResetTime ->
            updateQuizStatusText()
        }
    }
    
    private fun updateQuizStatusText() {
        val attempts = quizViewModel.dailyAttempts.value ?: 0
        val maxAttempts = quizViewModel.MAX_DAILY_ATTEMPTS
        val remaining = maxOf(maxAttempts - attempts, 0)
        
        if (remaining > 0) {
            // Show remaining quizzes
            binding.quizStatus.text = "$remaining quizzes left"
        } else {
            // Show reset timer if no quizzes left
            val nextResetTime = quizViewModel.nextResetTime.value
            if (nextResetTime != null) {
                val currentTime = System.currentTimeMillis()
                val timeUntilReset = nextResetTime - currentTime
                
                if (timeUntilReset <= 0) {
                    binding.quizStatus.text = "Resetting soon..."
                    // Refresh attempts when timer reaches zero
                    quizViewModel.fetchDailyAttempts(forceRefresh = true)
                } else {
                    // Format the time remaining
                    val hours = TimeUnit.MILLISECONDS.toHours(timeUntilReset)
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(timeUntilReset) % 60
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(timeUntilReset) % 60
                    
                    // Different formats based on time remaining
                    val timerText = when {
                        hours > 0 -> "${hours}h ${minutes}m left for reset"
                        minutes > 0 -> "${minutes}m ${seconds}s left for reset"
                        else -> "${seconds}s left for reset"
                    }
                    
                    binding.quizStatus.text = timerText
                }
            } else {
                binding.quizStatus.text = "Quizzes available soon"
            }
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            // Quiz card clicks - direct navigation without animation
            quizCard.setOnClickListener {
                navigateToQuizzes()
            }

            // Game card - direct navigation without animation
            gameCard.setOnClickListener {
                navigateToGame()
            }

            // Keep the existing button click handlers but without animations
            playGameButton.setOnClickListener {
                navigateToGame()
            }
            
            gameImage.setOnClickListener {
                navigateToGame()
            }

            gameDetails.setOnClickListener {
                navigateToDetails("game")
            }

            btnPayout.setOnClickListener {
                navigateToRedemption()
            }
        }
    }

    private fun navigateToQuizzes() {
        try {
            // Create nav options with bottom-to-top animation
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                
            // Navigate with animations
            findNavController().navigate(R.id.navigation_quizzes, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to quizzes: ${e.message}")
            // Fallback to direct tab selection
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_quizzes
        }
    }

    private fun navigateToGame() {
        try {
            // Create nav options with bottom-to-top animation
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                
            // Navigate with animations
            findNavController().navigate(R.id.navigation_play, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to game: ${e.message}")
            // Fallback to direct tab selection
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    private fun navigateToDetails(type: String) {
        try {
            // Use directions for safe args with animation
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
            // Create nav options with bottom-to-top animation
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                
            // Navigate with animations
            findNavController().navigate(R.id.navigation_redemption, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to redemption: ${e.message}")
            // Fallback to direct tab selection
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_redemption
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 