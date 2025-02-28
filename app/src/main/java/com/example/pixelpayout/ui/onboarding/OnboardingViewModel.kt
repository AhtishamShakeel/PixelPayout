package com.pixelpayout.ui.onboarding

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth

class OnboardingViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()

    fun isUserLoggedIn(): Boolean {
        return auth.currentUser != null
    }
}