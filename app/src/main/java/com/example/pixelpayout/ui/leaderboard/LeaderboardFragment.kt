package com.example.pixelpayout.ui.leaderboard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.utils.ServerClock
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentLeaderboardBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The weekly leaderboard.
 *
 * A destination rather than the bottom sheet it replaces. The sheet had grown
 * past what a sheet is for, and it carried a bug that a destination cannot
 * have: it was shown imperatively, so a fast thumb could stack ten of them.
 *
 * Redrawn on the leaderboard handoff: the stakes and the countdown, a podium,
 * one list that switches between the standings and the prize bands, and the
 * caller's own place pinned above the tab bar where it cannot scroll away.
 *
 * Every number on the screen is bound from [UserRepository.Leaderboard] - the
 * pool, the reset, the places, the prizes and the gaps. The prototype's
 * figures (a 5,000 pool, 24,247 players, a top 100) are not repeated anywhere
 * here: what the server pays is what the screen says.
 *
 * The full board is asked for once, here. Home only ever holds the podium.
 */
class LeaderboardFragment : Fragment() {

    private var _binding: FragmentLeaderboardBinding? = null
    private val binding get() = _binding!!

    private val mainViewModel: MainViewModel by activityViewModels()

    private var board: UserRepository.Leaderboard? = null

    /** Which half of the segmented control is showing. */
    private var segment = SEGMENT_STANDINGS

    /**
     * The reset countdown, redrawn when the minute it shows actually changes
     * rather than once a second. The label is measured in days, hours and
     * minutes; a per-second timer would rewrite the same string sixty times
     * for nothing. Scheduling on the boundary rather than on a fixed minute
     * keeps it from lagging up to 59 seconds behind the truth.
     */
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderResetCountdown()
            ticker.postDelayed(this, nextTickDelay())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLeaderboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.leaderboardBack.setOnClickListener { findNavController().popBackStack() }
        binding.segmentStandings.setOnClickListener { selectSegment(SEGMENT_STANDINGS) }
        binding.segmentPrizes.setOnClickListener { selectSegment(SEGMENT_PRIZES) }
        binding.leaderboardClimb.setOnClickListener { openPlay() }

        // The bands never change between deploys, so they are drawn from what
        // the board reports rather than fetched separately.
        loadBoard()
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun loadBoard() {
        viewLifecycleOwner.lifecycleScope.launch {
            val full = mainViewModel.getFullLeaderboard()
            if (!isAdded || _binding == null) return@launch

            if (full == null) {
                showLoadFailure()
                return@launch
            }

            board = full
            render(full)
        }
    }

    /**
     * Nothing loaded. The pinned card stays, in its unranked state, so the
     * screen still has a floor rather than ending in dead space.
     */
    private fun showLoadFailure() {
        val binding = _binding ?: return

        binding.leaderboardPodium.visibility = View.GONE
        binding.leaderboardSegments.visibility = View.GONE
        binding.leaderboardListHeader.visibility = View.GONE
        binding.leaderboardColumns.visibility = View.GONE
        binding.leaderboardBody.removeAllViews()
        binding.leaderboardRules.visibility = View.GONE
        binding.leaderboardEmpty.visibility = View.VISIBLE
        binding.leaderboardEmpty.setText(R.string.leaderboard_load_failed)
    }

    private fun render(data: UserRepository.Leaderboard) {
        renderPool(data)
        renderResetCountdown()
        renderPodium(data)

        val hasEntries = data.entries.isNotEmpty()
        binding.leaderboardSegments.visibility = if (hasEntries) View.VISIBLE else View.GONE
        binding.leaderboardListHeader.visibility = if (hasEntries) View.VISIBLE else View.GONE
        binding.leaderboardEmpty.visibility = if (hasEntries) View.GONE else View.VISIBLE
        if (!hasEntries) binding.leaderboardColumns.visibility = View.GONE

        if (hasEntries) {
            renderSegment(data)
        } else {
            binding.leaderboardBody.removeAllViews()
            binding.leaderboardRules.visibility = View.GONE
        }

        renderMyPlace(data)
    }

