package com.pixelpayout.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SignupViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _signupResult = MutableLiveData<Result<Unit>>()
    val signupResult: LiveData<Result<Unit>> = _signupResult

    fun signup(name: String, email: String, password: String) {
        viewModelScope.launch {
            try {
                // Create authentication account
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                
                // Create user document in Firestore
                result.user?.let { user ->
                    val userData = hashMapOf(
                        "displayName" to name,
                        "email" to email,
                        "points" to 0,
                        "joinedDate" to com.google.firebase.Timestamp.now(),
                        "lastActive" to com.google.firebase.Timestamp.now()
                    )
                    
                    firestore.collection("users")
                        .document(user.uid)
                        .set(userData)
                        .await()
                    
                    _signupResult.value = Result.success(Unit)
                } ?: throw Exception("User creation failed")
                
            } catch (e: Exception) {
                _signupResult.value = Result.failure(e)
            }
        }
    }
} 