package com.pixelpayout.ui.redemption

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentRedemptionBinding
import com.pixelpayout.data.repository.UserRepository

class RedemptionFragment : Fragment() {
    private var _binding: FragmentRedemptionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReferralViewModel by viewModels {
        ReferralViewModelFactory(UserRepository.getInstance())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedemptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupReferralSystem()
        observeViewModel()
    }

    private fun setupReferralSystem() {
        binding.submitReferralButton.setOnClickListener {
            val referralCode = binding.referralCodeInput.text.toString().trim()
            if (referralCode.isEmpty()) {
                binding.referralInputLayout.error = getString(R.string.error_invalid_referral)
                return@setOnClickListener
            }

            viewModel.submitReferral(referralCode)
        }
    }

    private fun observeViewModel() {
        viewModel.referralResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ReferralResult.Success -> {
                    showSuccessMessage()
                    binding.referralCodeInput.text?.clear()
                }
                is ReferralResult.Error -> {
                    showErrorMessage(result.message)
                }
                is ReferralResult.InvalidCode -> {
                    binding.referralInputLayout.error = getString(R.string.error_invalid_referral)
                }
                is ReferralResult.AlreadyUsed -> {
                    binding.referralInputLayout.error = getString(R.string.error_already_used_referral)
                }
            }
        }
    }

    private fun showSuccessMessage() {
        Snackbar.make(
            binding.root,
            getString(R.string.referral_success),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showErrorMessage(message: String) {
        Snackbar.make(
            binding.root,
            "Error: $message",
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 