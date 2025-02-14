package com.pixelpayout.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import com.pixelpayout.data.repository.UserRepository

class SignupViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val userRepository = UserRepository()

    private val _signupState = MutableLiveData<SignupState>()
    val signupState: LiveData<SignupState> = _signupState

    private var signupJob: Job? = null

    fun signup(name: String, email: String, password: String) {
        // Cancel any existing signup attempt
        signupJob?.cancel()

        signupJob = viewModelScope.launch {
            try {
                _signupState.value = SignupState.Loading

                // Add timeout handling
                withTimeout(10000) { // 10 seconds timeout
                    // Create authentication account
                    val result = auth.createUserWithEmailAndPassword(email, password).await()

                    // Create user document in Firestore
                    result.user?.let { user ->
                        val userData = hashMapOf(
                            "displayName" to name,
                            "email" to email,
                            "points" to 0,
                            "joinedDate" to com.google.firebase.Timestamp.now(),
                            "lastActive" to com.google.firebase.Timestamp.now(),
                            "referralCode" to userRepository.generateReferralCode(),
                            "hasUsedReferral" to false
                        )

                        firestore.collection("users")
                            .document(user.uid)
                            .set(userData)
                            .await()

                        _signupState.value = SignupState.Success
                    } ?: throw Exception("User creation failed")
                }
            } catch (e: TimeoutCancellationException) {
                _signupState.value = SignupState.Error(
                    message = "Request timed out. Please try again.",
                    field = null
                )
            } catch (e: Exception) {
                val (message, field) = when {
                    e.message?.contains("email", ignoreCase = true) == true ->
                        Pair("Email is already in use", SignupField.EMAIL)
                    e.message?.contains("password", ignoreCase = true) == true ->
                        Pair("Password is too weak", SignupField.PASSWORD)
                    e.message?.contains("network", ignoreCase = true) == true ->
                        Pair("Network error. Please check your connection.", null)
                    else -> Pair("Signup failed: ${e.message}", null)
                }
                _signupState.value = SignupState.Error(message, field)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        signupJob?.cancel()
    }
}

sealed class SignupState {
    object Initial : SignupState()
    object Loading : SignupState()
    object Success : SignupState()
    data class Error(val message: String, val field: SignupField?) : SignupState()
}

enum class SignupField {
    NAME, EMAIL, PASSWORD
} 