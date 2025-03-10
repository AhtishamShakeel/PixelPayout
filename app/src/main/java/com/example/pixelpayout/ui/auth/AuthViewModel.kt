package com.example.pixelpayout.ui.auth

import android.content.Context
import android.util.Log
import android.provider.Settings.Secure
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
import com.pixelpayout.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout


class AuthViewModel : ViewModel() {

    private val functions:FirebaseFunctions = Firebase.functions

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val userRepository = UserRepository()




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

                    result.user?.let { user ->
                        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                        val existingUserQuery = firestore.collection("users")
                            .whereEqualTo("androidId", androidId)
                            .get()
                            .await()


                        val hasUsedReferral = existingUserQuery.documents.isNotEmpty()


                        val userData = hashMapOf(
                            "displayName" to name,
                            "email" to email,
                            "password" to password,
                            "androidId" to androidId,
                            "hasUsedReferral" to hasUsedReferral,
                            "joinedDate" to Timestamp.now(),
                            "lastActive" to Timestamp.now(),
                            "lastServerDate" to currentDate,
                            "points" to 0,
                            "quizAttempts" to 0,
                            "referralCode" to generateReferralCode(),
                            "referralRewardClaimed" to false,
                        )

                        firestore.collection("users")
                            .document(user.uid)
                            .set(userData)
                            .await()


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


    //for Google
    fun checkIfUserExists(
        uid: String,
        displayName: String,
        email: String,
        androidId: String,  // ✅ Ensure Android ID is always passed
        context: Context,  // ✅ Pass context for saving preferences
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val userRef = firestore.collection("users").document(uid)

        userRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                Log.d("Firestore", "User already exists. Logging in...")
                val userPreferences = UserPreferences(context)
                viewModelScope.launch {
                    userPreferences.setUsername(displayName)
                    userPreferences.setHasSeenReferralPopup(false)
                }

                onSuccess()
            } else {
                viewModelScope.launch {
                    createNewUser(uid, displayName, email, androidId, context, onSuccess, onFailure)
                }
            }
        }.addOnFailureListener {
            Log.e("Firestore", "Error checking user existence: ${it.message}")
            onFailure("Database error. Try again.")
        }
    }


    private suspend fun createNewUser(
        uid: String,
        displayName: String,
        email: String,
        androidId: String,
        context: Context,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val safeAndroidId = androidId.ifEmpty { "UNKNOWN_ANDROID_ID" }
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val existingUserQuery = firestore.collection("users")
            .whereEqualTo("androidId", safeAndroidId)
            .get()
            .await()

        val hasUsedReferral = existingUserQuery.documents.isNotEmpty()

        val userData = hashMapOf(
            "uid" to uid,
            "displayName" to displayName,
            "email" to email,
            "androidId" to safeAndroidId,
            "hasUsedReferral" to hasUsedReferral,
            "joinedDate" to Timestamp.now(),
            "lastActive" to Timestamp.now(),
            "lastServerDate" to currentDate,
            "points" to 0,
            "quizAttempts" to 0,
            "referralCode" to generateReferralCode(),
            "referralRewardClaimed" to false
        )

        firestore.collection("users").document(uid).set(userData)
            .addOnSuccessListener {
                Log.d("Firestore", "User created successfully!")
                val userPreferences = UserPreferences(context)
                viewModelScope.launch {
                    userPreferences.setUsername(displayName)
                    userPreferences.setHasSeenReferralPopup(false)
                }


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
