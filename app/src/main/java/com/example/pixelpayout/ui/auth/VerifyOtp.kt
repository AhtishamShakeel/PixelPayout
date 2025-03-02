package com.example.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
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
    private val viewModel: AuthViewModel by viewModels()

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
                    user?.let {
                        viewModel.checkIfUserExists(
                            it.uid,
                            phoneNumber,
                            "",
                            onSuccess = { navigateToMain() },
                            onFailure = { errorMessage -> Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()}

                        )
                    }
                } else {
                    Toast.makeText(this, "Invalid OTP", Toast.LENGTH_SHORT).show()
                }
            }
    }
    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }
}
