package com.pixelpayout.ui.home

import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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
            // Quiz card clicks with touch feedback
            quizCard.apply {
                setOnClickListener {
                    // Apply animation to the card
                    val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                    startAnimation(scaleDown)
                    
                    // Navigate with a small delay for smoother transition
                    Handler(Looper.getMainLooper()).postDelayed({
                        navigateToQuizzes()
                    }, 150) // 150ms delay feels responsive yet gives time for animation
                }
                
                // Add touch listener for better feedback
                setOnTouchListener { view, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            // Scale down when pressed
                            val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                            startAnimation(scaleDown)
                            false
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            // Scale back up when released or canceled
                            val scaleUp = AnimationUtils.loadAnimation(context, R.anim.scale_up)
                            startAnimation(scaleUp)
                            false
                        }
                        else -> false
                    }
                }
            }

            // Game card with improved animations
            gameCard.apply {
                setOnClickListener {
                    // Apply animation to the card
                    val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                    startAnimation(scaleDown)
                    
                    // Navigate with a small delay for smoother transition
                    Handler(Looper.getMainLooper()).postDelayed({
                        navigateToGame()
                    }, 150)
                }
                
                // Add touch listener for better feedback
                setOnTouchListener { view, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                            startAnimation(scaleDown)
                            false
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val scaleUp = AnimationUtils.loadAnimation(context, R.anim.scale_up)
                            startAnimation(scaleUp)
                            false
                        }
                        else -> false
                    }
                }
            }

            // Keep the existing button click handlers but add animations
            playGameButton.setOnClickListener {
                val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                gameCard.startAnimation(scaleDown)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    navigateToGame()
                }, 150)
            }
            
            gameImage.setOnClickListener {
                val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                gameCard.startAnimation(scaleDown)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    navigateToGame()
                }, 150)
            }

            gameDetails.setOnClickListener {
                val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                gameCard.startAnimation(scaleDown)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    navigateToDetails("game")
                }, 150)
            }

            btnPayout.setOnClickListener {
                val scaleDown = AnimationUtils.loadAnimation(context, R.anim.scale_down)
                btnPayout.startAnimation(scaleDown)
                
                Handler(Looper.getMainLooper()).postDelayed({
                    navigateToRedemption()
                }, 150)
            }
        }
    }

    private fun navigateToQuizzes() {
        // Add animation for smoother tab transition
        val mainActivity = activity as? MainActivity
        
        // Apply custom animations - determine direction based on current and target tabs
        val currentTabId = mainActivity?.binding?.bottomNav?.selectedItemId ?: 0
        val targetTabId = R.id.navigation_quizzes
        
        applyNavigationAnimation(currentTabId, targetTabId)
        
        // Then change the selected tab
        mainActivity?.binding?.bottomNav?.selectedItemId = targetTabId
    }

    private fun navigateToGame() {
        // Add animation for smoother tab transition
        val mainActivity = activity as? MainActivity
        
        // Apply custom animations - determine direction based on current and target tabs
        val currentTabId = mainActivity?.binding?.bottomNav?.selectedItemId ?: 0
        val targetTabId = R.id.navigation_play
        
        applyNavigationAnimation(currentTabId, targetTabId)
        
        // Then change the selected tab
        mainActivity?.binding?.bottomNav?.selectedItemId = targetTabId
    }

    private fun navigateToDetails(type: String) {
        findNavController().navigate(
            HomeFragmentDirections.actionHomeToDetails(type)
        )
    }

    private fun navigateToRedemption() {
        // Add animation for smoother tab transition
        val mainActivity = activity as? MainActivity
        
        // Apply custom animations - determine direction based on current and target tabs
        val currentTabId = mainActivity?.binding?.bottomNav?.selectedItemId ?: 0
        val targetTabId = R.id.navigation_redemption
        
        applyNavigationAnimation(currentTabId, targetTabId)
        
        // Then change the selected tab
        mainActivity?.binding?.bottomNav?.selectedItemId = targetTabId
    }

    private fun applyNavigationAnimation(currentTabId: Int, targetTabId: Int) {
        // Determine if we're moving left or right in the bottom nav
        val fragmentTransaction = parentFragmentManager.beginTransaction()
        
        if (currentTabId < targetTabId) {
            // Moving right
            fragmentTransaction.setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left
            )
        } else {
            // Moving left
            fragmentTransaction.setCustomAnimations(
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
        }
        
        fragmentTransaction.commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 