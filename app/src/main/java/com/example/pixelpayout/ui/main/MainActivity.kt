package com.example.pixelpayout.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.example.pixelpayout.utils.UserPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.R
import com.example.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.ActivityMainBinding
import com.example.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.example.pixelpayout.ui.quiz.QuizListViewModel
import com.example.pixelpayout.ui.redemption.ReferralViewModel
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.ui.dialogs.NoInternetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var userPreferences: UserPreferences
    private val quizViewModel: QuizListViewModel by viewModels()
    private lateinit var referralViewModel: ReferralViewModel
    private lateinit var connectivityCheck: AndroidConnectivityCheck
    private var noInternetDialog: NoInternetDialog? = null
    
    // Cache for Lottie compositions
    private val lottieCache = mutableMapOf<Int, LottieComposition>()

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(UserRepository())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userPreferences = UserPreferences(this)
        connectivityCheck = AndroidConnectivityCheck(this)

        setupConnectivityCheck()
        quizViewModel.loadCachedQuizzes(this)
        quizViewModel.refreshAttemptsIfNeeded()
        
        // Observe categories and preload animations efficiently
        quizViewModel.categories.observe(this) { categories ->
            lifecycleScope.launch(Dispatchers.IO) {
                categories.forEach { category ->
                    // Skip if already cached
                    if (lottieCache.containsKey(category.lottieAnimationResId)) {
                        return@forEach
                    }
                    
                    try {
                        // Load composition in background
                        val result = withContext(Dispatchers.Main) {
                            LottieCompositionFactory.fromRawRes(
                                this@MainActivity, 
                                category.lottieAnimationResId
                            ).addListener { composition ->
                                composition?.let {
                                    lottieCache[category.lottieAnimationResId] = it
                                    Log.d("LottiePreload", "Cached animation for category: ${category.lottieAnimationResId}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LottiePreload", "Failed to load animation: ${e.message}")
                    }
                }
                Log.d("LottiePreload", "Finished preloading ${categories.size} animations")
            }
        }

        Log.d("ReferralDebug", "Initializing ReferralViewModel...")
        lifecycleScope.launch {
            referralViewModel = ReferralViewModel(UserRepository())
            checkAndShowReferralPopup()
        }

        setupToolbar()
        setupNavigation()
        observeViewModel()
    }

    private fun setupConnectivityCheck() {
        lifecycleScope.launch {
            connectivityCheck.isConnected.collect { isConnected ->
                if (!isConnected) {
                    showNoInternetDialog()
                } else {
                    hideNoInternetDialog()
                }
            }
        }
    }

    private fun showNoInternetDialog() {
        if (noInternetDialog == null) {
            noInternetDialog = NoInternetDialog()
            noInternetDialog?.show(supportFragmentManager, NoInternetDialog.TAG)
        }
    }

    private fun hideNoInternetDialog() {
        noInternetDialog?.dismiss()
        noInternetDialog = null
    }

    // Function to get cached composition
    fun getCachedAnimation(resId: Int): LottieComposition? = lottieCache[resId]

    private fun setupToolbar() {
        setSupportActionBar(binding.customToolbar.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        lifecycleScope.launch {
            userPreferences.username.collect {username ->
                binding.customToolbar.usernameText.text = "Hey, ${username ?: "User"}"
            }
        }

        binding.customToolbar.pointsHeader.root.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.navigation_redemption
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Use the built-in setupWithNavController and customize it for better animations
        binding.bottomNav.setupWithNavController(navController)
        
        // Override the default behavior with custom animations
        binding.bottomNav.setOnItemSelectedListener { item ->
            // Store the current and target destination IDs
            val currentDestinationId = navController.currentDestination?.id ?: 0
            val targetDestinationId = item.itemId
            
            // Only navigate if we're actually changing destinations
            if (currentDestinationId != targetDestinationId) {
                // Check if we need to refresh quiz data when returning to home
                if (targetDestinationId == R.id.navigation_home) {
                    quizViewModel.refreshAttemptsIfNeeded()
                }
                
                // Create the navigation options with fade animations
                val navOptions = NavOptions.Builder()
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)
                    .build()
                
                try {
                    // Try to navigate using the safe approach
                    navController.navigate(targetDestinationId, null, navOptions)
                    return@setOnItemSelectedListener true
                } catch (e: Exception) {
                    // If navigation fails, at least select the tab in the UI
                    Log.e("Navigation", "Failed to navigate: ${e.message}")
                    // Return true to still update the selected item
                    return@setOnItemSelectedListener true
                }
            }
            
            // If we're already at the destination, just return true to handle the selection
            true
        }
    }

    private fun observeViewModel() {
        viewModel.points.observe(this) { points ->
            binding.customToolbar.pointsHeader.pointsText.text =
                getString(R.string.points_value, points)
        }
    }

    private fun checkAndShowReferralPopup() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        lifecycleScope.launch {
            val hasSeenPopup = userPreferences.hasSeenReferralPopup.firstOrNull() ?: false
            if (hasSeenPopup) {
                Log.d("ReferralDebug", "User has seen the popup. Skipping Firebase check")
                return@launch
            }
            userPreferences.setHasSeenReferralPopup(true)

            try{
                val document = FirebaseFirestore.getInstance().collection("users")
                    .document(user.uid)
                    .get()
                    .await()
                if (document.exists()){
                    val hasUsedReferral = document.getBoolean("hasUsedReferral") ?: false
                    Log.d("ReferralDebug", "Firebase hasUsedReferral: $hasUsedReferral")

                    if(!hasUsedReferral){
                        showReferralPopup()
                    } else {
                        Log.d("ReferralDebug", "User has already used a referral code.")
                    }
                } else {
                    Log.d("ReferralDebug", "User document does not exist in Firebase.")
                }
            } catch (e: Exception) {
                Log.e("ReferralDebug", "Error fetching Firebase data: ${e.message}")
            }
        }
    }

    private fun showReferralPopup() {
        val dialog = ReferralDialogFragment()
        dialog.show(supportFragmentManager, "ReferralDialog")
    }

    // Add this method to be called from other activities
    companion object {
        fun handleInternetDisconnection(activity: AppCompatActivity) {
            if (activity !is MainActivity) {
                activity.runOnUiThread {
                    try {
                        // Finish the current activity and return to MainActivity
                        activity.finish()
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    } catch (e: Exception) {
                        // Log but don't crash if there's an issue
                        Log.e("MainActivity", "Error handling internet disconnection: ${e.message}")
                    }
                }
            }
        }
    }
}