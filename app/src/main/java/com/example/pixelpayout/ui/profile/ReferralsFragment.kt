package com.example.pixelpayout.ui.profile

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.redemption.WalletFormat
import com.example.pixelpayout.utils.setStarText
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentReferralsBinding

/**
 * Who used this account's code, and how far each of them has got.
 *
 * Moved off Profile, which now shows only a one-line summary row that opens
 * this. The stats come from the same activity-scoped referralStats Profile
 * refreshes, so opening the page costs no extra call; it is refreshed again
 * here on resume because it aggregates other people's documents and has no
 * snapshot to listen to.
 */
class ReferralsFragment : Fragment() {

    private var _binding: FragmentReferralsBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    private val inviteeAdapter = InviteeAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReferralsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.referralsBack.setOnClickListener { findNavController().popBackStack() }

        binding.inviteesRecyclerView.adapter = inviteeAdapter
        binding.inviteesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.inviteesRecyclerView.isNestedScrollingEnabled = false

        // The funnel counts people, not stars, so its icons are violet.
        val violet = ColorStateList.valueOf(requireContext().getColor(R.color.brand_violet_light))
        binding.funnelInvited.statLabel.setText(R.string.profile_funnel_invited)
        binding.funnelInvited.statIcon.setImageResource(R.drawable.ic_users)
        binding.funnelInvited.statIcon.imageTintList = violet
        binding.funnelQualified.statLabel.setText(R.string.profile_funnel_qualified)
        binding.funnelQualified.statIcon.setImageResource(R.drawable.ic_shield_check)
        binding.funnelQualified.statIcon.imageTintList = violet
        binding.funnelPaid.statLabel.setText(R.string.profile_funnel_paid)
        binding.funnelPaid.statIcon.setImageResource(R.drawable.ic_check)
        binding.funnelPaid.statIcon.imageTintList = violet

        mainViewModel.referralStats.observe(viewLifecycleOwner) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.refreshReferralStats()
    }

    /**
     * Null stats means the callable is unavailable - most often not deployed
     * yet. The page then shows zeros and the empty state rather than an error
     * nobody using the app can act on.
     */
    private fun render(stats: UserRepository.ReferralStats?) {
        val b = _binding ?: return
        val invitees = stats?.invitees.orEmpty()

        inviteeAdapter.submitList(invitees)
        inviteeAdapter.updateRewards(stats?.levelReward ?: 0, stats?.redeemReward ?: 0)
        b.inviteesRecyclerView.isVisible = invitees.isNotEmpty()
        b.inviteesEmpty.isVisible = invitees.isEmpty()

        b.funnelInvited.statValue.text = (stats?.invited ?: 0).toString()
        b.funnelQualified.statValue.text = (stats?.qualified ?: 0).toString()
        b.funnelPaid.statValue.text = (stats?.levelPaid ?: 0).toString()

        val earned = WalletFormat.number(earnedStars(stats))
        b.profileEarnedLabel.setStarText(
            getString(R.string.profile_earned, earned),
            emphasise = earned,
            emphasisColor = R.color.stars_accent
        )
        b.profileEarnedLabel.isVisible = stats != null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Both milestones, counted separately and added. */
        fun earnedStars(stats: UserRepository.ReferralStats?): Int =
            if (stats == null) 0
            else stats.levelPaid * stats.levelReward + stats.redeemPaid * stats.redeemReward
    }
}
