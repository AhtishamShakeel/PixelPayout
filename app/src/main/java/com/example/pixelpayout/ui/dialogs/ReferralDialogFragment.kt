package com.pixelpayout.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.DialogReferralBinding
import com.pixelpayout.ui.redemption.ReferralResult
import com.pixelpayout.ui.redemption.ReferralViewModel
import com.pixelpayout.ui.redemption.ReferralViewModelFactory

class ReferralDialogFragment : DialogFragment() {

    private var _binding: DialogReferralBinding? = null
    private val binding get() = _binding!!

    // ✅ Get ViewModel from MainActivity (shared ViewModel)
    private val referralViewModel: ReferralViewModel by activityViewModels {
        ReferralViewModelFactory(UserRepository())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogReferralBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.CustomDialogTheme)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ Prevent dismissing when clicking outside
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(false)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        // ✅ Close only when user clicks "X" button
        binding.btnCloseReferral.setOnClickListener {
            dismiss()
        }

        // ✅ Submit referral code
        binding.btnSubmitReferral.setOnClickListener {
            val referralCode = binding.referralInput.text.toString().trim()

            if (referralCode.isEmpty()) {
                binding.referralInputLayout.error = "Please enter a referral code"
                return@setOnClickListener
            }

            binding.referralInputLayout.error = null // Clear previous error
            binding.btnSubmitReferral.isEnabled = false // Disable button while processing

            referralViewModel.submitReferral(referralCode) // Send referral code to ViewModel
        }
    }

    private fun observeViewModel() {
        referralViewModel.referralResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ReferralResult.Success -> {
                    showSuccessMessage()
                    dismiss() // ✅ Close dialog only on success
                }
                is ReferralResult.InvalidCode -> {
                    binding.referralInputLayout.error = "Invalid referral code"
                }
                is ReferralResult.AlreadyUsed -> {
                    binding.referralInputLayout.error = "Referral already used"
                }
                is ReferralResult.Error -> {
                    showErrorMessage(result.message)
                }
            }
            binding.btnSubmitReferral.isEnabled = true // Re-enable button after result
        }
    }

    private fun showSuccessMessage() {
        Snackbar.make(requireView(), "Referral Applied Successfully!", Snackbar.LENGTH_LONG).show()
    }

    private fun showErrorMessage(message: String) {
        Snackbar.make(requireView(), "Error: $message", Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
