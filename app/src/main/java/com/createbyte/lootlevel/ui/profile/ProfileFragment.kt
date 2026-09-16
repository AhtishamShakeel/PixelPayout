package com.createbyte.lootlevel.ui.profile

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
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.ui.auth.Auth
import com.createbyte.lootlevel.ui.main.MainViewModel
import com.createbyte.lootlevel.ui.legal.LegalActivity
import com.createbyte.lootlevel.ui.redemption.ReferralResult
import com.createbyte.lootlevel.ui.redemption.ReferralViewModel
import com.createbyte.lootlevel.ui.redemption.ReferralViewModelFactory
import com.createbyte.lootlevel.ui.redemption.WalletFormat
import com.createbyte.lootlevel.utils.UserPreferences
import com.createbyte.lootlevel.utils.AdConsent
import com.createbyte.lootlevel.data.repository.SupportTicketStore
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.createbyte.lootlevel.BuildConfig
import com.createbyte.lootlevel.utils.setStarText
import com.createbyte.lootlevel.utils.showAppDialog
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.FragmentProfileBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Profile, built on the Profile.dc.html handoff.
 *
 * Identity, three stats, the Refer & Earn card, claiming a code, and the
 * account rows. Who used this account's code lives on ReferralsFragment,
 * opened from the referrals row. Referrals live here rather than on Wallet: the code this
 * account hands out is part of who it is, and claiming somebody else's is a
 * once-ever act with nothing to do with spending a balance.
 *
 * The referral figures are the one thing here that need the server -
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
        setupReferralSharing()
        setupReferralClaim()
        setupAccountRows()
        observeViewModel()

        mainViewModel.refreshReferralStats()

        if (arguments?.getBoolean(ARG_SCROLL_TO_REFERRAL) == true) {
            // Consumed once: the tab is a single fragment instance, so an
            // un-cleared flag would re-scroll every time the user came back
            // to Profile from anywhere else.
            arguments?.remove(ARG_SCROLL_TO_REFERRAL)
            scrollToReferral()
        }
    }

    /**
     * Posted rather than called straight away - at this point the scroll view
     * has not been laid out, so the invite header has no y to scroll to yet.
     */
    private fun scrollToReferral() {
        binding.profileScroll.post {
            val b = _binding ?: return@post
            b.profileScroll.smoothScrollTo(0, b.inviteHeader.top)
        }
    }

    override fun onResume() {
        super.onResume()
        // Aggregates across other people's documents, so there is no snapshot
        // to listen to - it is re-read whenever the tab comes back.
        mainViewModel.refreshReferralStats()
        // Only users under consent rules (EEA/UK/Switzerland) get the row.
        binding.rowPrivacyChoices.isVisible = AdConsent.privacyOptionsRequired(requireContext())
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
        // The tile layout tints its icon `stars_accent`, which is gold. Only
        // the Stars tile is about stars, so the other two are repainted to
        // the brand violet here - left alone they inherit the gold and the
        // row reads as three currencies that are all the same one.
        val gold = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.gold)
        )
        val violet = android.content.res.ColorStateList.valueOf(
            requireContext().getColor(R.color.brand_violet_light)
        )

        binding.statStars.statLabel.setText(R.string.profile_stat_stars)
        binding.statStars.statIcon.setImageResource(R.drawable.ic_star)
        binding.statStars.statIcon.imageTintList = gold

        binding.statXp.statLabel.setText(R.string.profile_stat_xp)
        binding.statXp.statIcon.setImageResource(R.drawable.ic_bolt)
        binding.statXp.statIcon.imageTintList = violet

        binding.statStreak.statLabel.setText(R.string.profile_stat_streak)
        binding.statStreak.statIcon.setImageResource(R.drawable.ic_history)
        binding.statStreak.statIcon.imageTintList = violet

    }

    private fun setupReferralSharing() {
        val copy = View.OnClickListener { copyReferralCode() }
        binding.referralCodeValue.setOnClickListener(copy)
        binding.referralCopy.setOnClickListener(copy)
        binding.referralShare.setOnClickListener { shareReferralCode() }

        // "How will I get Stars?" opens and closes the full rule.
        binding.referralHowToggle.setOnClickListener {
            binding.referralRuleNote.isVisible = !binding.referralRuleNote.isVisible
        }
        binding.referralsRow.setOnClickListener { openReferrals() }
    }

    private fun openReferrals() = openPage(R.id.referralsFragment)

    /** A page stacked over Profile. Guarded against a double tap pushing it twice. */
    private fun openPage(destination: Int) {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.navigation_profile) return
        controller.navigate(
            destination,
            null,
            NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.fade_out)
                .build()
        )
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
        // The documents are the HTML files in public/legal, bundled into the app; see
        // LegalActivity.
        binding.rowTerms.setOnClickListener {
            LegalActivity.open(requireContext(), LegalActivity.Doc.TERMS)
        }
        binding.rowPrivacy.setOnClickListener {
            LegalActivity.open(requireContext(), LegalActivity.Doc.PRIVACY)
        }
        binding.rowSupport.setOnClickListener { openPage(R.id.helpFragment) }
        binding.rowPrivacyChoices.setOnClickListener {
            AdConsent.showPrivacyOptions(requireActivity())
        }
        binding.rowDeleteAccount.setOnClickListener { confirmDeleteAccount() }
        binding.rowSignOut.setOnClickListener { confirmSignOut() }
    }

    /**
     * Delete account, as Google Play requires. Confirmed in red first; the
     * server refuses while a redemption is pending, and on success the Auth
     * user is gone, so the app signs out and returns to sign-in.
     */
    private fun confirmDeleteAccount() {
        requireContext().showAppDialog(
            title = R.string.delete_account_title,
            message = R.string.delete_account_message,
            icon = R.drawable.ic_trash,
            accent = R.color.difficulty_hard,
            positiveText = R.string.delete_account_confirm,
            negativeText = R.string.cancel,
            positiveDelaySeconds = 5,
            onPositive = { deleteAccount() }
        )
    }

    private fun deleteAccount() {
        val activity = requireActivity()
        binding.rowDeleteAccount.isEnabled = false
        binding.rowDeleteAccount.setText(R.string.delete_account_working)

        // The activity's scope: the call must finish even if the tab changes.
        activity.lifecycleScope.launch {
            when (val result = mainViewModel.deleteAccount()) {
                UserRepository.DeleteAccountResult.Deleted -> signOutToAuth(activity)
                UserRepository.DeleteAccountResult.PendingRedemption -> {
                    restoreDeleteRow()
                    activity.showAppDialog(
                        title = R.string.profile_delete_account,
                        message = R.string.delete_account_pending,
                        positiveText = R.string.ok
                    )
                }
                is UserRepository.DeleteAccountResult.Error -> {
                    restoreDeleteRow()
                    _binding?.let {
                        Snackbar.make(it.root, R.string.delete_account_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun restoreDeleteRow() {
        val b = _binding ?: return
        b.rowDeleteAccount.isEnabled = true
        b.rowDeleteAccount.setText(R.string.profile_delete_account)
    }

    private fun signOutToAuth(activity: android.app.Activity) {
        FirebaseAuth.getInstance().signOut()
        activity.startActivity(
            Intent(activity, Auth::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        activity.finish()
    }

    /** Confirmed, because signing out of an account holding a balance is not
     *  something to do on a mis-tap. */
    private fun confirmSignOut() {
        requireContext().showAppDialog(
            title = R.string.profile_sign_out,
            message = R.string.profile_sign_out_confirm,
            // The same glyph and the same red the sign-out row wears, so the
            // dialog reads as that row confirming itself rather than as a
            // generic warning that arrived from somewhere else.
            icon = R.drawable.ic_arrow_up_right,
            accent = R.color.difficulty_hard,
            positiveText = R.string.profile_sign_out,
            negativeText = R.string.cancel,
            onPositive = { signOutToAuth(requireActivity()) }
        )
    }

    private fun observeViewModel() {
        mainViewModel.userState.observe(viewLifecycleOwner) { state ->
            binding.statStars.statValue.text = WalletFormat.number(state.points)
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.statXp.statValue.text = WalletFormat.number(progress.totalXp)
        }

        mainViewModel.streak.observe(viewLifecycleOwner) { streak ->
            binding.statStreak.statValue.text = WalletFormat.number(streak.count)
        }

        mainViewModel.referralCode.observe(viewLifecycleOwner) { code ->
            binding.referralCodeValue.text = code
        }

        // TWO WAYS TO LOSE THE INPUT, and both have to retire it: spending
        // the claim, and running out of time. The window closes at the unlock
        // level (see submitReferral, which enforces it - this only saves the
        // user from typing a code that is going to be refused).
        //
        // The level is taken from levelProgress rather than the curve's own
        // copy of the rule so the two cannot disagree mid-session; the unlock
        // level itself comes from the published curve, so retuning it moves
        // the deadline everywhere at once.
        mainViewModel.hasUsedReferral.observe(viewLifecycleOwner) { renderReferralClaim() }
        mainViewModel.levelProgress.observe(viewLifecycleOwner) { renderReferralClaim() }
        mainViewModel.levelCurve.observe(viewLifecycleOwner) { renderReferralClaim() }

        mainViewModel.referralStats.observe(viewLifecycleOwner) { stats ->
            renderReferralStats(stats)
        }

        // Gold dot on Help & Support while a support reply is unread.
        SupportTicketStore.tickets.observe(viewLifecycleOwner) { tickets ->
            val unread = tickets.any { it.userUnread }
            binding.rowSupport.setCompoundDrawablesRelativeWithIntrinsicBounds(
                AppCompatResources.getDrawable(requireContext(), R.drawable.ic_row_help),
                null,
                AppCompatResources.getDrawable(
                    requireContext(), if (unread) R.drawable.ic_row_caret_dot else R.drawable.ic_row_caret
                ),
                null
            )
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
                is ReferralResult.WindowClosed ->
                    binding.referralInputLayout.error =
                        getString(R.string.error_referral_window_closed)
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
    /**
     * Whether this account can still enter somebody's code.
     *
     * The "already claimed" note is kept for the spent case only. Somebody
     * who simply ran out of time never claimed anything, and telling them
     * they did would send them looking for stars that were never paid - so
     * that case gets its own line.
     */
    private fun renderReferralClaim() {
        val used = mainViewModel.hasUsedReferral.value == true
        val unlockLevel = mainViewModel.levelCurve.value?.referralUnlockLevel ?: 0
        val level = mainViewModel.levelProgress.value?.level ?: 1
        // A curve that has not landed yet reads as "not expired": the server
        // refuses a late code anyway, and hiding the input on a guess would
        // retire it for somebody who is still eligible.
        val expired = !used && unlockLevel > 0 && level >= unlockLevel

        binding.referralClaimGroup.isVisible = !used && !expired
        binding.referralClaimedNote.isVisible = used
        binding.referralExpiredNote.isVisible = expired
    }

    /**
     * The card's two figures, the referrals row, and the rule behind the
     * "How will I get Stars?" link. Null stats (callable unavailable) reads as
     * zero, and the rule falls back to naming no numbers.
     */
    private fun renderReferralStats(stats: UserRepository.ReferralStats?) {
        val invited = stats?.invited ?: 0
        binding.referInvitedValue.text = WalletFormat.number(invited)
        binding.referStarsValue.text =
            WalletFormat.number(ReferralsFragment.earnedStars(stats))

        if (invited > 0) {
            binding.referralsRowTitle.text =
                resources.getQuantityString(R.plurals.profile_referrals_count, invited, invited)
            binding.referralsRowSub.setText(R.string.profile_referrals_open)
        } else {
            binding.referralsRowTitle.setText(R.string.profile_referrals_none)
            binding.referralsRowSub.setText(R.string.profile_referrals_none_sub)
        }

        if (stats != null) {
            // Every "50 ★" in the rule is a gold, bold figure.
            binding.referralRuleLine.setStarText(
                getString(
                    R.string.profile_referral_rule,
                    stats.levelReward,
                    stats.unlockLevel,
                    stats.redeemReward
                ),
                figureColor = R.color.stars_accent,
                centerStars = true
            )
        } else {
            binding.referralRuleLine.setText(R.string.profile_referral_rule_generic)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Set by Home's "Refer and earn" row so the tab opens on the invite
         * block rather than at the top of the account.
         */
        const val ARG_SCROLL_TO_REFERRAL = "scrollToReferral"
    }
}
