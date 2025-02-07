package com.pixelpayout.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
            finish() // Return to LoginActivity
        }
    }

    private fun observeViewModel() {
        viewModel.signupResult.observe(this) { result ->
            result.fold(
                onSuccess = { navigateToMain() },
                onFailure = { exception ->
                    Toast.makeText(
                        this,
                        getString(R.string.error_signup_failed, exception.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun validateInput(name: String, email: String, password: String): Boolean {
        var isValid = true

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
        finishAffinity() // Close all auth activities
    }
} 