    /** What is at stake this week, and when it stops being at stake. */
    private fun renderPool(data: UserRepository.Leaderboard) {
        val binding = _binding ?: return

        binding.leaderboardPool.text = formatCount(data.prizePool)
        binding.leaderboardPoolSplit.text =
            getString(R.string.leaderboard_pool_split, data.size)

        // The boundary is Monday 00:00 UTC, but it is shown in the reader's
        // own time - a countdown that ends at a wall-clock time they cannot
        // check is worse than no wall-clock time at all.
        if (data.weekEndsAtMillis > 0) {
            binding.leaderboardResetsAt.visibility = View.VISIBLE
            binding.leaderboardResetsAt.text = getString(
                R.string.leaderboard_resets_at,
                RESET_AT_FORMAT.format(Date(data.weekEndsAtMillis))
            )
        } else {
            binding.leaderboardResetsAt.visibility = View.GONE
        }
    }

    /**
     * The top three, bottom-aligned and lifted by place.
     *
     * Only the places that exist are drawn. A podium padded out with empty
     * plinths on a quiet week reads as a broken screen rather than a new one.
     */
    private fun renderPodium(data: UserRepository.Leaderboard) {
        val binding = _binding ?: return

        val places = data.entries.take(3)
        binding.leaderboardPodium.visibility =
            if (places.isEmpty()) View.GONE else View.VISIBLE

        bindPodium(
            binding.podium1, binding.podium1Avatar, binding.podium1Name,
            binding.podium1Xp, binding.podium1Prize, places.getOrNull(0)
        )
        bindPodium(
            binding.podium2, binding.podium2Avatar, binding.podium2Name,
            binding.podium2Xp, binding.podium2Prize, places.getOrNull(1)
        )
        bindPodium(
            binding.podium3, binding.podium3Avatar, binding.podium3Name,
            binding.podium3Xp, binding.podium3Prize, places.getOrNull(2)
        )
    }

    private fun bindPodium(
        column: View,
        avatar: TextView,
        name: TextView,
        xp: TextView,
        prize: TextView,
        entry: UserRepository.LeaderboardEntry?
    ) {
        if (entry == null) {
            column.visibility = View.GONE
            return
        }
        column.visibility = View.VISIBLE

        val label = displayName(entry)
        avatar.text = initialOf(label)
        name.text = label
        xp.text = getString(R.string.leaderboard_xp, formatCount(entry.xp))

        prize.visibility = if (entry.prize > 0) View.VISIBLE else View.GONE
        prize.text = getString(R.string.leaderboard_prize_star, formatCount(entry.prize))
    }

    private fun selectSegment(next: Int) {
        if (segment == next) return
        segment = next

        val data = board ?: return
        renderSegment(data)

        // A short fade, so the swap registers as one list changing rather
        // than as the screen jumping.
        binding.leaderboardBody.alpha = 0f
        binding.leaderboardBody.animate().alpha(1f).setDuration(SWAP_FADE_MS).start()
    }

    private fun renderSegment(data: UserRepository.Leaderboard) {
        val binding = _binding ?: return
        val standings = segment == SEGMENT_STANDINGS

        styleSegment(binding.segmentStandings, standings)
        styleSegment(binding.segmentPrizes, !standings)

        binding.leaderboardBody.removeAllViews()
        binding.leaderboardColumns.visibility = if (standings) View.VISIBLE else View.GONE
        binding.leaderboardRules.visibility = if (standings) View.GONE else View.VISIBLE

        if (standings) {
            binding.leaderboardListTitle.setText(R.string.leaderboard_standings_title)
            binding.leaderboardListMeta.text =
                getString(R.string.leaderboard_meta_standings, data.size)
            renderStandings(data)
        } else {
            binding.leaderboardListTitle.setText(R.string.leaderboard_prizes_title)
            binding.leaderboardListMeta.text =
                getString(R.string.leaderboard_meta_prizes, formatCount(data.prizePool))
            renderBands(data)
        }
    }

    private fun styleSegment(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
        view.setTextColor(color(if (selected) R.color.white else R.color.text_half))
    }

