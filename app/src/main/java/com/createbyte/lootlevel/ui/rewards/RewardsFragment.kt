package com.createbyte.lootlevel.ui.rewards

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.createbyte.lootlevel.data.model.OfferwallEntry
import com.createbyte.lootlevel.data.repository.OfferwallCatalogStore
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.ui.main.MainViewModel
import com.createbyte.lootlevel.utils.ServerClock
import com.createbyte.lootlevel.utils.TapjoyOfferwall
import com.createbyte.lootlevel.utils.setStarText
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.FragmentRewardsBinding
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Earn: the weekly leaderboard, then the offerwall list.
 *
 * THE LEADERBOARD CARD MOVED HERE FROM HOME. It is a way to earn, which is
 * what this screen is a list of, and on Home it was one card among nine. The
 * consequence worth knowing about is in MainActivity: this tab used to be
 * hidden whenever the offerwall catalogue was empty, and it no longer is,
 * because it now has something behind it that does not depend on a network
 * approving us.
 *
 * The board itself lives in MainViewModel, shared with the leaderboard
 * screen, so this card and the screen it opens cannot disagree about where
 * the user stands - and the throttle in refreshLeaderboard means visiting
 * this tab repeatedly does not re-read thirty user documents each time.
 *
 * REPLACES THE TAPJOY WIRING THAT USED TO LIVE HERE, which was placeholder
 * grade in three ways worth recording rather than quietly deleting:
 * `onRewardRequest` only logged, so no completion ever credited anybody;
 * `onRequestSuccess` called `requestContent()` again, re-entering the
 * request it was reporting on; and `Tapjoy.connect` ran on every visit to
 * this screen while the placement held an Activity reference across
 * rotation. The AAR stays in the build - removing it is work with no payoff
 * while Tapjoy's post-ironSource status is unresolved - but nothing calls
 * into it now, and if it is ever approved it should come back through the
 * catalogue below rather than as bespoke code on this screen.
 *
 * Everything here is driven by `config/offerwallWalls`, so approving a
 * network is a document rather than a release.
 */
class RewardsFragment : Fragment() {

    private var _binding: FragmentRewardsBinding? = null
    private val binding get() = _binding!!

    private val userRepository = UserRepository()

    private val mainViewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRewardsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Row spacing is a margin on the item, not an ItemDecoration:
        // SpacingItemDecoration casts to StaggeredGridLayoutManager.LayoutParams
        // and would throw under the linear manager this list uses.
        binding.offerwallList.layoutManager = LinearLayoutManager(requireContext())
        // Entry is free and automatic, so the button and the card both just
        // open the board.
        binding.viewTournament.setOnClickListener { openLeaderboard() }
        binding.leaderboardRow.setOnClickListener { openLeaderboard() }

