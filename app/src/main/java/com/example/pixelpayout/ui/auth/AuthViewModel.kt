package com.example.pixelpayout.ui.auth

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AuthViewModel : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

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
}
