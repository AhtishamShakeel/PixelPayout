package com.example.pixelpayout.ui.auth

import android.annotation.SuppressLint
import android.provider.Settings.Secure
import kotlinx.coroutines.*
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.TextKeyListener
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import com.pixelpayout.R
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.utils.UserPreferences
import com.example.pixelpayout.utils.startLoading
import com.example.pixelpayout.utils.stopLoading
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.textfield.TextInputLayout
import com.pixelpayout.databinding.ActivityAuthBinding
import com.pixelpayout.ui.main.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class Auth : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        auth = FirebaseAuth.getInstance() // Initialize Firebase Authentication

        setupGoogleLogin()
        setupViews()
        observeViewModelLogin()
        observeViewModelSignup()
    }




    @SuppressLint("HardwareIds")
    private fun setupViews() {

        binding.inputEmail.addTextChangedListener(createTextWatcher(binding.emailLayout))
        binding.inputPassword.addTextChangedListener(createTextWatcher(binding.passwordInputLayout))
        binding.inputNewPassword.addTextChangedListener(createTextWatcher(binding.newPasswordInputLayout))
        binding.inputConfirmPassword.addTextChangedListener(createTextWatcher(binding.newConfirmPasswordInputLayout))
        binding.inputName.addTextChangedListener(createTextWatcher(binding.nameLayout))

        binding.btnContinue.setOnClickListener{

            val email = binding.inputEmail.text.toString().trim()
            if(validateInput(email)) {
                hideKeyboard(it)
                binding.btnContinue.startLoading()
                showLoading()
                viewModel.checkIfEmailExists(email)
            }
        }

        binding.btnLogin.setOnClickListener{
            val email = binding.inputEmail.text.toString()
            val password = binding.inputPassword.text.toString()
            hideKeyboard(it)
            viewModel.login(email,password, this)
            binding.btnLogin.startLoading()
            showLoading()
        }

        binding.btnSignup.setOnClickListener{
            val name = binding.inputName.text.toString()
            val email = binding.inputEmail.text.toString()
            val password = binding.inputNewPassword.text.toString()
            val confirmPassword = binding.inputConfirmPassword.text.toString()
            hideKeyboard(it)

            val androidId = Secure.getString(contentResolver,  Settings.Secure.ANDROID_ID)

            if(validateSignup(name, password, confirmPassword)){
                binding.btnSignup.startLoading()
                showLoading()
                viewModel.signup(name, email, password, androidId, this)

            }
        }

        viewModel.emailExists.observe(this, Observer { exists ->
            exists?.let {
                if (it) {
                    emailExists()
                    binding.loginOrSignupText.text = "Login to your Account"
                    binding.btnContinue.stopLoading("Continue")
                    hideLoading()
                } else {
                    emailNotExist()
                    binding.btnContinue.stopLoading("Continue")
                    hideLoading()
                    binding.loginOrSignupText.text = "Create new Account"
                }
            }
        })

        binding.btnGoogleSignin.setOnClickListener{
            signInWithGoogle()
        }

        binding.editEmail.setOnClickListener(){
            editEmail()
        }


    }


    private fun signInWithGoogle(){
        showLoading()
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
            hideLoading()
        }

    }

    private fun setupGoogleLogin() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // ✅ Get this from google-services.json
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso) // ✅ Initialize here
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInClient.revokeAccess()
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String){
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if(task.isSuccessful){
                    val user = auth.currentUser
                    user?.let{

                        val androidId = try {
                            Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        } catch (e: Exception) {
                            "UNKNOWN_ANDROID_ID" // ✅ Use fallback value
                        }
                        val userPreferences = UserPreferences(this)
                        lifecycleScope.launch {
                            userPreferences.setUsername(it.displayName ?: "User")
                            userPreferences.setHasSeenReferralPopup(false)
                        }

                        viewModel.checkIfUserExists(
                            it.uid,
                            it.displayName ?:"User",
                            it.email?:"",
                            androidId,
                            this,
                            onSuccess = {
                                navigateToMain()
                                lifecycleScope.launch{
                                    delay(400)
                                    hideLoading()
                                }
                                        },

                            onFailure = { errorMessage ->
                                hideLoading()
                                Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show() }
                        )
                    }
                } else{
                    hideLoading()
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

    private fun validateInput(email:String): Boolean {
        var isValid = true
        clearErrors()
        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()){
            binding.emailLayout.error = "Please Enter Email"
            isValid = false
        }
        return isValid
    }

    private fun validateSignup(name: String, password: String, confirmPassword: String): Boolean{
        var isValid = true
        clearErrors()

        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.error_name_required)
            isValid = false
        }

        if (password.length < 6) {
            binding.newPasswordInputLayout.error = getString(R.string.error_invalid_password)
            isValid = false
        }

        if (password != confirmPassword) {
            binding.newConfirmPasswordInputLayout.error = getString(R.string.error_pass_notequal_confirm)
            isValid = false
        }
        return isValid
    }

    private fun observeViewModelLogin() {
        viewModel.loginState.observe(this) { state ->
            when (state) {
                is AuthViewModel.LoginState.Loading -> {
                    clearErrors()
                }
                is AuthViewModel.LoginState.Success -> {
                    binding.btnLogin.stopLoading("Login")
                    navigateToMain()
                    lifecycleScope.launch{
                        delay(400)
                        hideLoading()
                    }
                }
                is AuthViewModel.LoginState.Error -> {
                    binding.btnLogin.stopLoading("Login")
                    hideLoading()
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG ).show()
                }
                is AuthViewModel.LoginState.Initial -> {
                    binding.btnLogin.stopLoading("Login")
                    hideLoading()
                    clearErrors()
                }
            }
        }
    }

    private fun observeViewModelSignup(){
        viewModel.signupState.observe(this) { state ->
            when (state) {
                is AuthViewModel.SignupState.Loading -> {
                    clearErrors()
                }
                is AuthViewModel.SignupState.Success -> {
                    navigateToMain()
                    binding.btnSignup.stopLoading("Signup")
                    lifecycleScope.launch{
                        delay(400)
                        hideLoading()
                    }

                }
                is AuthViewModel.SignupState.Error -> {
                    binding.btnSignup.stopLoading("Signup")
                    hideLoading()
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG ).show()
                }
                is AuthViewModel.SignupState.Initial -> {
                    binding.btnSignup.stopLoading("Signup")
                    hideLoading()

                    clearErrors()
                }
            }
        }
    }


    private fun navigateToMain(){
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }

    private fun editEmail(){
        binding.btnContinue.stopLoading("Continue")
        binding.layoutExistingUser.visibility = View.GONE
        binding.layoutNewUser.visibility = View.GONE
        binding.btnContinue.visibility = View.VISIBLE
        binding.inputEmail.isFocusable = true
        binding.inputEmail.isFocusableInTouchMode = true
        binding.inputEmail.keyListener = TextKeyListener.getInstance()
        binding.editEmail.visibility = View.GONE
        showKeyboard(binding.inputEmail)
        binding.nameLayout.visibility = View.GONE
        binding.loginOrSignupText.text = getString(R.string.create_new_account_text)
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
        binding.nameLayout.visibility = View.VISIBLE
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

    private fun createTextWatcher(inputLayout: TextInputLayout): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                inputLayout.error = null // Remove the error when user starts typing
                inputLayout.isErrorEnabled = false
            }
            override fun afterTextChanged(s: Editable?) {}
        }
    }


    private fun showLoading() {
        binding.loadingOverlay.visibility = View.VISIBLE // Show dark overlay
        binding.lottieLoading.visibility = View.VISIBLE // Show Lottie animation
        binding.lottieLoading.playAnimation() // Start animation
    }

    private fun hideLoading() {
            binding.lottieLoading.cancelAnimation()
            binding.lottieLoading.visibility = View.GONE
            binding.loadingOverlay.visibility = View.GONE

    }








}
