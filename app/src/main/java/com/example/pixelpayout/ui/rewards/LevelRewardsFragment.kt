package com.example.pixelpayout.ui.rewards

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.AdManager
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentLevelRewardsBinding
import com.pixelpayout.databinding.ViewLevelRewardsFooterBinding
import com.pixelpayout.databinding.ViewLevelRewardsHeaderBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * What every level is worth, and how far off the next one is.
 *
 * Opened from the level card on Home, which until now said how much XP the
 * next level costs without ever saying what it buys.
 *
 * THIS IS ALSO WHERE LEVEL STARS ARE COLLECTED. Crossing a level earns its
 * bonus; awardReward writes it LOCKED and queues the level on the user
 * document, and a rewarded ad releases it here - one level per ad, lowest
 * first, so somebody who climbed to 5 without claiming works up through 2, 3
 * and 4 to get there. The order is the server's (claimLevelReward drains its
 * own queue and ignores anything the client might name); this screen only has
 * to show which one is next and why the others are waiting.
 *
 * The rungs remain a readout - the claim lives in the header card, above the
 * ladder rather than on it, because there is only ever ONE claimable level
 * and a button per rung would imply a choice between them.
 *
 * STILL COSTS NO READS AT ALL. The pending queue arrives on the user snapshot
 * the app already listens to, so knowing there is something to claim is free;
 * only the claim itself is a call.
 *
 * COSTS NO READS AT ALL. The curve is fetched once per process by
 * LevelCurveStore, the catalogue is seeded from Firestore's disk cache at
 * start, and the first-redeem level is memoised for the process by
 * getFirstRedeemMinLevel - which reads disk before it reads the network, so
 * opening this screen does not wait on a round trip the way it used to.
 *
 * THE SCREEN IS ONE RECYCLERVIEW: a header row, the rungs, a footer row,
 * stitched together by a ConcatAdapter. See LevelRungAdapter for why the
 * rungs stopped being inflated into a LinearLayout - in short, all
 * twenty-nine of them were built on the main thread between the tap and the
 * first frame, and that was the delay.
 */
class LevelRewardsFragment : Fragment() {

    private var _binding: FragmentLevelRewardsBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    /**
     * Kept across view recreations so coming back to the screen redraws from
     * the rungs it already had rather than from an empty list.
     */
    private val rungAdapter = LevelRungAdapter()

    private var headerAdapter: SingleRowAdapter<ViewLevelRewardsHeaderBinding>? = null
    private var footerAdapter: SingleRowAdapter<ViewLevelRewardsFooterBinding>? = null

    /**
     * What the header and footer should currently show.
     *
     * They are list rows now, so they are drawn when RecyclerView asks rather
     * than when new data arrives - which means the answer has to be readable
     * at any moment, including after the header has scrolled off and come
     * back. [render] updates this and then asks for a redraw.
     */
    private var screen: Screen = Screen(null, null)

    /**
     * True from the moment the ad starts until the claim call returns.
     *
     * Guards the whole round trip rather than just the network call, because
     * the ad is the long part: without it a second tap during playback would
     * queue a second claim and release two levels for one ad.
     */
    private var claimInFlight = false

    private data class Screen(
        val progress: MainViewModel.LevelProgress?,
        val ladder: LevelLadder.Ladder?
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLevelRewardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val header = SingleRowAdapter(
            inflate = { inflater, parent ->
                ViewLevelRewardsHeaderBinding.inflate(inflater, parent, false)
            },
            bind = ::bindHeader
        )
        val footer = SingleRowAdapter(
            inflate = { inflater, parent ->
                ViewLevelRewardsFooterBinding.inflate(inflater, parent, false)
            },
            bind = ::bindFooter
        )
        headerAdapter = header
        footerAdapter = footer

        binding.levelRewardsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            // The list fills the screen whatever the curve says, so its own
            // size never depends on its contents.
            setHasFixedSize(true)
            // The rungs used to appear with no animation at all, and a
            // twenty-nine item insert animation on open would be a new kind
            // of slow rather than a fix for the old one.
            itemAnimator = null
            adapter = ConcatAdapter(header, rungAdapter, footer)
        }

        mainViewModel.loadFirstRedeemMinLevel()

        // Warms the pool for the claim button. A no-op when an ad is already
        // ready or the pacer says wait, so opening the screen repeatedly costs
        // nothing - see AdManager.loadRewardedAd.
        AdManager.getInstance().loadRewardedAd(requireContext())

