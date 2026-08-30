package com.example.pixelpayout.ui.rewards

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.pixelpayout.ui.main.MainViewModel
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentLevelRewardsBinding
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
 * Costs no reads of its own beyond the one config document behind the
 * first-redeem rung, and that is cached on the view model for the life of the
 * process. The curve and the catalogue are already in memory: the curve is
 * fetched once at sign-in for Home's XP bar, and the catalogue is seeded from
 * Firestore's disk cache at start for Home's balance bar.
 */
class LevelRewardsFragment : Fragment() {

    private var _binding: FragmentLevelRewardsBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

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

        binding.levelRewardsBack.setOnClickListener { findNavController().popBackStack() }

        mainViewModel.loadFirstRedeemMinLevel()

        // Four independent sources, any of which can land last. Each one just
        // asks for a redraw rather than trying to sequence them - the render
        // below is written to cope with whichever are missing.
        mainViewModel.levelProgress.observe(viewLifecycleOwner) { render() }
        mainViewModel.levelCurve.observe(viewLifecycleOwner) { render() }
        mainViewModel.redemptionGames.observe(viewLifecycleOwner) { render() }
        mainViewModel.firstRedeemMinLevel.observe(viewLifecycleOwner) { render() }
    }

    private fun render() {
        val binding = _binding ?: return

        val progress = mainViewModel.levelProgress.value
        val curve = mainViewModel.levelCurve.value

        // Both are fetched once per session and either can still be in
        // flight. Until they are both here there is no level to draw and no
        // thresholds to place rungs on, so the ladder says so rather than
        // showing a page of blank cards.
        if (progress == null || curve == null) {
            binding.levelRewardsRows.removeAllViews()
            binding.levelRewardsRows.visibility = View.GONE
            binding.levelRewardsEmpty.visibility = View.VISIBLE
            binding.levelRewardsLadderNote.text = ""
            if (progress != null) renderHero(progress)
            return
        }

        renderHero(progress)

        val ladder = LevelLadder.build(
            res = resources,
            curve = curve,
            currentLevel = progress.level,
            firstRedeemMinLevel = mainViewModel.firstRedeemMinLevel.value,
            games = mainViewModel.redemptionGames.value.orEmpty()
        )

        binding.levelRewardsEarned.text =
            getString(R.string.level_rewards_stars, ladder.starsEarned)
        binding.levelRewardsAhead.text =
            getString(R.string.level_rewards_stars, ladder.starsAhead)
        binding.levelRewardsLadderNote.text = getString(
            R.string.level_rewards_ladder_note,
            ladder.rungCount,
            ladder.maxLevel
        )

        binding.levelRewardsEmpty.visibility =
            if (ladder.rungs.isEmpty()) View.VISIBLE else View.GONE
        binding.levelRewardsRows.visibility =
            if (ladder.rungs.isEmpty()) View.GONE else View.VISIBLE

        renderRungs(ladder.rungs)
    }

    private fun renderHero(progress: MainViewModel.LevelProgress) {
        binding.levelRewardsLevel.text = progress.level.toString()
        binding.levelRewardsHeroTitle.text =
            getString(R.string.level_card_title, progress.level)
        binding.levelRewardsHeroXp.text =
            getString(R.string.level_rewards_lifetime_xp, format(progress.totalXp))

        when {
            progress.isMaxLevel -> {
                binding.levelRewardsProgress.progress = 100
                binding.levelRewardsNextNote.setText(R.string.level_reached_max)
                binding.levelRewardsNextTarget.text = ""
            }

            // The curve can still be in flight, or have failed. The level is
            // known either way; the XP figures are not, so they stay blank
            // rather than reading as 0 / 0.
            progress.xpForNextLevel <= 0 -> {
                binding.levelRewardsProgress.progress = 0
                binding.levelRewardsNextNote.text = ""
                binding.levelRewardsNextTarget.text = ""
            }

            else -> {
                binding.levelRewardsProgress.progress =
                    (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
                binding.levelRewardsNextNote.text =
                    getString(R.string.level_rewards_next, progress.level + 1)
                binding.levelRewardsNextTarget.text = getString(
                    R.string.level_xp_ratio,
                    progress.xpIntoLevel,
                    progress.xpForNextLevel
                )
            }
        }
    }

    /**
     * Rows are inflated rather than adapted: the ladder is bounded by the
     * level curve - thirty rungs at the very most - so a RecyclerView and its
     * adapter would be more machinery than the content justifies.
     */
    private fun renderRungs(rungs: List<LevelLadder.Rung>) {
        val container = binding.levelRewardsRows
        container.removeAllViews()

        rungs.forEachIndexed { index, rung ->
            val row = layoutInflater.inflate(R.layout.item_level_rung, container, false)
            bindRung(row, rung, isLast = index == rungs.lastIndex)
            container.addView(row)
        }
    }

    private fun bindRung(row: View, rung: LevelLadder.Rung, isLast: Boolean) {
        val reached = rung.state == LevelLadder.State.REACHED
        val next = rung.state == LevelLadder.State.NEXT

        val badge = row.findViewById<TextView>(R.id.rungBadge)
        badge.text = rung.level.toString()
        badge.setBackgroundResource(
            when {
                reached -> R.drawable.bg_chip_violet
                next -> R.drawable.bg_rung_badge_next
                else -> R.drawable.bg_rung_badge_locked
            }
        )
        badge.setTextColor(
            color(if (reached || next) R.color.brand_violet_light else R.color.text_ghost)
        )

        // The rail stops being violet where the climb stops, so the boundary
        // between what is done and what is ahead is visible without reading a
        // single tag. The last rung has nothing below it to connect to.
        val rail = row.findViewById<View>(R.id.rungRail)
        rail.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
        rail.setBackgroundColor(
            color(if (reached) R.color.violet_tint_35 else R.color.stroke_strong)
        )

        row.findViewById<View>(R.id.rungCard).setBackgroundResource(
            when {
                next -> R.drawable.bg_first_redeem_card
                reached -> R.drawable.bg_summary_card
                else -> R.drawable.bg_rung_locked
            }
        )

        row.findViewById<TextView>(R.id.rungTitle).apply {
            text = getString(R.string.level_card_title, rung.level)
            setTextColor(color(if (reached || next) R.color.white else R.color.text_dim))
        }

        row.findViewById<TextView>(R.id.rungThreshold).text =
            getString(R.string.level_rewards_threshold, format(rung.xpRequired))

        row.findViewById<TextView>(R.id.rungTag).apply {
            when {
                reached -> {
                    setText(R.string.level_rewards_tag_reached)
                    setBackgroundResource(R.drawable.bg_status_done)
                    setTextColor(color(R.color.success))
                }

                next -> {
                    setText(R.string.level_rewards_tag_next)
                    setBackgroundResource(R.drawable.bg_tag_next)
                    setTextColor(color(R.color.brand_violet_light))
                }

                else -> {
                    setText(R.string.level_rewards_tag_locked)
                    setBackgroundResource(R.drawable.bg_tag_locked)
                    setTextColor(color(R.color.text_ghost))
                }
            }
        }

        val perks = row.findViewById<ViewGroup>(R.id.rungPerks)
        perks.removeAllViews()
        rung.perks.forEach { perk ->
            val perkRow = layoutInflater.inflate(R.layout.item_level_perk, perks, false)
            perkRow.findViewById<ImageView>(R.id.perkIcon).apply {
                // A locked rung shows the padlock rather than the perk's own
                // icon: the line is describing something not available yet,
                // and a gift box that is not yours reads as one that is.
                setImageResource(if (reached || next) perk.icon else R.drawable.ic_lock)
                imageTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    if (reached || next) R.color.brand_violet_light else R.color.text_ghost
                )
            }
            perkRow.findViewById<TextView>(R.id.perkText).apply {
                text = perk.text
                setTextColor(color(if (reached || next) R.color.text_soft else R.color.text_faint))
            }
            perks.addView(perkRow)
        }
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)

    /** Thousands separators - 15,000 XP is unreadable without them. */
    private fun format(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
