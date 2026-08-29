package com.example.pixelpayout.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentProfileBinding
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.redemption.ReferralResult
import com.example.pixelpayout.ui.redemption.ReferralViewModel
import com.example.pixelpayout.ui.redemption.ReferralViewModelFactory
import com.example.pixelpayout.utils.UserPreferences
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Profile: who this account is, and referrals.
 *
 * Referrals moved here from Wallet. Both halves of it belong to identity
 * rather than to a balance - the code this user hands out is theirs, and
 * claiming someone else's is a once-ever act with no relationship to
 * spending. On Wallet the form was also the first thing the tab showed, above
 * the rewards it exists to sell.
 *
 * The claim half retires itself once hasUsedReferral is true. Leaving an
 * input on screen that the server will always reject is worse than removing
 * it, and this is the one referral fact the client can know in advance.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    /**
     * Activity scoped, matching ReferralDialogFragment: the first-run popup
     * and this card submit the same code to the same place, and a result
     * arriving after the other has been dismissed should not be orphaned.
     */
    private val referralViewModel: ReferralViewModel by activityViewModels {
        ReferralViewModelFactory(UserRepository())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupIdentity()
        setupReferralSharing()
        setupReferralClaim()
        observeViewModel()
    }

    private fun setupIdentity() {
        val preferences = UserPreferences(requireContext().applicationContext)

        viewLifecycleOwner.lifecycleScope.launch {
            preferences.username.collect { username ->
                _binding?.profileName?.text = username ?: getString(R.string.nav_profile)
            }
        }
    }

    private fun setupReferralSharing() {
        val copy = View.OnClickListener { copyReferralCode() }
        binding.referralCodeBox.setOnClickListener(copy)
        binding.referralCopy.setOnClickListener(copy)
        binding.referralShare.setOnClickListener { shareReferralCode() }
    }

    private fun currentCode(): String = binding.referralCodeValue.text.toString().trim()

    private fun copyReferralCode() {
        val code = currentCode()
        if (code.isEmpty()) return

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.referral_section_label), code))

        Snackbar.make(binding.root, getString(R.string.referral_copied), Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Opens the system chooser. Nothing is sent from here - which app, and
     * whether to send at all, stays the user's decision.
     */
    private fun shareReferralCode() {
        val code = currentCode()
        if (code.isEmpty()) return

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.referral_share_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.referral_share_text, code))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.referral_share)))
    }

    private fun setupReferralClaim() {
        binding.submitReferralButton.setOnClickListener {
            val referralCode = binding.referralCodeInput.text.toString().trim()
            if (referralCode.isEmpty()) {
                binding.referralInputLayout.error = getString(R.string.error_invalid_referral)
                return@setOnClickListener
            }

            binding.referralInputLayout.error = null
            binding.submitReferralButton.isEnabled = false
            referralViewModel.submitReferral(referralCode)
        }
    }

    private fun observeViewModel() {
        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            binding.profileMeta.text = getString(
                R.string.profile_meta,
                state.level,
                NUMBER.format(state.points)
            )
        }

        mainViewModel.referralCode.observe(viewLifecycleOwner) { code ->
            binding.referralCodeValue.text = code
            binding.referralCodeBox.isVisible = code.isNotBlank()
        }

        // The server is the authority on this: the user document flipping to
        // true is what retires the input, whether it was this screen, the
        // first-run popup or another device that spent the claim.
        mainViewModel.hasUsedReferral.observe(viewLifecycleOwner) { used ->
            binding.referralClaimGroup.isVisible = !used
            binding.referralClaimedNote.isVisible = used
        }

        referralViewModel.referralResult.observe(viewLifecycleOwner) { result ->
            if (result == null) return@observe
            binding.submitReferralButton.isEnabled = true

            when (result) {
                is ReferralResult.Success -> {
                    binding.referralInputLayout.error = null
                    binding.referralCodeInput.text?.clear()
                    Snackbar.make(
                        binding.root,
                        getString(R.string.referral_success),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                is ReferralResult.InvalidCode ->
                    binding.referralInputLayout.error = getString(R.string.error_invalid_referral)
                is ReferralResult.AlreadyUsed ->
                    binding.referralInputLayout.error =
                        getString(R.string.error_already_used_referral)
                is ReferralResult.Error ->
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
            }

            referralViewModel.clearReferralResult()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val NUMBER: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
    }
}
