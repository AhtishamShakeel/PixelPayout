package com.pixelpayout.ui.main

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.pixelpayout.utils.UserPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.R
import com.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.ActivityMainBinding
import com.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.pixelpayout.ui.quiz.QuizListViewModel
import com.pixelpayout.ui.redemption.ReferralResult
import com.pixelpayout.ui.redemption.ReferralViewModel
import com.pixelpayout.ui.redemption.ReferralViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var userPreferences: UserPreferences
    private val quizViewModel: QuizListViewModel by viewModels()
    private lateinit var referralViewModel: ReferralViewModel
    private val userRepository = UserRepository.getInstance()
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userPreferences = UserPreferences(this)

        // Initialize ViewModel with factory
        viewModel = ViewModelProvider(this, MainViewModelFactory(userRepository))[MainViewModel::class.java]

        Log.d("ReferralDebug", "Initializing ReferralViewModel...")
        lifecycleScope.launch {
            delay(1000)
            referralViewModel = ReferralViewModel(userRepository)
            checkAndShowReferralPopup()
        }

        setupToolbar()
        setupNavigation()
        observeViewModel()
        loadQuizzes()
    }

    override fun onDestroy() {
        super.onDestroy()
        userRepository.cleanup()
    }

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

        binding.bottomNav.setupWithNavController(navController)
    }

    private fun observeViewModel() {
        viewModel.points.observe(this) { points ->
            Log.d("UIUpdate", "Points updated in UI: $points")
            binding.customToolbar.pointsHeader.pointsText.text =
                getString(R.string.points_value, points)
        }
    }

    private fun loadQuizzes(forceRefresh: Boolean = false) {
        quizViewModel.loadQuizzes(forceRefresh)
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
}