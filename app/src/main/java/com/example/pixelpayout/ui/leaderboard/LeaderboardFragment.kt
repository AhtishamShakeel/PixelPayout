package com.example.pixelpayout.ui.leaderboard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import androidx.annotation.DrawableRes
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
 * Redrawn on the leaderboard handoff: the stakes and the countdown, one list
 * that switches between the standings and the prize bands, and the caller's
 * own place pinned above the tab bar where it cannot scroll away.
 *
 * THE PODIUM HAS BEEN REMOVED. Three plinths spent most of a phone screen
 * saying what the first three rows of the table say anyway, and pushed the
 * rest of the board - the segments, the headings, rank four down to thirty -
 * below the fold on the screen whose entire job is the standings. The first
 * three places now sit in the same table as the other twenty-seven, at the
 * same height, distinguished by their metal rather than by their size.
 *
 * Every number on the screen is bound from [UserRepository.Leaderboard] - the
 * pool, the reset, the places, the prizes and the gaps. The prototype's
 * figures (a 5,000 pool, 24,247 players, a top 100) are not repeated anywhere
 * here: what the server pays is what the screen says.
 *
 * Reached from the card at the top of Earn. The full board - all thirty
 * places - is asked for once, here; that card holds only the caller's own
 * standing, which is all the preview fetch returns.
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
    /** The skeleton's breath, held so it can be stopped when data lands. */
    private var pulse: ObjectAnimator? = null

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

        // Drawn BEFORE the fetch, not after it. getLeaderboard is a callable
        // on a Cloud Function that is usually cold on the first call of the
        // day, and this screen used to sit blank for the whole of it - which
        // is the complaint that "the tournament loads after a delay". The
        // delay is real and server-side; what is fixed here is that the app
        // now draws its own furniture immediately and fills it in when the
        // answer lands, instead of waiting to be told what a leaderboard
        // looks like.
        showSkeleton()

        // The bands never change between deploys, so they are drawn from what
        // the board reports rather than fetched separately.
        loadBoard()
    }

    /**
     * The screen as it looks while the board is in flight.
     *
     * Everything that is KNOWN without the server is drawn for real: the
     * headings, the segmented control, the columns. Everything that is not -
     * the pool, the countdown, the thirty places, the caller's own rank - is
     * a placeholder in the exact position its value will occupy, so nothing
     * moves when the data arrives.
     *
     * The placeholder figures are em dashes rather than zeroes. "0 stars" and
     * "#0" are statements about a user's standing, and both of them are false
     * while we are still asking.
     */
    private fun showSkeleton() {
        val binding = _binding ?: return

        binding.leaderboardPool.text = PLACEHOLDER
        binding.leaderboardPoolSplit.text = getString(R.string.leaderboard_pool_loading)
        binding.leaderboardResets.text = PLACEHOLDER
        binding.leaderboardResetsAt.visibility = View.GONE

        binding.leaderboardSegments.visibility = View.VISIBLE
        binding.leaderboardColumns.visibility = View.VISIBLE
        binding.leaderboardEmpty.visibility = View.GONE
        binding.leaderboardRules.visibility = View.GONE

        binding.leaderboardBody.removeAllViews()
        repeat(SKELETON_ROWS) {
            binding.leaderboardBody.addView(
                layoutInflater.inflate(
                    R.layout.item_leaderboard_skeleton, binding.leaderboardBody, false
                )
            )
        }

        // The pinned card, in its unranked shape. It is the one part of the
        // screen that is never empty, so leaving it blank would read as the
        // card itself having failed.
        binding.leaderboardMyRank.text = PLACEHOLDER
        binding.leaderboardMyRankLabel.setText(R.string.leaderboard_you_label)
        binding.leaderboardMyXp.setText(R.string.leaderboard_loading_standing)
        binding.leaderboardMyGap.visibility = View.GONE
        binding.leaderboardClimbBar.visibility = View.GONE
        binding.leaderboardClimbCaption.visibility = View.GONE
        binding.leaderboardClimb.setText(R.string.leaderboard_play)

        // One slow breath across the whole list. Cheap - it animates alpha on
        // a single parent, not on each of the eight rows - and it is what
        // separates "still loading" from "loaded, and empty".
        pulse?.cancel()
        pulse = ObjectAnimator.ofFloat(
            binding.leaderboardBody, View.ALPHA, 1f, 0.45f
        ).apply {
            duration = SKELETON_PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** Stops the pulse and hands the list back its opacity. */
    private fun clearSkeleton() {
        pulse?.cancel()
        pulse = null
        _binding?.leaderboardBody?.alpha = 1f
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

        clearSkeleton()
        binding.leaderboardPool.text = PLACEHOLDER
        binding.leaderboardPoolSplit.text = getString(R.string.leaderboard_pool_loading)
        binding.leaderboardSegments.visibility = View.GONE
        binding.leaderboardColumns.visibility = View.GONE
        binding.leaderboardBody.removeAllViews()
        binding.leaderboardRules.visibility = View.GONE
        binding.leaderboardEmpty.visibility = View.VISIBLE
        binding.leaderboardEmpty.setText(R.string.leaderboard_load_failed)
    }

    private fun render(data: UserRepository.Leaderboard) {
        clearSkeleton()
        renderPool(data)
        renderResetCountdown()

        val hasEntries = data.entries.isNotEmpty()
        binding.leaderboardSegments.visibility = if (hasEntries) View.VISIBLE else View.GONE
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

    private fun selectSegment(next: Int) {
        if (segment == next) return
        segment = next

        // Restyled BEFORE the board is checked for. The control is on screen
        // during the skeleton now, so it can be pressed while the fetch is
        // still out - and a button that does not move when pressed reads as a
        // dead button. The choice is remembered either way: render() draws
        // whichever segment is selected when the data lands.
        styleSegment(binding.segmentStandings, next == SEGMENT_STANDINGS)
        styleSegment(binding.segmentPrizes, next == SEGMENT_PRIZES)

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

        // No heading is set here any more. There was one, and it repeated the
        // segment button the user had just pressed - "Standings" under
        // STANDINGS - with a meta beside it ("top 30", "2,450 a week) that the
        // prize-pool card at the top of the screen already states. The control
        // says which list this is; saying it twice more was noise.
        if (standings) renderStandings(data) else renderBands(data)
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

            // What carries the top three now that the podium does not: the
            // card tint, the avatar well and the name, all in the metal.
            //
            // Only the surfaces change - never the height, the padding or the
            // font size. The row has to stay one row of the same table, or
            // this becomes a podium again in a smaller costume.
            //
            // Rows are inflated fresh rather than recycled, so there is no
            // else branch resetting anything: an unmedalled row is whatever
            // item_leaderboard says it is.
            if (metal != 0) {
                row.setBackgroundResource(medalCard(entry.rank))
                avatar.setBackgroundResource(medalAvatar(entry.rank))
                avatar.setTextColor(color(metal))
                name.setTextColor(color(R.color.white))
            }

            // The caller's own line, picked out of thirty near-identical ones,
            // and it wins over the metal: a user scanning this list is looking
            // for themselves first and for the medals second. The numeral
            // stays in its metal either way - that is the one thing a medalled
            // row has earned, and violet would take it away.
            if (entry.isMe) {
                row.setBackgroundResource(R.drawable.bg_row_card_me)
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

    /** The card a medalled place sits on. Same shape as every other row. */
    @DrawableRes
    private fun medalCard(rank: Int): Int = when (rank) {
        1 -> R.drawable.bg_row_card_gold
        2 -> R.drawable.bg_row_card_silver
        else -> R.drawable.bg_row_card_bronze
    }

    /** Its avatar well, in the same metal. */
    @DrawableRes
    private fun medalAvatar(rank: Int): Int = when (rank) {
        1 -> R.drawable.bg_row_avatar_gold
        2 -> R.drawable.bg_row_avatar_silver
        else -> R.drawable.bg_row_avatar_bronze
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
        // An INFINITE animator holding a view from a destroyed hierarchy is
        // the usual way this leaks.
        pulse?.cancel()
        pulse = null
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

        /**
         * How many placeholder rows the skeleton draws.
         *
         * Eight rather than the full thirty: it fills the first screen, which
         * is all a skeleton has to do, and inflating thirty views that are
         * about to be thrown away is work done twice on the slowest devices.
         */
        private const val SKELETON_ROWS = 8
        private const val SKELETON_PULSE_MS = 900L

        /** Stands in for any figure the server has not sent yet. */
        private const val PLACEHOLDER = "—"
        private const val NO_INITIAL = "?"

        private val RESET_AT_FORMAT = SimpleDateFormat("EEEE HH:mm", Locale.getDefault())
    }
}
