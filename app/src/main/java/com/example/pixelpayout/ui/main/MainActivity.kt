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
import com.pixelpayout.ui.quiz.QuizListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var userPreferences: UserPreferences
    private val quizViewModel: QuizListViewModel by viewModels()
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(UserRepository())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        Log.d("ReferralDebug", "✅ MainActivity started")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userPreferences = UserPreferences(this)

        lifecycleScope.launch {
            delay(2000)  // ✅ Give Firebase time to load data
            Log.d("ReferralDebug", "🔍 Calling checkAndShowReferralPopup()")  // 🔍 Confirm function is called
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

        // Make points clickable
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
        AlertDialog.Builder(this)
            .setTitle("Enter Referral Code")
            .setMessage("Do you have a referral code? Enter it now to get rewards!")
            .setPositiveButton("Enter Code") { _, _ ->
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}