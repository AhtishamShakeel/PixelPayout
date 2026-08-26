package com.example.pixelpayout.ui.auth

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.utils.UserPreferences
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

    private val _signupState = MutableLiveData<SignupState>()
    val signupState: LiveData<SignupState> = _signupState

    private var signupJob: Job? = null




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

    fun login(email: String, password: String, context: Context) {
        loginJob?.cancel()

        loginJob = viewModelScope.launch {
            try {
                _loginState.value = LoginState.Loading

                withTimeout(10000) {
                    val result = auth.signInWithEmailAndPassword(email, password).await()
                    val user = result.user ?: throw Exception("Login failed")

                    val userDoc = firestore.collection("users").document(user.uid).get().await()
                    val username = userDoc.getString("displayName") ?: "User"

                    val userPreferences = UserPreferences(context)
                    userPreferences.setUsername(username)

                    _loginState.value = LoginState.Success
                }
            } catch (e: TimeoutCancellationException) {
                _loginState.value = LoginState.Error("Request timed out. Please try again.")
            } catch (e: Exception) {
                _loginState.value = LoginState.Error("Login failed: ${e.message}")
            }
        }
    }

    fun signup(name: String, email: String, password: String, androidId: String, context: Context){
        signupJob?.cancel()

        signupJob = viewModelScope.launch {
            try {
                _signupState.value = SignupState.Loading

                withTimeout(15000){
                    val result = auth.createUserWithEmailAndPassword(email,password).await()

                    result.user?.let { _ ->
                        // The user document is created server-side: the client
                        // is not allowed to write points/xp/level, and the
                        // device check and referral code need reads across
                        // users that only the server can do.
                        completeSignup(name, androidId)

                        val userPreferences = UserPreferences(context)
                        userPreferences.setHasSeenReferralPopup(false)
                        userPreferences.setUsername(name)


                        _signupState.value = SignupState.Success
                    } ?: throw Exception("User creation failed")
                }
            } catch (e: TimeoutCancellationException) {
                auth.currentUser?.delete()
                _signupState.value = SignupState.Error(
                    message = "Request time out. Please try again.",
                    field = null
                )
            } catch (e: Exception) {
                val (message,field) = when{
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
        loginJob?.cancel()
        signupJob?.cancel()
    }


    /**
     * Calls the server to create this account's user document. Idempotent, so
     * it is safe on both a first sign-up and a repeat sign-in.
     */
    private suspend fun completeSignup(displayName: String, androidId: String) {
        functions.getHttpsCallable("completeSignup")
            .call(
                hashMapOf(
                    "displayName" to displayName,
                    "androidId" to androidId.ifEmpty { "UNKNOWN_ANDROID_ID" }
                )
            )
            .await()
    }

    // for Google. The server takes uid and email from the auth token, so they
    // are no longer passed from here.
    fun checkIfUserExists(
        displayName: String,
        androidId: String,
        context: Context,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        // completeSignup handles both cases, so there is no longer a separate
        // "does this user exist?" read here - which also means the client no
        // longer needs read access across the users collection.
        viewModelScope.launch {
            try {
                completeSignup(displayName, androidId)

                val userPreferences = UserPreferences(context)
                userPreferences.setUsername(displayName)
                userPreferences.setHasSeenReferralPopup(false)

                onSuccess()
            } catch (e: Exception) {
                Log.e("Firestore", "Error completing signup: ${e.message}")
                onFailure("Failed to create account. Try again.")
            }
        }
    }

    sealed class LoginState {
        object Initial : LoginState()
        object Loading : LoginState()
        object Success : LoginState()
        data class Error(val message: String) : LoginState()
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


}