    private fun renderStandings(data: UserRepository.Leaderboard) {
        val binding = _binding ?: return

        // Where the money stops. Drawn at the place it actually falls, so the
        // reader does not have to count rows to find the cut.
        val lastPaid = data.entries.indexOfLast { it.prize > 0 }

        data.entries.forEachIndexed { index, entry ->
            val row = layoutInflater.inflate(
                R.layout.item_leaderboard, binding.leaderboardBody, false
            )
            val name = row.findViewById<TextView>(R.id.leaderboardItemName)
            val rank = row.findViewById<TextView>(R.id.leaderboardItemRank)
            val avatar = row.findViewById<TextView>(R.id.leaderboardItemAvatar)
            val prize = row.findViewById<TextView>(R.id.leaderboardItemPrize)

            val label = displayName(entry)
            // A bare numeral under a RANK heading: the column says what it is,
            // and a hash on every one of thirty rows is thirty hashes.
            rank.text = formatCount(entry.rank)
            name.text = label
            avatar.text = initialOf(label)
            row.findViewById<TextView>(R.id.leaderboardItemXp).text = formatCount(entry.xp)

            // The first three wear a laurel in their metal, numeral inside.
            val metal = metalFor(entry.rank)
            val laurel = row.findViewById<ImageView>(R.id.leaderboardItemLaurel)
            if (metal != 0) {
                laurel.visibility = View.VISIBLE
                laurel.setImageResource(R.drawable.ic_laurel)
                laurel.setColorFilter(color(metal))
                rank.setTextColor(color(metal))
            } else {
                laurel.visibility = View.GONE
            }

            if (entry.prize > 0) {
                prize.text = getString(R.string.leaderboard_prize_star, formatCount(entry.prize))
                prize.setTextColor(color(R.color.gold))
            } else {
                prize.setText(R.string.leaderboard_no_prize_dash)
                prize.setTextColor(color(R.color.text_trace))
            }

            // The caller's own line, picked out of thirty near-identical ones.
            if (entry.isMe) {
                row.setBackgroundResource(R.drawable.bg_row_card_me)
                // A medalled row keeps its metal; violet would take away the
                // one thing that row has earned.
                if (metal == 0) rank.setTextColor(color(R.color.brand_violet_light))
                name.setTextColor(color(R.color.white))
                avatar.setBackgroundResource(R.drawable.bg_row_avatar_me)
                avatar.setTextColor(color(R.color.primary_text))
            }

            binding.leaderboardBody.addView(row)

            if (index == lastPaid) {
                addPrizeCutoff(entry.rank)
            }
        }
    }

    private fun addPrizeCutoff(lastPaidRank: Int) {
        val binding = _binding ?: return
        val rule = layoutInflater.inflate(
            R.layout.item_prize_cutoff, binding.leaderboardBody, false
        )
        rule.findViewById<TextView>(R.id.leaderboardCutoffLabel).text =
            getString(R.string.leaderboard_prize_zone_ends, lastPaidRank)
        binding.leaderboardBody.addView(rule)
    }

    /**
     * The prize bands, collapsed from the entries themselves.
     *
     * Derived rather than sent as its own table: the board already carries a
     * prize on every place, so grouping consecutive equal values reproduces
     * the bands exactly and cannot disagree with what the rows show.
     */
    private fun renderBands(data: UserRepository.Leaderboard) {
        if (data.entries.isEmpty()) return

        var start = data.entries.first()
        var previous = start

        data.entries.drop(1).forEach { entry ->
            if (entry.prize != previous.prize) {
                addBand(start.rank, previous.rank, previous.prize)
                start = entry
            }
            previous = entry
        }
        addBand(start.rank, previous.rank, previous.prize)
    }