        // Four independent sources, any of which can land last. Each one just
        // asks for a redraw rather than trying to sequence them - the render
        // below is written to cope with whichever are missing, and both the
        // header rebind and submitList are no-ops when nothing changed.
        mainViewModel.levelProgress.observe(viewLifecycleOwner) { render() }
        mainViewModel.levelCurve.observe(viewLifecycleOwner) { render() }
        mainViewModel.redemptionGames.observe(viewLifecycleOwner) { render() }
        mainViewModel.firstRedeemMinLevel.observe(viewLifecycleOwner) { render() }
    }

    private fun render() {
        if (_binding == null) return

        val progress = mainViewModel.levelProgress.value
        val curve = mainViewModel.levelCurve.value

        // Both are fetched once per session and either can still be in
        // flight. Until they are both here there is no level to draw and no
        // thresholds to place rungs on, so the ladder says so rather than
        // showing a page of blank cards.
        val ladder = if (progress == null || curve == null) {
            null
        } else {
            LevelLadder.build(
                res = resources,
                curve = curve,
                currentLevel = progress.level,
                firstRedeemMinLevel = mainViewModel.firstRedeemMinLevel.value,
                games = mainViewModel.redemptionGames.value.orEmpty(),
                pendingLevels = progress.pendingLevelRewards
            )
        }

        screen = Screen(progress, ladder)
        headerAdapter?.redraw()
        footerAdapter?.redraw()

        // DiffUtil's work, not ours: an identical ladder dispatches no
        // changes, so the four redraws on the way in cost one layout.
        rungAdapter.submitList(ladder?.rungs.orEmpty())
    }

    private fun bindHeader(header: ViewLevelRewardsHeaderBinding) {
        header.levelRewardsBack.setOnClickListener { findNavController().popBackStack() }

        screen.progress?.let { renderHero(header, it) }

        val ladder = screen.ladder
        if (ladder == null) {
            header.levelClaimCard.visibility = View.GONE
            header.levelRewardsEarned.text = ""
            header.levelRewardsAhead.text = ""
            header.levelRewardsLadderNote.text = ""
            return
        }

        renderClaim(header, ladder)

        header.levelRewardsEarned.text =
            getString(R.string.level_rewards_stars, ladder.starsEarned)
        header.levelRewardsAhead.text =
            getString(R.string.level_rewards_stars, ladder.starsAhead)
        header.levelRewardsLadderNote.text = getString(
            R.string.level_rewards_ladder_note,
            ladder.rungCount,
            ladder.maxLevel
        )
    }

    /**
     * The claim card, which exists only while something is owed.
     *
     * Driven entirely by the ladder, which is derived from the user document -
     * so it appears the moment a level is crossed and disappears the moment
     * the queue empties, without this screen tracking anything of its own.
     * That matters because the level-up it is reacting to happened in another
     * activity entirely, on a results screen this fragment never saw.
     */
    private fun renderClaim(
        header: ViewLevelRewardsHeaderBinding,
        ladder: LevelLadder.Ladder
    ) {
        val level = ladder.nextClaimLevel
        if (level == null) {
            header.levelClaimCard.visibility = View.GONE
            return
        }

        header.levelClaimCard.visibility = View.VISIBLE
        header.levelClaimTitle.text = getString(R.string.level_claim_title, level)
        header.levelClaimAmount.text =
            getString(R.string.level_claim_amount, ladder.nextClaimStars)

        // How many are BEHIND this one, so the number promises what is still
        // coming rather than counting the card the user is looking at.
        val behind = ladder.pendingCount - 1
        header.levelClaimNote.text = if (behind > 0) {
            resources.getQuantityString(R.plurals.level_claim_note_queued, behind, behind)
        } else {
            getString(R.string.level_claim_note_single)
        }

        header.levelClaimButton.apply {
            isEnabled = !claimInFlight
            setText(
                if (claimInFlight) R.string.level_claim_working else R.string.level_claim_watch
            )
            setOnClickListener { playAdThenClaim() }
        }
    }

    /**
     * The ad, then the claim.
     *
     * Fired from the REWARD callback rather than from dismissal, and the
     * difference is the point: onRewarded is the moment AdMob says the ad was
     * genuinely watched, and an ad can be dismissed without it ever firing.
     *
     * If no ad plays, NOTHING is claimed - unlike the daily streak, where the
     * ad gates only the payout and the streak itself has to advance either
     * way. Here the stars are the whole transaction and nothing is lost by
     * waiting: the level stays queued, the card stays on screen, and the user
     * can try again when fill comes back.
     */
    private fun playAdThenClaim() {
        if (claimInFlight) return
        claimInFlight = true
        headerAdapter?.redraw()

        // Held rather than looked up in the callbacks. Those fire after a
        // full-screen ad has come and gone, and requireActivity() from a
        // fragment that was detached in the meantime throws - on the one path
        // where a reward has already been earned and must not be dropped.
        val host = requireActivity()

        var earned = false
        AdManager.getInstance().showRewardedAdWhenReady(
            activity = host,
            onRewarded = { earned = true },
            onAdClosed = {
                if (earned) {
                    submitClaim(host)
                } else {
                    // Closed early. Nothing was earned for it, so the level
                    // stays queued and the button comes back.
                    claimInFlight = false
                    headerAdapter?.redraw()
                }
            },
            onAdFailedToShow = {
                claimInFlight = false
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.level_claim_ad_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                headerAdapter?.redraw()
            }
        )
    }

    private fun submitClaim(host: FragmentActivity) {
        // Deliberately the ACTIVITY's scope rather than the view's. The ad has
        // already been watched by the time this runs, so the claim is owed;
        // backing out of the screen mid-call must not cancel it.
        host.lifecycleScope.launch {
            val result = mainViewModel.claimLevelReward()
            claimInFlight = false

            if (isAdded) {
                when (result) {
                    is UserRepository.LevelRewardResult.Claimed -> Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.level_claim_toast,
                            result.level,
                            result.pointsAwarded
                        ),
                        Toast.LENGTH_LONG
                    ).show()

                    // A stale screen or a double tap, not a failure. Said
                    // plainly and quietly; the snapshot corrects the card.
                    is UserRepository.LevelRewardResult.NothingToClaim -> Toast.makeText(
                        requireContext(),
                        R.string.level_claim_nothing,
                        Toast.LENGTH_SHORT
                    ).show()

                    is UserRepository.LevelRewardResult.Error -> Toast.makeText(
                        requireContext(),
                        result.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // The user document's snapshot listener repaints the queue and the
            // balance; this only restores the button in the cases where the
            // queue did not change.
            if (_binding != null) render()
        }
    }

    /**
     * The footer carries the stand-in for the rows, so it is shown in exactly
     * the cases where there are none: the curve has not landed, or it landed
     * with nothing on it.
     */
    private fun bindFooter(footer: ViewLevelRewardsFooterBinding) {
        footer.levelRewardsEmpty.visibility =
            if (screen.ladder?.rungs.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderHero(
        header: ViewLevelRewardsHeaderBinding,
        progress: MainViewModel.LevelProgress
    ) {
        header.levelRewardsLevel.text = progress.level.toString()
        header.levelRewardsHeroTitle.text =
            getString(R.string.level_card_title, progress.level)
        header.levelRewardsHeroXp.text =
            getString(R.string.level_rewards_lifetime_xp, format(progress.totalXp))

        when {
            progress.isMaxLevel -> {
                header.levelRewardsProgress.progress = 100
                header.levelRewardsNextNote.setText(R.string.level_reached_max)
                header.levelRewardsNextTarget.text = ""
            }

            // The curve can still be in flight, or have failed. The level is
            // known either way; the XP figures are not, so they stay blank
            // rather than reading as 0 / 0.
            progress.xpForNextLevel <= 0 -> {
                header.levelRewardsProgress.progress = 0
                header.levelRewardsNextNote.text = ""
                header.levelRewardsNextTarget.text = ""
            }

            else -> {
                header.levelRewardsProgress.progress =
                    (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
                header.levelRewardsNextNote.text =
                    getString(R.string.level_rewards_next, progress.level + 1)
                header.levelRewardsNextTarget.text = getString(
                    R.string.level_xp_ratio,
                    progress.xpIntoLevel,
                    progress.xpForNextLevel
                )
            }
        }
    }

    /** Thousands separators - 15,000 XP is unreadable without them. */
    private fun format(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    override fun onDestroyView() {
        super.onDestroyView()
        // The header and footer adapters close over this fragment's binder
        // methods, so they go with the view that owns them.
        binding.levelRewardsList.adapter = null
        headerAdapter = null
        footerAdapter = null
        _binding = null
    }
}
