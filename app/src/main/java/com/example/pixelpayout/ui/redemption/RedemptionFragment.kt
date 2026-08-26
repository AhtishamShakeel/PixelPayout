package com.example.pixelpayout.ui.redemption

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentRedemptionBinding
import com.example.pixelpayout.data.model.RedemptionOption
import com.example.pixelpayout.data.model.RedemptionType
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel

class RedemptionFragment : Fragment() {
    private var _binding: FragmentRedemptionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReferralViewModel by viewModels {
        ReferralViewModelFactory(UserRepository())
    }
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: RedemptionAdapter

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
        setupRedemptionList()
        observeViewModel()
        viewModel.loadOptions()
    }

    private fun setupRedemptionList() {
        adapter = RedemptionAdapter { option -> confirmRedemption(option) }
        binding.redemptionRecyclerView.adapter = adapter
        binding.redemptionRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.redemptionRecyclerView.isNestedScrollingEnabled = false
    }

    /**
     * Cash payouts need a destination number, so ask for it before spending.
     * Redeeming is irreversible from the user's side, so it always confirms.
     */
    private fun confirmRedemption(option: RedemptionOption) {
        if (option.type == RedemptionType.EASYPAISA) {
            val input = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_PHONE
                hint = getString(R.string.enter_phone_number)
            }

            AlertDialog.Builder(requireContext())
                .setTitle(option.title)
                .setMessage(getString(R.string.confirm_redemption, option.pointsCost))
                .setView(input)
                .setPositiveButton(R.string.redeem) { _, _ ->
                    viewModel.redeem(option, input.text.toString().trim())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(option.title)
                .setMessage(getString(R.string.confirm_redemption, option.pointsCost))
                .setPositiveButton(R.string.redeem) { _, _ ->
                    viewModel.redeem(option, null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
        viewModel.options.observe(viewLifecycleOwner) { options ->
            adapter.submitList(options)
        }

        // Affordability/level gates in the list follow the live balance.
        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            adapter.updateUserState(state.points, state.level)
        }

        viewModel.isRedeeming.observe(viewLifecycleOwner) { busy ->
            binding.progressIndicator.visibility = if (busy) View.VISIBLE else View.GONE
        }

        viewModel.redemptionResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is RedemptionResult.Success -> {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.redemption_submitted, result.pointsSpent),
                        Snackbar.LENGTH_LONG
                    ).show()
                    viewModel.clearRedemptionResult()
                }
                is RedemptionResult.Error -> {
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                    viewModel.clearRedemptionResult()
                }
                null -> Unit
            }
        }

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