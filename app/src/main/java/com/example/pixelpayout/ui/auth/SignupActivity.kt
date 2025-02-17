package com.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivitySignupBinding
import com.pixelpayout.ui.main.MainActivity

class SignupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignupBinding
    private val viewModel: SignupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.signupButton.setOnClickListener {
            val name = binding.nameEditText.text.toString()
            val email = binding.emailEditText.text.toString()
            val password = binding.passwordEditText.text.toString()

            if (validateInput(name, email, password)) {
                viewModel.signup(name, email, password)
            }
        }

        binding.loginButton.setOnClickListener {
            finish()
        }
    }

    private fun observeViewModel() {
        viewModel.signupState.observe(this) { state ->
            when (state) {
                is SignupState.Loading -> {
                    showLoading(true)
                    clearErrors()
                }
                is SignupState.Success -> {
                    showLoading(false)
                    navigateToMain()
                }
                is SignupState.Error -> {
                    showLoading(false)
                    showError(state.message, state.field)
                }
                is SignupState.Initial -> {
                    showLoading(false)
                    clearErrors()
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.apply {
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            signupButton.isEnabled = !isLoading
            loginButton.isEnabled = !isLoading
            nameEditText.isEnabled = !isLoading
            emailEditText.isEnabled = !isLoading
            passwordEditText.isEnabled = !isLoading

            // Update button text
            signupButton.text = if (isLoading) "" else getString(R.string.sign_up)
            if (isLoading) {
                signupButton.icon = CircularProgressDrawable(this@SignupActivity).apply {
                    setStyle(CircularProgressDrawable.DEFAULT)
                    start()
                }
            } else {
                signupButton.icon = null
            }
        }
    }

    private fun showError(message: String, field: SignupField?) {
        when (field) {
            SignupField.NAME -> binding.nameLayout.error = message
            SignupField.EMAIL -> binding.emailLayout.error = message
            SignupField.PASSWORD -> binding.passwordLayout.error = message
            null -> {
                binding.errorText.apply {
                    text = message
                    visibility = View.VISIBLE
                    announceForAccessibility(message)
                }
            }
        }
    }

    private fun clearErrors() {
        binding.apply {
            errorText.visibility = View.GONE
            nameLayout.error = null
            emailLayout.error = null
            passwordLayout.error = null
        }
    }

    private fun validateInput(name: String, email: String, password: String): Boolean {
        var isValid = true
        clearErrors()

        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.error_name_required)
            isValid = false
        }

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
        finishAffinity()
    }
} 