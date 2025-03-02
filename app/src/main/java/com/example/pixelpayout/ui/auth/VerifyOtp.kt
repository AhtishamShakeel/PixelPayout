package com.example.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pixelpayout.databinding.ActivityVerifyOtpBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import com.pixelpayout.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.*

class VerifyOtp : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyOtpBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var verificationId: String
    private val firestore = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Get verification ID and phone number from Intent
        verificationId = intent.getStringExtra("verificationId").toString()
        val phoneNumber = intent.getStringExtra("phoneNumber")

        binding.textMobile.text = phoneNumber // Display phone number

        binding.buttonVerify.setOnClickListener {
            val otp = getEnteredOtp()

            if (otp.length == 6) {
                binding.progressBar.visibility = View.VISIBLE
                verifyOtp(otp, phoneNumber ?: "")
            } else {
                Toast.makeText(this, "Enter valid OTP", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getEnteredOtp(): String {
        return binding.inputCode1.text.toString().trim() +
                binding.inputCode2.text.toString().trim() +
                binding.inputCode3.text.toString().trim() +
                binding.inputCode4.text.toString().trim() +
                binding.inputCode5.text.toString().trim() +
                binding.inputCode6.text.toString().trim()
    }

    private fun verifyOtp(otp: String, phoneNumber: String) {
        val credential = PhoneAuthProvider.getCredential(verificationId, otp)

        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        checkIfUserExists(user.uid, phoneNumber)
                    }
                } else {
                    Toast.makeText(this, "Invalid OTP", Toast.LENGTH_SHORT).show()
                    Log.e("OTP", "Verification Failed: ${task.exception?.message}")
                    binding.progressBar.visibility = View.GONE
                }
            }
    }

    private fun checkIfUserExists(uid: String, phoneNumber: String) {
        val userRef = firestore.collection("users").document(uid)

        userRef.get().addOnSuccessListener { document ->
            if (document.exists()) {
                Log.d("Firestore", "User already exists. Logging in...")
                navigateToMain()
            } else {
                createNewUser(uid, phoneNumber)
            }
        }.addOnFailureListener {
            Log.e("Firestore", "Error checking user existence: ${it.message}")
            Toast.makeText(this, "Database error. Try again.", Toast.LENGTH_SHORT).show()
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun createNewUser(uid: String, phoneNumber: String) {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val userData = hashMapOf(
            "displayName" to phoneNumber, // Using phone number as display name
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
                navigateToMain()
            }
            .addOnFailureListener {
                Log.e("Firestore", "Error saving user: ${it.message}")
                Toast.makeText(this, "Failed to create account. Try again.", Toast.LENGTH_SHORT).show()
                binding.progressBar.visibility = View.GONE
            }
    }

    private fun generateReferralCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }
}
