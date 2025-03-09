package com.pixelpayout.ui.main

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var userPreferences: UserPreferences
    private val quizViewModel: QuizListViewModel by viewModels()
    private lateinit var referralViewModel: ReferralViewModel

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(UserRepository())
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userPreferences = UserPreferences(this)
        Log.d("ReferralDebug", "Initializing ReferralViewModel...") // ✅ Add log before initialization
        lifecycleScope.launch {
            delay(1000)  // ✅ Delay ViewModel initialization
            referralViewModel = ReferralViewModel(UserRepository())
            checkAndShowReferralPopup()
        }

        setupToolbar()
        setupNavigation()
        observeViewModel()
        loadQuizzes()

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
            binding.customToolbar.pointsHeader.pointsText.text =
                getString(R.string.points_value, points)
        }
    }

    private fun loadQuizzes(forceRefresh: Boolean = false) {
        quizViewModel.loadQuizzes(forceRefresh)
    }

    private fun checkAndShowReferralPopup() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        FirebaseFirestore.getInstance().collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val hasUsedReferral = document.getBoolean("hasUsedReferral") ?: false  // ✅ Default to false
                    Log.d("ReferralDebug", "Firebase hasUsedReferral: $hasUsedReferral")
                    lifecycleScope.launch {
                        val hasSeenPopup = userPreferences.hasSeenReferralPopup.firstOrNull() ?: false  // ✅ Use firstOrNull() to avoid crashes
                        Log.d("ReferralDebug", "DataStore hasSeenPopup: $hasSeenPopup")

                        if (!hasUsedReferral && !hasSeenPopup) {  // ✅ Now both !values are non-null
                            showReferralPopup()
                            userPreferences.setHasSeenReferralPopup(true)
                        } else {
                            Log.d("ReferralDebug", "❌ Popup conditions not met")
                        }
                    }
                } else {
                    Log.d("ReferralDebug", "❌ Firebase document does not exist")
                }
            }
            .addOnFailureListener { e ->
                Log.e("ReferralDebug", "❌ Firebase fetch error: ${e.message}")
            }
    }

    private fun showReferralPopup() {
        val dialog = ReferralDialogFragment()
        dialog.show(supportFragmentManager, "ReferralDialog")

    }

}