package com.example.pixelpayout.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.auth.Auth
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.onboarding.TermsDialogFragment
import com.example.pixelpayout.ui.redemption.ReferralResult
import com.example.pixelpayout.ui.redemption.ReferralViewModel
import com.example.pixelpayout.ui.redemption.ReferralViewModelFactory
import com.example.pixelpayout.ui.redemption.WalletFormat
import com.example.pixelpayout.utils.UserPreferences
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.pixelpayout.BuildConfig
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentProfileBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile, built on the Profile.dc.html handoff.
 *
 * Identity, level, three stats, referrals in both directions, and the
 * account rows. Referrals live here rather than on Wallet: the code this
 * account hands out is part of who it is, and claiming somebody else's is a
 * once-ever act with nothing to do with spending a balance.
 *
 * The referral progress list is the one thing here that needs the server -
 * firestore.rules never grants a client a read across users, so who used your
 * code can only come from a callable. When that callable is unavailable the
 * screen shows the empty state rather than an error, because a user can do
 * nothing about a function that has not been deployed.
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

    private lateinit var inviteeAdapter: InviteeAdapter

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
        setupStats()
        setupList()
        setupReferralSharing()
        setupReferralClaim()
        setupAccountRows()
        observeViewModel()

        mainViewModel.refreshReferralStats()
    }

    override fun onResume() {
        super.onResume()
        // Aggregates across other people's documents, so there is no snapshot
        // to listen to - it is re-read whenever the tab comes back.
        mainViewModel.refreshReferralStats()
    }

    private fun setupIdentity() {
        val user = FirebaseAuth.getInstance().currentUser
        binding.profileEmail.text = user?.email.orEmpty()
        binding.profileEmail.isVisible = !user?.email.isNullOrBlank()

        // From the auth record, not the user document - nothing in Firestore
        // records when an account was created, and Firebase already knows.
        val created = user?.metadata?.creationTimestamp
        binding.profileMemberSince.isVisible = created != null && created > 0
        if (created != null && created > 0) {
            binding.profileMemberSince.text = getString(
                R.string.profile_member_since,
                SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(created))
            )
        }

        binding.profileVersion.text = getString(R.string.profile_version, BuildConfig.VERSION_NAME)

        val preferences = UserPreferences(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch {
            preferences.username.collect { username ->
                val b = _binding ?: return@collect
                val name = username?.takeIf { it.isNotBlank() }
                    ?: user?.displayName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.nav_profile)
                b.profileName.text = name
                b.profileInitials.text = initialsOf(name)
            }
        }
    }

    private fun initialsOf(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(2).uppercase()
            else -> "${parts[0].first()}${parts[1].first()}".uppercase()
        }
    }

    /**
     * The three stat tiles.
     *
     * The handoff shows "Points earned / Quizzes done / Games played". None
     * of those counters exist - the ledger records events, nothing totals
     * them per user - so these are three figures the app genuinely holds.
     */
    private fun setupStats() {
        binding.statStars.statLabel.setText(R.string.profile_stat_stars)
        binding.statStars.statIcon.setImageResource(R.drawable.ic_star)
        binding.statStars.statIcon.imageTintList =
            android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.gold))

        binding.statXp.statLabel.setText(R.string.profile_stat_xp)
        binding.statXp.statIcon.setImageResource(R.drawable.ic_bolt)

        binding.statStreak.statLabel.setText(R.string.profile_stat_streak)
        binding.statStreak.statIcon.setImageResource(R.drawable.ic_history)

        binding.funnelInvited.statLabel.setText(R.string.profile_funnel_invited)
        binding.funnelInvited.statIcon.setImageResource(R.drawable.ic_users)
        binding.funnelQualified.statLabel.setText(R.string.profile_funnel_qualified)
        binding.funnelQualified.statIcon.setImageResource(R.drawable.ic_shield_check)
        binding.funnelPaid.statLabel.setText(R.string.profile_funnel_paid)
        binding.funnelPaid.statIcon.setImageResource(R.drawable.ic_check)
    }

    private fun setupList() {
        inviteeAdapter = InviteeAdapter()
        binding.inviteesRecyclerView.adapter = inviteeAdapter
        binding.inviteesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.inviteesRecyclerView.isNestedScrollingEnabled = false
    }

    private fun setupReferralSharing() {
        val copy = View.OnClickListener { copyReferralCode() }
        binding.referralCodeValue.setOnClickListener(copy)
        binding.referralCopy.setOnClickListener(copy)
        binding.referralShare.setOnClickListener { shareReferralCode() }
    }

    private fun currentCode(): String = binding.referralCodeValue.text.toString().trim()

    private fun copyReferralCode() {
        val code = currentCode()
        if (code.isEmpty()) return

        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.referral_section_label), code)
        )
        Snackbar.make(binding.root, getString(R.string.referral_copied), Snackbar.LENGTH_SHORT)
            .show()
    }

    /** Opens the system chooser. Which app, and whether to send at all, stays
     *  the user's decision - nothing is sent from here. */
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

    private fun setupAccountRows() {
        // Both documents already live in the app as strings and already have
        // a dialog that renders them - the one onboarding shows. Pointing
        // these rows at a hosted page instead would mean maintaining a second
        // copy of the text, and public/ has no such page to point at.
        binding.rowTerms.setOnClickListener { showLegal("terms") }
        binding.rowPrivacy.setOnClickListener { showLegal("privacy") }
        binding.rowSupport.setOnClickListener { openSupportEmail() }
        binding.rowSignOut.setOnClickListener { confirmSignOut() }
    }

    private fun showLegal(type: String) {
        TermsDialogFragment.newInstance(type)
            .show(parentFragmentManager, "legal_$type")
    }

    /**
     * Support is a mail intent rather than an in-app form: there is no ticket
     * system behind this, and a form that quietly went nowhere would be worse
     * than handing the user an address they can see.
     */
    private fun openSupportEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$SUPPORT_EMAIL")
            putExtra(Intent.EXTRA_SUBJECT, "PixelPayout support")
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Snackbar.make(binding.root, SUPPORT_EMAIL, Snackbar.LENGTH_LONG).show()
            }
    }

    /** Confirmed, because signing out of an account holding a balance is not
     *  something to do on a mis-tap. */
    private fun confirmSignOut() {
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.profile_sign_out_confirm)
            .setPositiveButton(R.string.profile_sign_out) { _, _ ->
                FirebaseAuth.getInstance().signOut()
                startActivity(
                    Intent(requireContext(), Auth::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
                requireActivity().finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            binding.statStars.statValue.text = WalletFormat.number(state.points)
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.profileLevel.text = getString(R.string.profile_level, progress.level)
            binding.statXp.statValue.text = WalletFormat.number(progress.totalXp)

            val percent = when {
                progress.isMaxLevel -> 100
                progress.xpForNextLevel <= 0 -> 0
                else -> (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
            }
            binding.profileLevelBar.progress = percent
            binding.profileLevelNote.text = if (progress.isMaxLevel) {
                getString(R.string.profile_level_note_max)
            } else {
                getString(
                    R.string.profile_level_note,
                    WalletFormat.number(progress.xpIntoLevel),
                    WalletFormat.number(progress.xpForNextLevel),
                    progress.level + 1
                )
            }
        }

        mainViewModel.streak.observe(viewLifecycleOwner) { streak ->
            binding.statStreak.statValue.text = WalletFormat.number(streak.count)
        }

        mainViewModel.referralCode.observe(viewLifecycleOwner) { code ->
            binding.referralCodeValue.text = code
        }

        // The server is the authority: the user document flipping to true is
        // what retires the input, whether it was this screen, the first-run
        // popup, or another device that spent the claim.
        mainViewModel.hasUsedReferral.observe(viewLifecycleOwner) { used ->
            binding.referralClaimGroup.isVisible = !used
            binding.referralClaimedNote.isVisible = used
        }

        mainViewModel.referralStats.observe(viewLifecycleOwner) { stats ->
            renderReferralStats(stats)
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

    /**
     * Null stats means the callable is unavailable - most often not deployed
     * yet. The screen then shows the empty state and the rule line falls back
     * to naming no numbers, rather than surfacing an error nobody using the
     * app can act on.
     */
    private fun renderReferralStats(stats: UserRepository.ReferralStats?) {
        val invitees = stats?.invitees.orEmpty()

        inviteeAdapter.submitList(invitees)
        inviteeAdapter.updateReward(stats?.referrerReward ?: 0)

        binding.inviteesRecyclerView.isVisible = invitees.isNotEmpty()
        binding.inviteesEmpty.isVisible = invitees.isEmpty()

        binding.inviteCount.text = getString(R.string.profile_invited_count, stats?.invited ?: 0)
        binding.inviteCount.isVisible = stats != null

        binding.referralFunnel.isVisible = stats != null && invitees.isNotEmpty()
        if (stats != null) {
            binding.funnelInvited.statValue.text = stats.invited.toString()
            binding.funnelQualified.statValue.text = stats.qualified.toString()
            binding.funnelPaid.statValue.text = stats.paid.toString()

            binding.profileEarnedLabel.text = getString(
                R.string.profile_earned,
                WalletFormat.number(stats.paid * stats.referrerReward)
            )
            binding.referralRuleLine.text = getString(
                R.string.profile_referral_rule,
                stats.referrerReward,
                stats.unlockXp
            )
            binding.referralClaimSub.text =
                getString(R.string.profile_claim_sub, stats.refereeReward)
        }
        binding.profileEarnedLabel.isVisible = stats != null
        binding.referralRuleLine.isVisible = stats != null
        binding.referralClaimSub.isVisible = stats != null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val SUPPORT_EMAIL = "earningapphelper@gmail.com"
    }
}
