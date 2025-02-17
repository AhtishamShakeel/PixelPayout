package com.pixelpayout.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job

class LoginViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()

    private val _loginState = MutableLiveData<LoginState>()
    val loginState: LiveData<LoginState> = _loginState

    private var loginJob: Job? = null

    fun login(email: String, password: String) {
        // Cancel any existing login attempt
        loginJob?.cancel()

        loginJob = viewModelScope.launch {
            try {
                _loginState.value = LoginState.Loading

                // Add timeout handling
                withTimeout(10000) { // 10 seconds timeout
                    auth.signInWithEmailAndPassword(email, password).await()
                    _loginState.value = LoginState.Success
                }
            } catch (e: TimeoutCancellationException) {
                _loginState.value = LoginState.Error("Request timed out. Please try again.")
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("invalid email", ignoreCase = true) == true ->
                        "Invalid email address"
                    e.message?.contains("password", ignoreCase = true) == true ->
                        "Invalid password"
                    else -> "Login failed: Incorrect Gmail or password:"
                }
                _loginState.value = LoginState.Error(errorMessage)
            }
        }
    }

    fun isUserLoggedIn(): Boolean = auth.currentUser != null

    override fun onCleared() {
        super.onCleared()
        loginJob?.cancel()
    }
}

sealed class LoginState {
    object Initial : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
} 