package com.example.pixelpayout.ui.auth

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.text.method.TextKeyListener
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import com.pixelpayout.R
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.pixelpayout.databinding.ActivityAuthBinding
import com.pixelpayout.ui.main.MainActivity
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class Auth : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var verificationId: String
    private lateinit var googleSignInClient: GoogleSignInClient
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance() // Initialize Firebase Authentication

        setupGoogleLogin()
        setupViews()
    }




    private fun setupViews() {

        binding.btnContinue.setOnClickListener{

            val name = binding.inputName.text.toString().trim()
            val email = binding.inputEmail.text.toString().trim()
                if(validateInput(name, email)) {
                    hideKeyboard(it)
                    startLoading()
                    viewModel.checkIfEmailExists(email)
                }
        }

        viewModel.emailExists.observe(this, Observer { exists ->
            exists?.let {
                if (it) {
                    emailExists()
                    stopLoading()
                } else {
                    emailNotExist()
                    stopLoading()
                    Toast.makeText(this, "Email is available", Toast.LENGTH_SHORT).show()
                }
            }
        })

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

        binding.btnGoogleSignin.setOnClickListener{
            signInWithGoogle()
        }

        binding.editEmail.setOnClickListener(){
            editEmail()
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
                    Toast.makeText(this@Auth, "Verification Failed: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                    binding.btnGet.visibility = View.VISIBLE
                }

                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    this@Auth.verificationId = verificationId
                    Log.d("OTP", "OTP Sent: $verificationId")
                    Toast.makeText(this@Auth, "OTP Sent", Toast.LENGTH_LONG).show()

                    // ✅ Navigate to VerifyOtp screen with the verification ID
                    val intent = Intent(this@Auth, VerifyOtp::class.java)
                    intent.putExtra("verificationId", verificationId)
                    intent.putExtra("phoneNumber", phoneNumber)
                    startActivity(intent)
                }
            })
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }


    private fun signInWithGoogle(){
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken!!)
        }
        catch (e: Exception) {
            Log.e("Google SignIn", "Error: ${e.message}", e)
            Toast.makeText(this, "Google Sign-in Failed", Toast.LENGTH_SHORT).show()
        }

    }

    private fun setupGoogleLogin() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(com.pixelpayout.R.string.default_web_client_id)) // ✅ Get this from google-services.json
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso) // ✅ Initialize here
    }


    private fun firebaseAuthWithGoogle(idToken: String){
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if(task.isSuccessful){
                    val user = auth.currentUser
                    user?.let{
                        viewModel.checkIfUserExists(
                            it.uid,
                            it.displayName ?:"User",
                            it.email?:"",
                            onSuccess = { navigateToMain()},
                            onFailure = { errorMessage -> Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show() }
                        )
                    }
                } else{
                    Toast.makeText(this, "Authentication Failed", Toast.LENGTH_SHORT).show()
                }

            }


    }
    private fun clearErrors() {
        binding.apply {
            nameLayout.error = null
            emailLayout.error = null
        }
    }

    private fun validateInput(name: String, email:String): Boolean {
        var isValid = true
        clearErrors()

        if (name.isBlank()) {
            binding.nameLayout.error = "Please Enter Name"
            isValid = false
        }
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()){
            binding.emailLayout.error = "Please Enter Email"
            isValid = false
        }
        return isValid
    }
    private fun navigateToMain(){
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }

    private fun startLoading(){
        binding.btnContinue.isEnabled = false  // Disable the button
        binding.btnContinue.text = ""  // Remove text
        binding.btnContinue.icon = ContextCompat.getDrawable(this, R.drawable.progress_loader)
        (binding.btnContinue.icon as? AnimatedVectorDrawable)?.start()
    }

    private fun stopLoading(){
        binding.btnContinue.isEnabled = true
        binding.btnContinue.text = "Continue"
        binding.btnContinue.icon = null

    }

    private fun editEmail(){
        stopLoading()
        binding.layoutExistingUser.visibility = View.GONE
        binding.layoutNewUser.visibility = View.GONE
        binding.btnContinue.visibility = View.VISIBLE
        binding.inputEmail.isFocusable = true
        binding.inputEmail.isFocusableInTouchMode = true
        binding.inputEmail.keyListener = TextKeyListener.getInstance()
        binding.editEmail.visibility = View.GONE
        showKeyboard(binding.inputEmail)


    }

    private fun emailExists(){
        binding.layoutExistingUser.visibility = View.VISIBLE
        binding.btnContinue.visibility = View.GONE
        binding.inputEmail.isFocusable = false
        binding.inputEmail.isFocusableInTouchMode = false
        binding.inputEmail.keyListener = null
        binding.editEmail.visibility = View.VISIBLE

    }

    private fun emailNotExist(){
        binding.btnContinue.visibility = View.GONE
        binding.inputEmail.isFocusable = false
        binding.inputEmail.isFocusableInTouchMode = false
        binding.inputEmail.keyListener = null
        binding.editEmail.visibility = View.VISIBLE
        binding.layoutNewUser.visibility = View.VISIBLE
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
    private fun showKeyboard(view: View) {
        view.requestFocus() // Set focus to the view
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }





}