    private fun addBand(from: Int, to: Int, prize: Int) {
        // A band paying nothing is not a prize band; the cut is already drawn
        // on the standings, and repeating it here as a row of zeroes would
        // read as a bug in the prize table.
        if (prize <= 0) return

        val binding = _binding ?: return
        val row = layoutInflater.inflate(
            R.layout.item_leaderboard_band, binding.leaderboardBody, false
        )
        val places = to - from + 1

        row.findViewById<TextView>(R.id.leaderboardBandRange).text = if (from == to) {
            getString(R.string.leaderboard_band_single, from)
        } else {
            getString(R.string.leaderboard_band_range, from, to)
        }
        row.findViewById<TextView>(R.id.leaderboardBandEach).text = if (places == 1) {
            getString(R.string.leaderboard_band_one_winner)
        } else {
            getString(R.string.leaderboard_band_each, prize)
        }
        row.findViewById<TextView>(R.id.leaderboardBandTotal).text =
            getString(R.string.leaderboard_prize_star, formatCount(places * prize))

        binding.leaderboardBody.addView(row)
    }

    /**
     * The pinned card: where the caller stands, what it is worth, and what
     * the next step up costs.
     *
     * Three states, and none of them invents a figure. Unranked has no gap to
     * report and so reports none; inside the prizes, the bar measures the
     * climb to the next band up; outside them, it measures the climb to the
     * last paying place currently on the board.
     */
    private fun renderMyPlace(data: UserRepository.Leaderboard) {
        val binding = _binding ?: return
        val ranked = data.isRanked

        binding.leaderboardMyRank.text = if (ranked) {
            getString(R.string.leaderboard_rank, formatCount(data.myRank))
        } else {
            UNRANKED_RANK
        }
        binding.leaderboardMyRankLabel.setText(
            if (ranked) R.string.leaderboard_you_label else R.string.leaderboard_unranked_label
        )
        binding.leaderboardMyXp.text = if (ranked) {
            getString(R.string.leaderboard_xp_this_week, formatCount(data.myXp))
        } else {
            getString(R.string.leaderboard_play_to_enter)
        }
        binding.leaderboardClimb.setText(
            if (ranked) R.string.leaderboard_climb else R.string.leaderboard_play
        )

        renderGapLine(data, ranked)
        renderClimbBar(data, ranked)
    }

    private fun renderGapLine(data: UserRepository.Leaderboard, ranked: Boolean) {
        val binding = _binding ?: return
        val gap = binding.leaderboardMyGap

        // The place directly ahead, when the board happens to show it. A
        // caller below the last visible place has no known neighbour, so they
        // are told the target instead of a made-up distance.
        val ahead = data.entries.firstOrNull { it.rank == data.myRank - 1 }

        when {
            !ranked -> gap.visibility = View.GONE

            data.myPrize > 0 -> {
                gap.visibility = View.VISIBLE
                gap.text = getString(R.string.leaderboard_your_prize, data.myPrize)
                gap.setTextColor(color(R.color.gold))
            }

            ahead != null -> {
                gap.visibility = View.VISIBLE
                gap.text = getString(
                    R.string.leaderboard_gap_behind,
                    formatCount((ahead.xp - data.myXp).coerceAtLeast(0)),
                    formatCount(ahead.rank)
                )
                gap.setTextColor(color(R.color.text_faint))
            }

            else -> {
                gap.visibility = View.VISIBLE
                gap.text = getString(R.string.leaderboard_no_prize, data.size)
                gap.setTextColor(color(R.color.text_faint))
            }
        }
    }

    private fun renderClimbBar(data: UserRepository.Leaderboard, ranked: Boolean) {
        val binding = _binding ?: return

        // The edge of the prize zone as it stands right now: the XP of the
        // lowest-ranked place that is still being paid.
        val zoneEdge = data.entries.lastOrNull { it.prize > 0 }?.xp ?: 0

        // The cheapest place that pays more than the caller is paid now.
        val nextBand = data.entries.filter { it.prize > data.myPrize }.minByOrNull { it.xp }

        val target = if (data.myPrize > 0) nextBand?.xp ?: 0 else zoneEdge
        val show = ranked && target > 0

        binding.leaderboardClimbBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.leaderboardClimbCaption.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            // Top of the board, or nothing on it to climb toward.
            if (ranked && data.myPrize > 0) {
                binding.leaderboardClimbCaption.visibility = View.VISIBLE
                binding.leaderboardToPrize.setText(R.string.leaderboard_top_of_board)
                binding.leaderboardZoneTarget.text =
                    getString(R.string.leaderboard_zone_target, data.size)
            }
            return
        }

