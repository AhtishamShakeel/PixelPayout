package com.example.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.pixelpayout.databinding.ActivitySendOtpBinding
import java.util.concurrent.TimeUnit

class SendOtp : AppCompatActivity() {

    private lateinit var binding: ActivitySendOtpBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var verificationId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance() // Initialize Firebase Authentication

        setupViews()
    }

    private fun setupViews() {
        binding.btnGet.setOnClickListener {
            val number = binding.inputMobile.text.toString().trim()

            if (number.isBlank()) {
                Toast.makeText(this, "Enter Phone Number", Toast.LENGTH_LONG).show()
                binding.progressBar.visibility = View.GONE
                return@setOnClickListener
            }

            binding.progressBar.visibility = View.VISIBLE
            binding.btnGet.visibility = View.GONE

            sendOtp("+92$number") // Send OTP
        }
    }

    private fun sendOtp(phoneNumber: String) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    Log.d("OTP", "Auto Verification Completed: $credential")
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    Log.e("OTP", "Verification Failed: ${e.message}")
                    Toast.makeText(this@SendOtp, "Verification Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnGet.visibility = View.VISIBLE
                }

                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    this@SendOtp.verificationId = verificationId
                    Log.d("OTP", "OTP Sent: $verificationId")
                    Toast.makeText(this@SendOtp, "OTP Sent", Toast.LENGTH_LONG).show()

                    // ✅ Navigate to VerifyOtp screen with the verification ID
                    val intent = Intent(this@SendOtp, VerifyOtp::class.java)
                    intent.putExtra("verificationId", verificationId)
                    intent.putExtra("phoneNumber", phoneNumber)
                    startActivity(intent)
                }
            })
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }
}