        // The live view, not the raw board: the caller's XP and level follow
        // the user snapshot, so a finished quiz shows here without waiting out
        // the board's refresh throttle.
        mainViewModel.tournament.observe(viewLifecycleOwner) { renderLeaderboard(it) }
        // The locked panel quotes the XP still needed, which moves with XP and
        // needs the curve to state at all.
        mainViewModel.levelProgress.observe(viewLifecycleOwner) {
            renderLeaderboard(mainViewModel.tournament.value)
        }
        mainViewModel.levelCurve.observe(viewLifecycleOwner) {
            renderLeaderboard(mainViewModel.tournament.value)
        }
        observeWalls()
    }

    override fun onResume() {
        super.onResume()
        // Throttled inside the view model, so returning to this tab a dozen
        // times does not cost a dozen reads of the board.
        mainViewModel.refreshLeaderboard()
        countdownTick.run()
    }

    override fun onPause() {
        super.onPause()
        tickHandler.removeCallbacks(countdownTick)
    }

    private val tickHandler = Handler(Looper.getMainLooper())

    /**
     * Redraws the card from the board already held - no read - once a minute
     * while the tab is on screen, so the deadline line counts down.
     */
    private val countdownTick = object : Runnable {
        override fun run() {
            tickHandler.removeCallbacks(this)
            renderLeaderboard(mainViewModel.tournament.value)
            tickHandler.postDelayed(this, TimeUnit.MINUTES.toMillis(1))
        }
    }

    /**
     * The weekly board, as one card above the offers.
     *
     * THE CARD IS NEVER HIDDEN. It used to be, until getLeaderboard answered -
     * and that callable is a Cloud Function which is cold on the first call of
     * the day, so the tab opened without the card and it dropped in a second
     * or two later, shoving the offer list down as it landed. The layout now
     * ships with the card drawn and em dashes where the four figures go; this
     * only fills them in.
     *
     * A null board means "not known yet", which is why the placeholders stay
     * rather than being replaced with zeroes. "#0" and "0 stars" are claims
     * about where the user stands, and both are false while we are asking.
     *
     * The personal panel shows this week's XP; the adjacent panel shows
     * the total prize pool. Both use the same server snapshot as the board.
     */
    private fun renderLeaderboard(board: UserRepository.Leaderboard?) {
        val binding = _binding ?: return

        if (board == null) return

        val pool = formatCount(board.prizePool)
        val now = ServerClock.now()

        // The pool split is already in the prize panel below, so this line
        // carries either what unlocks the tournament or when the week ends.
        // Server clock, since the boundary is the server's.
        val endsIn = board.weekEndsAtMillis - now
        binding.leaderboardSubtitle.text = when {
            !board.unlocked -> getString(R.string.earn_unlocks_at_level, board.unlockLevel)
            endsIn > 0 -> getString(R.string.earn_tournament_ends_in, durationLabel(endsIn))
            else -> getString(R.string.earn_tournament_ending)
        }

        binding.leaderboardRank.text = when {
            !board.unlocked -> getString(R.string.earn_locked_you, board.unlockLevel)
            board.isRanked -> getString(R.string.earn_rank_you, formatCount(board.myRank))
            else -> getString(R.string.earn_unranked_you)
        }

        renderMyPanel(board)
        binding.leaderboardPrizePool.setStarText(getString(R.string.earn_pool_value, pool))
        binding.leaderboardPrizeShare.text = getString(R.string.earn_pool_share, board.size)
    }

    /**
     * The player's own panel. Unlocked, it is the weekly XP figure. Locked,
     * it greys out and says how far away the unlock is instead - a "0 XP"
     * figure there would only say what the lock already says.
     */
    private fun renderMyPanel(board: UserRepository.Leaderboard) {
        val binding = _binding ?: return
        val locked = !board.unlocked

        val accent = ContextCompat.getColor(
            requireContext(),
            if (locked) R.color.text_faint else R.color.brand_violet_light
        )
        binding.leaderboardMeIcon.setColorFilter(accent)
        binding.leaderboardRank.setTextColor(
            ContextCompat.getColor(requireContext(), if (locked) R.color.text_faint else R.color.white)
        )

        binding.leaderboardMyXp.isVisible = !locked
        binding.leaderboardMyXpCaption.isVisible = !locked
        binding.leaderboardUnlockNeed.isVisible = locked

        if (!locked) {
            binding.leaderboardMyXp.text = getString(R.string.earn_xp, formatCount(board.myXp))
            return
        }

        // From the published curve; until it lands, the line waits rather
        // than quoting a guessed figure.
        val curve = mainViewModel.levelCurve.value
        val xp = mainViewModel.levelProgress.value?.totalXp
        binding.leaderboardUnlockNeed.text = if (curve != null && xp != null) {
            val needed = (curve.xpRequiredFor(board.unlockLevel) - xp).coerceAtLeast(1)
            getString(R.string.earn_unlock_need, formatCount(needed))
        } else {
            ""
        }
    }

    /**
     * Opens the leaderboard screen.
     *
     * Guarded on the current destination rather than a boolean: navigating is
     * asynchronous, so a fast thumb could fire this several times before the
     * first one arrived, and every tap would push another copy of the screen
     * onto the stack. Asking where we are is the check that cannot race.
     */
    private fun openLeaderboard() {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.navigation_rewards) return

        controller.navigate(R.id.leaderboardFragment)
    }

    /**
     * "2 days, 5 hours", dropping to "5 hours, 12 minutes" and then
     * "12 minutes" as the deadline nears, so the line never reads "0 days".
     */
    private fun durationLabel(remainingMs: Long): String {
        val safe = remainingMs.coerceAtLeast(TimeUnit.MINUTES.toMillis(1))
        val days = TimeUnit.MILLISECONDS.toDays(safe).toInt()
        val hours = (TimeUnit.MILLISECONDS.toHours(safe) % 24).toInt()
        val minutes = (TimeUnit.MILLISECONDS.toMinutes(safe) % 60).toInt()
        val res = resources
        return when {
            days > 0 -> getString(
                R.string.earn_duration_pair,
                res.getQuantityString(R.plurals.earn_days, days, days),
                res.getQuantityString(R.plurals.earn_hours, hours, hours)
            )
            hours > 0 -> getString(
                R.string.earn_duration_pair,
                res.getQuantityString(R.plurals.earn_hours, hours, hours),
                res.getQuantityString(R.plurals.earn_minutes, minutes, minutes)
            )
            else -> res.getQuantityString(R.plurals.earn_minutes, minutes.coerceAtLeast(1), minutes.coerceAtLeast(1))
        }
    }

    /** Thousands separators - a rank of 24247 is unreadable without them. */
    private fun formatCount(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    /**
     * Follows the shared catalogue rather than fetching one of its own.
     *
     * The bottom bar decides whether this tab exists from the same
     * LiveData - see MainViewModel.offerwallAvailable - and a second,
     * independent read here is how the two would come to disagree: the bar
     * says Earn exists, this screen's own fetch fails, and the user lands on
     * an empty list. One source, one answer.
     *
     * An unreadable catalogue and an empty one look the same to the user on
     * purpose. There is nothing for them to retry - no wall exists either
     * way - and an error state here would read as "the earning screen is
     * broken", which is a worse and less accurate thing to say than
     * "nothing yet".
     */
    private fun observeWalls() {
        showLoading()

        OfferwallCatalogStore.walls.observe(viewLifecycleOwner) { walls ->
            if (_binding == null) return@observe
            render(OfferwallEntry.visibleTo(walls, currentLevel()))
        }
    }

    /**
     * The level to filter the catalogue against.
     *
     * Falls back to 1 rather than to a high number: an unknown level should
     * show the ungated walls, not silently unlock the gated ones.
     */
    private fun currentLevel(): Int =
        userRepository.userData.value?.level ?: 1

    private fun render(walls: List<OfferwallEntry>) {
        binding.offerwallLoading.isVisible = false
        binding.offerwallEmpty.isVisible = walls.isEmpty()
        binding.offerwallList.isVisible = walls.isNotEmpty()

        binding.offerwallList.adapter = OfferwallAdapter(walls) { wall -> open(wall) }
    }

    private fun showLoading() {
        binding.offerwallLoading.isVisible = true
        binding.offerwallEmpty.isVisible = false
        binding.offerwallList.isVisible = false
    }

    private fun open(wall: OfferwallEntry) {
        val uid = userRepository.getCurrentUserId()
        if (uid.isNullOrBlank()) {
            // Without a uid the network has nothing to attribute a
            // completion to, so opening the wall would let the user do the
            // work and never be paid for it. Refusing is the honest failure.
            Log.e(TAG, "No signed-in user; refusing to open ${wall.id}")
            return
        }

        if (wall.type == OfferwallEntry.TYPE_TAPJOY) {
            TapjoyOfferwall.show(requireActivity(), uid) { reason ->
                // Reported, never silent. A tap that produces no screen and
                // no message is indistinguishable from a frozen app, and the
                // user's only recourse is to tap again - which was exactly
                // the old behaviour.
                if (_binding == null) return@show
                Toast.makeText(
                    requireContext(),
                    when (reason) {
                        TapjoyOfferwall.REASON_CONNECTING -> getString(R.string.offerwall_connecting)
                        TapjoyOfferwall.REASON_LOADING -> getString(R.string.offerwall_loading)
                        else -> getString(R.string.offerwall_unavailable)
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        startActivity(
            OfferwallActivity.intent(requireContext(), wall.urlFor(uid), wall.name)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Dropped explicitly: the adapter closes over this fragment through
        // its click lambda, and a RecyclerView outliving the view it was
        // bound from is the usual way that becomes a leak.
        _binding?.offerwallList?.adapter = null
        _binding = null
    }

    companion object {
        private const val TAG = "Offerwall"
    }
}
