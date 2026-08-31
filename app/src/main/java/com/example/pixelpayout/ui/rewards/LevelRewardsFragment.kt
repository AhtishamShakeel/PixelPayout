package com.example.pixelpayout.ui.rewards

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pixelpayout.ui.main.MainViewModel
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentLevelRewardsBinding
import com.pixelpayout.databinding.ViewLevelRewardsFooterBinding
import com.pixelpayout.databinding.ViewLevelRewardsHeaderBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * What every level is worth, and how far off the next one is.
 *
 * Opened from the level card on Home, which until now said how much XP the
 * next level costs without ever saying what it buys.
 *
 * NOTHING IS CLAIMED HERE. Milestone stars are paid by awardReward in the
 * same transaction that crosses the level, so the handoff's "Claim reward"
 * button has no work to do in this economy - a button that only looked like
 * it worked would be worse than none. The rungs are a readout.
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
                games = mainViewModel.redemptionGames.value.orEmpty()
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
            header.levelRewardsEarned.text = ""
            header.levelRewardsAhead.text = ""
            header.levelRewardsLadderNote.text = ""
            return
        }

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
