package com.example.pixelpayout.ui.auth

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.tasks.await
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class AuthViewModel : ViewModel() {

    private val functions:FirebaseFunctions = Firebase.functions

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()


    private val _emailExists = MutableLiveData<Boolean?>()
    val emailExists: LiveData<Boolean?> =_emailExists

    private val _loginState = MutableLiveData<LoginState>()
    val loginState: LiveData<LoginState> = _loginState

    private var loginJob: Job? = null


    fun checkIfEmailExists(email: String){
        val data = hashMapOf("email" to email)
        functions
            .getHttpsCallable("checkEmailExists")
            .call(data)
            .addOnCompleteListener { task ->
                if(task.isSuccessful){
                    val result = task.result?.data as? Map<*, *>
                    val exists = result?.get("exists") as? Boolean ?: false
                    _emailExists.value = exists
                } else {
                    _emailExists.value = false
                }
            }
    }

    fun login(email: String, password: String){
        loginJob?.cancel()

        loginJob = viewModelScope.launch {
            try {
                _loginState.value = LoginState.Loading

                withTimeout(10000){
                    auth.signInWithEmailAndPassword(email,password).await()
                    _loginState.value = LoginState.Success
                }
            } catch (e: TimeoutCancellationException){
                _loginState.value = LoginState.Error("Request timed out. Please try again.")
            } catch (e: Exception){
                val errorMessage = when {
                    e.message?.contains("password", ignoreCase = true) == true ->
                        "Incorrect password"
                    else -> "Login failed: Incorrect Gmail or password:"
                }
                _loginState.value = LoginState.Error(errorMessage)
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        loginJob?.cancel()
    }

    fun checkIfUserExists(
        uid: String,
        displayName: String,
        email: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val userRef = firestore.collection("users").document(uid)

        userRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                Log.d("Firestore", "User already exists. Logging in...")
                onSuccess()
            } else {
                createNewUser(uid, displayName, email, onSuccess, onFailure)
            }
        }.addOnFailureListener {
            Log.e("Firestore", "Error checking user existence: ${it.message}")
            onFailure("Database error. Try again.")
        }
    }

    private fun loginUser(

    ){}



    private fun createNewUser(
        uid: String,
        displayName: String,
        email: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val userData = hashMapOf(
            "displayName" to displayName,
            "email" to email,
            "hasUsedReferral" to false,
            "joinedDate" to Timestamp.now(),
            "lastActive" to Timestamp.now(),
            "lastServerDate" to currentDate,
            "points" to 0,
            "quizAttempts" to 0,
            "referralCode" to generateReferralCode(),
            "referralRewardClaimed" to false,
            "referredByCode" to ""
        )

        firestore.collection("users").document(uid).set(userData)
            .addOnSuccessListener {
                Log.d("Firestore", "User created successfully!")
                onSuccess()
            }
            .addOnFailureListener {
                Log.e("Firestore", "Error saving user: ${it.message}")
                onFailure("Failed to create account. Try again.")
            }
    }

    private fun generateReferralCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        return (1..6).map { chars.random() }.joinToString("")
    }

    sealed class LoginState {
        object Initial : LoginState()
        object Loading : LoginState()
        object Success : LoginState()
        data class Error(val message: String) : LoginState()
    }


}
