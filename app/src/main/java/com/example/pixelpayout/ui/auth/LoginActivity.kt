package com.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityLoginBinding
import com.pixelpayout.ui.main.MainActivity
//import com.google.android.material.progressindicator.CircularProgressDrawable

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (viewModel.isUserLoggedIn()) {
            navigateToMain()
            return
        }

        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.loginButton.setOnClickListener {
            val email = binding.emailEditText.text.toString()
            val password = binding.passwordEditText.text.toString()

            if (validateInput(email, password)) {
                viewModel.login(email, password)
            }
        }

        binding.signupButton.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.loginState.observe(this) { state ->
            when (state) {
                is LoginState.Loading -> {
                    showLoading(true)
                    clearErrors()
                }
                is LoginState.Success -> {
                    showLoading(false)
                    navigateToMain()
                }
                is LoginState.Error -> {
                    showLoading(false)
                    showError(state.message)
                }
                is LoginState.Initial -> {
                    showLoading(false)
                    clearErrors()
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.apply {
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            loginButton.isEnabled = !isLoading
            signupButton.isEnabled = !isLoading
            emailEditText.isEnabled = !isLoading
            passwordEditText.isEnabled = !isLoading

            // Update button text
            loginButton.text = if (isLoading) "" else getString(R.string.login)
            if (isLoading) {
                loginButton.icon = CircularProgressDrawable(this@LoginActivity).apply {
                    setStyle(CircularProgressDrawable.DEFAULT)
                    start()
                }
            } else {
                loginButton.icon = null
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.apply {
            text = message
            visibility = View.VISIBLE
            announceForAccessibility(message) // For accessibility
        }
    }

    private fun clearErrors() {
        binding.apply {
            errorText.visibility = View.GONE
            emailLayout.error = null
            passwordLayout.error = null
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        var isValid = true
        clearErrors()

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = getString(R.string.error_invalid_email)
            isValid = false
        }

        if (password.length < 6) {
            binding.passwordLayout.error = getString(R.string.error_invalid_password)
            isValid = false
        }

        return isValid
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
} 