package com.example.pixelpayout.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val auth: FirebaseAuth

    init {
        FirebaseApp.initializeApp(application)
        auth = FirebaseAuth.getInstance()
    }

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }
}