        val needed = (target - data.myXp + 1).coerceAtLeast(0)
        binding.leaderboardToPrize.text = if (data.myPrize > 0) {
            getString(R.string.leaderboard_to_next_band, formatCount(needed))
        } else {
            getString(R.string.leaderboard_to_prize_zone, formatCount(needed))
        }
        binding.leaderboardZoneTarget.text =
            getString(R.string.leaderboard_zone_target, data.size)

        // Floored at a few percent so the bar reads as a bar rather than as
        // an empty track, which looks like a rendering failure.
        binding.leaderboardClimbBar.progress =
            ((data.myXp.toLong() * 100 / target).toInt()).coerceIn(MIN_BAR_PERCENT, 100)
    }

    /**
     * Time until the standings reset.
     *
     * Against the server clock, since weekEndsAt is the server's boundary - on
     * a device with a wrong clock, device time would count down to the wrong
     * moment or straight past it.
     */
    private fun renderResetCountdown() {
        val binding = _binding ?: return
        val endsAt = board?.weekEndsAtMillis ?: return

        val remaining = endsAt - ServerClock.now()
        binding.leaderboardResets.text = if (remaining <= 0) {
            getString(R.string.leaderboard_resets_now)
        } else {
            remainingLabel(remaining)
        }
    }

    private fun remainingLabel(remainingMs: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60

        return when {
            days > 0 -> getString(R.string.leaderboard_days_hours, days, hours, minutes)
            hours > 0 -> getString(R.string.leaderboard_hours_minutes, hours, minutes)
            else -> getString(R.string.leaderboard_minutes, minutes.coerceAtLeast(1))
        }
    }

    /** How long until the minute shown in the countdown changes. */
    private fun nextTickDelay(): Long {
        val endsAt = board?.weekEndsAtMillis ?: return MINUTE_MS
        val remaining = endsAt - ServerClock.now()
        if (remaining <= 0) return MINUTE_MS
        return (remaining % MINUTE_MS).coerceAtLeast(SECOND_MS)
    }

    /**
     * The one tappable thing on the pinned card. Guarded on the current
     * destination rather than a boolean: navigating is asynchronous, so a
     * fast thumb could otherwise push several copies of Play onto the stack.
     */
    private fun openPlay() {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.leaderboardFragment) return

        val navOptions = NavOptions.Builder()
            .setEnterAnim(R.anim.fade_in)
            .setExitAnim(R.anim.fade_out)
            .setPopEnterAnim(R.anim.fade_in)
            .setPopExitAnim(R.anim.fade_out)
            .build()

        try {
            controller.navigate(R.id.navigation_play, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to play: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    /** The metal a place is worth, or 0 for the ranks that get a numeral. */
    @ColorRes
    private fun metalFor(rank: Int): Int = when (rank) {
        1 -> R.color.gold
        2 -> R.color.silver
        3 -> R.color.bronze
        else -> 0
    }

    private fun displayName(entry: UserRepository.LeaderboardEntry): String =
        if (entry.isMe) getString(R.string.leaderboard_you) else entry.name

    /** The initial standing in for an avatar. Names arrive already masked. */
    private fun initialOf(name: String): String =
        name.trim().firstOrNull()?.uppercase(Locale.US) ?: NO_INITIAL

    private fun color(@ColorRes id: Int): Int =
        ContextCompat.getColor(requireContext(), id)

    private fun formatCount(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    override fun onDestroyView() {
        super.onDestroyView()
        ticker.removeCallbacks(tick)
        _binding = null
    }

    companion object {
        private const val SEGMENT_STANDINGS = 0
        private const val SEGMENT_PRIZES = 1

        private const val SWAP_FADE_MS = 150L
        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000L

        /** Enough of a sliver that an empty bar still reads as a bar. */
        private const val MIN_BAR_PERCENT = 6

        private const val UNRANKED_RANK = "—"
        private const val NO_INITIAL = "?"

        private val RESET_AT_FORMAT = SimpleDateFormat("EEEE HH:mm", Locale.getDefault())
    }
}
