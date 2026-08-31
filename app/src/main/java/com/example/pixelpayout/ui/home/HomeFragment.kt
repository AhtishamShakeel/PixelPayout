package com.example.pixelpayout.ui.home

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.debug.BuffDebug
import com.pixelpayout.BuildConfig
import com.example.pixelpayout.ui.redemption.RedemptionFragment
import com.example.pixelpayout.ui.redemption.WalletFormat
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentHomeBinding
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.example.pixelpayout.ui.play.PlayFragment
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.startLoading
import com.example.pixelpayout.utils.stopLoading
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.pixelpayout.utils.UserPreferences
import com.example.pixelpayout.data.repository.DailyGoalEngine
import com.example.pixelpayout.utils.ServerClock
import com.example.pixelpayout.utils.setStarText
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * How long a redemption is expected to take. A service target we are choosing
 * to show, not a rule the server applies - resolveRedemption has no deadline
 * and never will, because a person has to approve each one.
 */
/**
 * The 48 hour payout target now lives in WalletFormat, so this screen and the
 * Wallet Orders tab cannot promise different things. Kept as an alias rather
 * than inlined at the call site so the name still reads here.
 */
private val REDEEM_TARGET_MILLIS = WalletFormat.PAYOUT_TARGET_MILLIS

/** Matches STREAK_CYCLE_DAYS server-side; the strip draws one cycle. */
private const val STREAK_CYCLE_DAYS = 7


class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    
    /** The server's reward table, fetched once so the strip can show it. */
    private var cycleRewards: List<UserRepository.StreakDayReward> = emptyList()
    private var streakClaimInFlight = false
    private val userPreferences by lazy { UserPreferences(requireContext().applicationContext) }

    /** Guards against a second dialog stacking on the one already showing. */
    private var announcingRedemption = false
    private var goalClaimInFlight = false

    /** What the next claim would pay, and which day, for the dialog. */
    private var pendingRewardLabel: String? = null
    private var pendingRewardIsStars = false
    private var pendingClaimDay: Int = 1

    /**
     * The day a claim was confirmed for, held until the user document catches
     * up.
     *
     * The card is driven by a Firestore snapshot, and the write behind that
     * snapshot lands a moment after the callable returns. Without this the
     * button sat there saying "Try again" after a successful claim - and
     * tapping it did nothing visible, because the server correctly answered
     * "already rewarded" and that is not worth a toast.
     */
    private var confirmedRewardDayUtc: Long? = null

    // Timer handler for countdown if needed
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateQuizStatusText()
            updateBuffBadge()
            mainViewModel.pendingRedemptions.value?.let { renderPending(it) }
            timerHandler.postDelayed(this, 1000) // Update every second
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        setupDebugControls()
        // Warmed here so the claim button does not sit through a cold load.
        AdManager.getInstance().loadRewardedAd(requireContext())
        mainViewModel.loadStreakCycle()
        // Goals and standings are NOT refreshed here: onResume always follows
        // onViewCreated, and both refreshes are idempotent, so asking twice
        // bought nothing but a second round trip on every first visit.
        observeViewModel()

    }
    
    override fun onResume() {
        super.onResume()
        // Start the timer to update the quiz status if needed
        timerHandler.post(timerRunnable)
        // Goal progress and the standings both live in server state rather than
        // a snapshot, so coming back from a game or a quiz is the moment to
        // re-read them.
        mainViewModel.refreshDailyGoals()
        mainViewModel.refreshLeaderboard()
    }
    
    override fun onPause() {
        super.onPause()
        // Stop the timer when fragment is paused
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun observeViewModel(){
        mainViewModel.points.observe(viewLifecycleOwner) { points ->
            binding.totalPoints.text = points.toString()
        }

        // Fills toward the cheapest redemption not yet affordable. Null means
        // there is no such target, so the bar is hidden rather than shown full.
        mainViewModel.nextRedemption.observe(viewLifecycleOwner) { next ->
            if (next == null) {
                binding.nextTierGroup.visibility = View.GONE
            } else {
                binding.nextTierGroup.visibility = View.VISIBLE
                binding.redemptionProgress.progress = next.percent
                // Names the prize, not just the price. "30 UC at 600 stars"
                // replaced a pair of lines that between them said the target,
                // the shortfall and the prize - three rows for one fact.
                val cost = formatCount(next.pointsCost)
                binding.balanceTarget.setStarText(
                    getString(R.string.balance_target, next.title, cost),
                    emphasise = cost
                )
                binding.balanceCurrent.text = formatCount(next.pointsHeld)
                binding.balanceRequired.text = cost
            }
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.levelTitle.text = getString(R.string.level_card_title, progress.level)

            when {
                progress.isMaxLevel -> {
                    // No next level to fill toward. A full bar says "nothing
                    // left to earn here", which is the truth; an empty one
                    // would read as no progress at all.
                    binding.levelProgressBar.progress = 100
                    binding.levelReward.setText(R.string.level_reached_max)
                    binding.levelXpCurrent.text = ""
                    binding.levelXpRequired.text = ""
                }

                // The curve is fetched once per session and can still be in
                // flight, or have failed. Level is known either way; the XP
                // figures are not, so they are left blank rather than shown
                // as 0 / 0.
                progress.xpForNextLevel <= 0 -> {
                    binding.levelProgressBar.progress = 0
                    binding.levelReward.text = ""
                    binding.levelXpCurrent.text = ""
                    binding.levelXpRequired.text = ""
                }

                else -> {
                    binding.levelProgressBar.progress =
                        (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)

                    // One line where there were two, and it leads with what
                    // the next level PAYS rather than only how far it is -
                    // the question the level number invites is "worth what?".
                    // Levels the published curve pays nothing for fall back
                    // to the distance alone; "claim 0 stars" would be a
                    // promise the server never makes.
                    val xpToGo = progress.xpForNextLevel - progress.xpIntoLevel
                    if (progress.nextLevelReward > 0) {
                        val reward = formatCount(progress.nextLevelReward)
                        binding.levelReward.setStarText(
                            getString(R.string.level_reward_next, xpToGo, reward),
                            emphasise = reward
                        )
                    } else {
                        binding.levelReward.text =
                            getString(R.string.level_to_next, xpToGo, progress.level + 1)
                    }
                    binding.levelXpCurrent.text = formatCount(progress.xpIntoLevel)
                    binding.levelXpRequired.text = formatCount(progress.xpForNextLevel)
                }
            }
        }

        // The badge only appears while a buff is running; the countdown is
        // driven by the existing per-second timer below.
        mainViewModel.activeBuff.observe(viewLifecycleOwner) { updateBuffBadge() }
        mainViewModel.activeXpBuff.observe(viewLifecycleOwner) { updateBuffBadge() }

        mainViewModel.streak.observe(viewLifecycleOwner) { renderStreak(it) }

        // Served from the view model's cache, so returning to this tab does
        // not blank the streak cells while a callable is re-fetched.
        mainViewModel.streakCycle.observe(viewLifecycleOwner) { cycle ->
            cycleRewards = cycle
            mainViewModel.streak.value?.let { renderStreak(it) }
        }

        mainViewModel.pendingRedemptions.observe(viewLifecycleOwner) { renderPending(it) }

        mainViewModel.payoutFeed.observe(viewLifecycleOwner) { renderPayoutFeed(it) }

        mainViewModel.dailyGoals.observe(viewLifecycleOwner) { renderGoals(it) }

        mainViewModel.leaderboard.observe(viewLifecycleOwner) { renderLeaderboard(it) }

        mainViewModel.resolvedRedemptions.observe(viewLifecycleOwner) {
            announceResolvedRedemptions(it)
        }

        // Attempts come off the user snapshot now, not from a callable. The
        // countdown half needs no observer at all - it is recomputed by the
        // per-second timer from the clock.
        // The allowance rather than the raw count: an attempt bought with a
        // rewarded ad on the Quizzes tab widens it, and a tile still counting
        // against the bare cap would tell the user they had none left while
        // the Quizzes card offered them one.
        mainViewModel.quizAllowance.observe(viewLifecycleOwner) {
            updateQuizStatusText()
        }
    }
    
    /**
     * The boost card, driven by both buffs at once.
     *
     * The Points buff and the XP buff are separate grants server-side and
     * either can run alone, so the card names every one that is live rather
     * than picking a winner and quietly hiding the other.
     *
     * The clock shows the SOONEST expiry. One clock cannot honestly stand for
     * two deadlines, and the nearer one is the next thing about this card
     * that changes.
     */
    private fun updateBuffBadge() {
        val binding = _binding ?: return
        val now = ServerClock.now()

        val pointsBuff = mainViewModel.activeBuff.value?.takeIf { it.isActive(now) }
        val xpBuff = mainViewModel.activeXpBuff.value?.takeIf { it.isActive(now) }

        if (pointsBuff == null && xpBuff == null) {
            binding.buffCard.visibility = View.GONE
            return
        }

        val labels = mutableListOf<String>()
        xpBuff?.let {
            labels += getString(R.string.buff_label_xp, formatMultiplier(it.multiplier))
        }
        pointsBuff?.let {
            labels += getString(R.string.buff_label, formatMultiplier(it.multiplier))
        }

        binding.buffText.text = labels.joinToString(" · ")
        // The XP note is the more useful of the two when both are running:
        // levelling is the effect a player can actually watch happen.
        binding.buffNote.setText(
            if (xpBuff != null) R.string.buff_xp_note else R.string.buff_rate_note
        )

        val soonestExpiry = listOfNotNull(pointsBuff, xpBuff).minOf { it.expiresAtMillis }
        binding.buffTimer.text = formatCountdown(soonestExpiry - now)
        binding.buffCard.visibility = View.VISIBLE
    }

    /** HH:MM:SS. Monospace in the layout keeps the digits from jumping. */
    private fun formatCountdown(remainingMs: Long): String {
        val ms = remainingMs.coerceAtLeast(0)
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(ms),
            TimeUnit.MILLISECONDS.toMinutes(ms) % 60,
            TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        )
    }

    /** Multipliers are whole-ish; drop a trailing .0 so it reads "2x". */
    private fun formatMultiplier(multiplier: Double): String =
        if (multiplier % 1.0 == 0.0) multiplier.toInt().toString() else multiplier.toString()

    /**
     * The quiz tile's subtitle, refreshed once a second.
     *
     * The condition here used to be inverted: the reset countdown was computed
     * inside the `remaining > 0` branch and overwrote the attempts text, so
     * "3 quizzes left" was written and then immediately replaced on the same
     * pass and never actually appeared. Meanwhile the branch where the user
     * has NO attempts left - the one case the countdown is for, and the one
     * the original comment described - set no text at all, leaving the
     * subtitle empty. On a slim row that read as a blank line; on the tile it
     * reads as a rendering fault.
     *
     * So: attempts remaining is what a player needs while they can still
     * play, and the countdown is what they need once they cannot.
     */
    private fun updateQuizStatusText() {
        val remaining = mainViewModel.quizAllowanceNow().remaining

        binding.quizTileSubtitle.text = if (remaining > 0) {
            resources.getQuantityString(R.plurals.quizzes_left, remaining, remaining)
        } else {
            resetCountdownText()
        }
    }

    /**
     * Time until the daily quiz allowance resets, measured against the
     * server's clock rather than the device's - changing the phone's date
     * cannot buy extra attempts.
     */
    private fun resetCountdownText(): String {
        val remainingMs = mainViewModel.nextAttemptsResetMillis() - ServerClock.now()
        if (remainingMs <= 0) return getString(R.string.quiz_resetting)

        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60

        return when {
            hours > 0 -> getString(R.string.quiz_reset_in_hm, hours, minutes)
            minutes > 0 -> getString(R.string.quiz_reset_in_ms, minutes, seconds)
            else -> getString(R.string.quiz_reset_in_s, seconds)
        }
    }

    private fun setupClickListeners() {
        binding.apply {
            // The tiles are fully described in XML now - only the quiz
            // subtitle is dynamic, and the per-second timer owns that.
            playTile.setOnClickListener { navigateToGame() }
            quizTile.setOnClickListener { navigateToQuizzes() }

            // The section's "View all" link and its offer tile both land on
            // Earn. The tile lost its separate button in the redesign - at a
            // third of the row's width there is no room for one, and the
            // whole tile was already the larger target.
            earnAction.setOnClickListener { navigateToRewards() }
            offerCard.setOnClickListener { navigateToRewards() }
            referAction.setOnClickListener { showReferralDialog() }

            streakClaimButton.setOnClickListener { confirmStreakClaim() }
            goalsClaimButton.setOnClickListener { confirmGoalClaim() }

            levelRewardsButton.setOnClickListener { openLevelRewards() }

            btnPayout.setOnClickListener { navigateToRedemption()}
        }
    }

    private fun navigateToQuizzes() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                

            // Quizzes is a tab inside Play now, so land there rather than on
            // a destination of its own.
            val args = bundleOf(PlayFragment.ARG_START_TAB to PlayFragment.TAB_QUIZZES)
            findNavController().navigate(R.id.navigation_play, args, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to quizzes: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    private fun navigateToGame() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()

            findNavController().navigate(R.id.navigation_play, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to game: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_play
        }
    }

    /**
     * The boost card cannot otherwise be seen: no source grants a buff yet, so
     * without this the card is unreachable in a running app. Debug builds only.
     */
    private fun setupDebugControls() {
        if (!BuildConfig.DEBUG) return

        binding.debugControls.visibility = View.VISIBLE
        binding.debugBoostButton.setOnClickListener { grantDebugBuff(BuffDebug.Kind.POINTS) }
        binding.debugXpBoostButton.setOnClickListener { grantDebugBuff(BuffDebug.Kind.XP) }
    }

    private fun grantDebugBuff(kind: BuffDebug.Kind) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Disabling the container would not reach the buttons: isEnabled
            // does not propagate to children.
            setDebugControlsEnabled(false)
            val message = BuffDebug.grantSelfBuff(kind)
            if (isAdded) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
            setDebugControlsEnabled(true)
        }
    }

    /**
     * The seven-cell strip, the footer and the claim button.
     *
     * Everything is derived from the streak fields plus the reward table; the
     * client stores no progress of its own. Two separate day gates drive this,
     * mirroring the server: whether the STREAK has moved on today, and whether
     * today's REWARD has been paid. They are not the same question - a claim
     * whose ad failed advances the first and leaves the second open, which is
     * exactly the state the retry button exists for.
     */
    private fun renderStreak(streak: UserRepository.Streak) {
        val binding = _binding ?: return
        val todayUtc = ServerClock.now() / MILLIS_PER_DAY

        val done = streak.cyclePosition(todayUtc, STREAK_CYCLE_DAYS)
        val streakMovedToday = streak.movedOn(todayUtc)
        val rewardedToday = streak.rewardedOn(todayUtc) ||
            confirmedRewardDayUtc == todayUtc

        binding.streakTitle.text = if (streak.isAlive(todayUtc) && streak.count > 0) {
            getString(R.string.streak_title, streak.count)
        } else {
            getString(R.string.streak_title_none)
        }

        // The cycle position the NEXT claim will pay for. If the streak has
        // already moved today it lands on the day just reached; otherwise it
        // advances one. This has to match resolveStreakClaim exactly, or the
        // dialog promises one figure and the server pays another - which it
        // did: with a six day streak from yesterday, the dialog offered day
        // six's 60 XP while the claim correctly paid day seven's 20 stars.
        val claimPosition = if (streakMovedToday) {
            maxOf(done, 1)
        } else {
            done % STREAK_CYCLE_DAYS + 1
        }

        // Every cell shows what that day of the cycle pays, claimed or not, so
        // the week ahead is legible rather than a row of blanks. The one the
        // next claim will actually pay is ringed - the strip otherwise says
        // what the week holds without saying which part of it is in play.
        // Only while something is claimable: once today is paid, ringing a
        // cell would point at a reward that is a day away.
        val nextIndex = if (rewardedToday) -1 else claimPosition - 1

        streakCells(binding).forEachIndexed { index, cell ->
            val reward = cycleRewards.getOrNull(index)
            val filled = index < done
            val isNext = index == nextIndex

            cell.setBackgroundResource(
                when {
                    filled && isNext -> R.drawable.bg_streak_cell_done_next
                    filled -> R.drawable.bg_streak_cell_done
                    isNext -> R.drawable.bg_streak_cell_next
                    else -> R.drawable.bg_streak_cell_todo
                }
            )
            // A CLAIMED day drops its figure for a tick. What day three paid
            // stops being information the moment it is banked, and seven spent
            // figures compete with the days still to come, which are the only
            // ones the strip is really for.
            //
            // Everywhere else the label carries the REWARD TYPE and nothing
            // else - the box already said reached, in play, or ahead. A Stars
            // day is gold, as a star is on every screen in this app; an XP day
            // is neutral, and leans brighter while it is the one in play.
            if (filled) {
                // Nothing to draw here: the cell's background carries both
                // the fill and the tick, centred. This used to set a compound
                // drawable, which Android pins to the view's edge rather than
                // centring, and which made the claimed cell measure
                // differently from the six beside it.
                cell.text = ""
            } else {
                cell.text = reward?.let { cellLabel(it) }.orEmpty()
                cell.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        when {
                            reward != null && reward.points > 0 -> R.color.stars_accent
                            isNext -> R.color.text_soft
                            else -> R.color.text_faint
                        }
                    )
                )
            }
        }
        // Only meaningful once today is settled; "tomorrow" is the day after
        // whichever day the current claim belongs to.
        val tomorrowPosition = claimPosition % STREAK_CYCLE_DAYS + 1

        val claimReward = cycleRewards.getOrNull(claimPosition - 1)
        val tomorrowReward = cycleRewards.getOrNull(tomorrowPosition - 1)

        binding.streakFooter.text = when {
            rewardedToday && tomorrowReward != null ->
                getString(R.string.streak_tomorrow, describeReward(tomorrowReward))
            rewardedToday -> getString(R.string.streak_claimed)
            streakMovedToday -> getString(R.string.streak_reward_waiting, claimPosition)
            streak.isAlive(todayUtc) && streak.count > 0 ->
                getString(R.string.streak_ready, claimPosition)
            else -> getString(R.string.streak_start)
        }

        binding.streakClaimButton.visibility =
            if (rewardedToday) View.GONE else View.VISIBLE
        // A day whose streak already moved on but paid nothing is a retry, and
        // saying so is the difference between "come back tomorrow" and "have
        // another go".
        binding.streakClaimButton.renderClaimState(
            inFlight = streakClaimInFlight,
            idleText = getString(
                if (streakMovedToday) R.string.streak_try_again else R.string.streak_claim
            )
        )

        pendingClaimDay = claimPosition
        pendingRewardLabel = claimReward?.let { describeReward(it) }
        pendingRewardIsStars = (claimReward?.points ?: 0) > 0
    }

    private fun streakCells(binding: FragmentHomeBinding) = listOf(
        binding.streakCell1, binding.streakCell2, binding.streakCell3,
        binding.streakCell4, binding.streakCell5, binding.streakCell6,
        binding.streakCell7
    )

    /** Two lines - the figure, then its unit - because a cell is ~22dp wide. */
    private fun cellLabel(reward: UserRepository.StreakDayReward): String = when {
        reward.points > 0 -> "${reward.points}\n\u2605"
        else -> "${reward.xp}\nXP"
    }

    private fun describeReward(reward: UserRepository.StreakDayReward): String = when {
        reward.points > 0 -> getString(R.string.streak_reward_points, reward.points)
        else -> getString(R.string.streak_reward_xp, reward.xp)
    }

    /**
     * Asks before spending the user's time on an ad, and names what it buys.
     * Starting a fullscreen ad straight off a tap reads as an accident.
     *
     * Built from its own layout rather than MaterialAlertDialogBuilder, whose
     * default paints from colorSurface and the platform typeface - on top of
     * the redrawn home screen that read as a different app.
     */
    private fun confirmStreakClaim() {
        if (streakClaimInFlight) return
        showAdClaimDialog(
            title = getString(R.string.streak_dialog_title, pendingClaimDay),
            reward = pendingRewardLabel,
            rewardIsStars = pendingRewardIsStars,
            dotRes = R.drawable.bg_dot_streak,
            onWatch = { playAdThenClaim() }
        )
    }

    /**
     * The confirmation shown before any rewarded ad.
     *
     * Shared by the streak and the daily goals rather than duplicated: two
     * dialogs asking the same question in the same words would drift apart the
     * first time one of them was touched.
     */
    private fun showAdClaimDialog(
        title: String,
        reward: String?,
        dotRes: Int,
        /**
         * Which currency [reward] is quoted in. The figure was painted gold
         * unconditionally, which was right for the Stars days of the streak
         * and for the goal bonus, and wrong for its four XP days - a gold
         * "+30 XP" reads as a payout that never arrives.
         */
        rewardIsStars: Boolean,
        onWatch: () -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_ad_claim, null)
        val dialog = Dialog(requireContext(), R.style.CustomDialogTheme).apply {
            setContentView(view)
        }

        view.findViewById<TextView>(R.id.adClaimTitle).text = title
        view.findViewById<View>(R.id.adClaimDot).setBackgroundResource(dotRes)

        val rewardView = view.findViewById<TextView>(R.id.adClaimReward)
        if (reward != null) {
            rewardView.text = getString(R.string.streak_dialog_reward, reward)
            rewardView.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (rewardIsStars) R.color.stars_accent else R.color.xp_accent
                )
            )
        } else {
            // Better to say nothing than a figure the claim might not pay.
            rewardView.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.adClaimMessage)
            .setText(R.string.streak_dialog_message_short)

        view.findViewById<View>(R.id.adClaimWatch).setOnClickListener {
            dialog.dismiss()
            onWatch()
        }
        view.findViewById<View>(R.id.adClaimCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * The ad gates the reward, not the streak.
     *
     * If no ad plays the claim still goes through with adWatched=false: the
     * streak moves on, nothing is paid, and the day stays claimable so the
     * user can retry. Ad fill is our problem, and losing a streak to it would
     * break the one promise the feature makes.
     */
    private fun playAdThenClaim() {
        streakClaimInFlight = true
        binding.streakClaimButton.renderClaimState(inFlight = true)

        var earned = false
        AdManager.getInstance().showRewardedAd(
            activity = requireActivity(),
            onRewarded = { earned = true },
            onAdClosed = { submitStreakClaim(adWatched = earned) },
            onAdFailedToShow = {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.streak_ad_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                submitStreakClaim(adWatched = false)
            }
        )
    }

    private fun submitStreakClaim(adWatched: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = mainViewModel.claimDailyStreak(adWatched)) {
                is UserRepository.StreakClaimResult.Rewarded -> {
                    // Believe the response immediately; the snapshot only
                    // confirms what the server already told us.
                    confirmedRewardDayUtc = ServerClock.now() / MILLIS_PER_DAY
                    if (isAdded) {
                        val awarded = if (result.pointsAwarded > 0) {
                            getString(R.string.streak_reward_points, result.pointsAwarded)
                        } else {
                            getString(R.string.streak_reward_xp, result.xpAwarded)
                        }
                        // The server counts absolutely (day 8 of an unbroken
                        // run); the card counts within the cycle it draws.
                        // Showing the absolute number here would contradict
                        // the dialog that just said "Day 1 reward".
                        val cycleDay = (result.day - 1) % STREAK_CYCLE_DAYS + 1
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.streak_claimed_toast, cycleDay, awarded),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                is UserRepository.StreakClaimResult.NotRewarded -> {
                    // The server treats today as settled either way, so stop
                    // offering the button once it says so.
                    if (result.reason == "already_rewarded") {
                        confirmedRewardDayUtc = ServerClock.now() / MILLIS_PER_DAY
                    }
                    // Only worth saying when the user expected a reward; an
                    // already-rewarded day is just a repeat tap.
                    if (isAdded && result.reason != "already_rewarded") {
                        Toast.makeText(
                            requireContext(),
                            R.string.streak_no_reward,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                is UserRepository.StreakClaimResult.Error -> {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(), result.message, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            streakClaimInFlight = false
            // The user document repaints the card through its snapshot
            // listener; this only restores the button.
            mainViewModel.streak.value?.let { renderStreak(it) }
        }
    }

    /**
     * Tells the user, once, that a payout was settled.
     *
     * Approval happens on our side, usually while the app is closed, so
     * without this the pending row simply vanishes and a balance quietly
     * changes - the user is never told the thing they were waiting for
     * actually happened. A rejection matters more still, since their stars
     * come back and nothing on screen would say why.
     *
     * "Once" is a stored timestamp rather than a set of seen ids: one
     * comparison, it never grows, and anything settled before it is by
     * definition already known.
     */
    private fun announceResolvedRedemptions(
        resolved: List<UserRepository.ResolvedRedemption>
    ) {
        if (resolved.isEmpty() || announcingRedemption) return

        viewLifecycleOwner.lifecycleScope.launch {
            val lastSeen = userPreferences.lastSeenRedemptionResolvedAt.first()

            // First run on this device: adopt the current history silently.
            // Everything already settled predates the app knowing about it,
            // and greeting a fresh install with news of a months-old payout
            // would be worse than saying nothing.
            if (lastSeen == 0L) {
                userPreferences.setLastSeenRedemptionResolvedAt(
                    resolved.maxOf { it.resolvedAtMillis }
                )
                return@launch
            }

            val unseen = resolved.filter { it.resolvedAtMillis > lastSeen }
            if (unseen.isEmpty() || !isAdded) return@launch

            // Mark everything unseen as known before showing anything. If
            // several were settled at once the newest is the one worth a
            // dialog, and the rest would otherwise queue up behind it.
            userPreferences.setLastSeenRedemptionResolvedAt(
                unseen.maxOf { it.resolvedAtMillis }
            )

            announcingRedemption = true
            showRedemptionResult(unseen.first())
        }
    }

    private fun showRedemptionResult(result: UserRepository.ResolvedRedemption) {
        val view = layoutInflater.inflate(R.layout.dialog_redemption_result, null)
        val dialog = Dialog(requireContext(), R.style.CustomDialogTheme).apply {
            setContentView(view)
            setOnDismissListener { announcingRedemption = false }
        }

        val title = view.findViewById<TextView>(R.id.redemptionResultTitle)
        val value = view.findViewById<TextView>(R.id.redemptionResultValue)
        val message = view.findViewById<TextView>(R.id.redemptionResultMessage)
        val dot = view.findViewById<View>(R.id.redemptionResultDot)

        if (result.approved) {
            title.setText(R.string.redemption_approved_title)
            value.text = result.title
            value.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
            dot.setBackgroundResource(R.drawable.bg_dot_success)
            message.setText(R.string.redemption_approved_message)
        } else {
            title.setText(R.string.redemption_declined_title)
            value.text = getString(R.string.redemption_declined_refund, result.refundedPoints)
            // A refund is Stars coming back, so it is gold like every other
            // Stars figure - not the warm off-gold this used to be.
            value.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.stars_accent)
            )
            dot.setBackgroundResource(R.drawable.bg_dot_stars)
            // The admin can attach a reason; when there is one it is more use
            // than the generic line.
            val reason = result.rejectionReason?.trim().orEmpty()
            message.text = if (reason.isNotEmpty()) {
                getString(R.string.redemption_declined_message_reason, reason)
            } else {
                getString(R.string.redemption_declined_message)
            }
        }

        view.findViewById<View>(R.id.redemptionResultDone).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * The weekly leaderboard card.
     *
     * Rank zero means no play this week rather than last place, so it reads as
     * an invitation instead of a position - telling someone they are "#0" or
     * dead last for not having started is worse than saying nothing.
     *
     * The figure beside the rank is WEEKLY XP, which is what the board is
     * actually sorted on. Showing stars there would be a scoreboard whose
     * number had nothing to do with its order.
     */
    private fun renderLeaderboard(board: UserRepository.Leaderboard?) {
        val binding = _binding ?: return

        if (board == null) {
            binding.leaderboardRow.visibility = View.GONE
            return
        }
        binding.leaderboardRow.visibility = View.VISIBLE

        val pool = formatCount(board.prizePool)
        binding.leaderboardSubtitle.setStarText(
            getString(R.string.leaderboard_subtitle, board.size, pool),
            emphasise = pool,
            emphasisColor = R.color.stars_accent
        )

        binding.leaderboardRank.text = if (board.isRanked) {
            getString(R.string.leaderboard_rank, formatCount(board.myRank))
        } else {
            getString(R.string.leaderboard_play_to_enter)
        }

        // What the rank is WORTH, in Stars, rather than the weekly XP that
        // produced it. The card is about a prize pool - the figure beside
        // "You" is the share of it this rank currently takes.
        //
        // A ranked user outside the prize zone wins nothing, and "0 stars"
        // beside their name reads as a payout of zero rather than as a
        // position that does not pay yet. Unranked users have no standing at
        // all, which is a third thing again.
        when {
            !board.isRanked ->
                binding.leaderboardMyXp.text =
                    getString(R.string.leaderboard_unranked_xp)

            board.myPrize > 0 -> {
                val prize = formatCount(board.myPrize)
                binding.leaderboardMyXp.setStarText(
                    getString(R.string.leaderboard_my_prize, prize),
                    emphasise = prize,
                    emphasisColor = R.color.stars_accent
                )
            }

            else -> binding.leaderboardMyXp.text =
                getString(R.string.leaderboard_my_none)
        }

        binding.leaderboardRow.setOnClickListener { openLeaderboard() }
    }

    /** Thousands separators - a rank of 24247 is unreadable without them. */
    private fun formatCount(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

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
        if (controller.currentDestination?.id != R.id.navigation_home) return

        controller.navigate(R.id.leaderboardFragment, null, defaultNavOptions())
    }

    /** The level ladder. Guarded the same way, and for the same reason. */
    private fun openLevelRewards() {
        val controller = findNavController()
        if (controller.currentDestination?.id != R.id.navigation_home) return

        controller.navigate(R.id.levelRewardsFragment, null, defaultNavOptions())
    }

    /**
     * Today's goals.
     *
     * Every figure here comes from the server, including whether a goal is
     * done. The card cannot decide that for itself - a goal the client can
     * mark complete is a button that prints Points - so this only draws what
     * it was told.
     */
    private fun renderGoals(goals: UserRepository.DailyGoals?) {
        val binding = _binding ?: return

        // The heading goes with the card. Goals are null until the pool
        // published on config/levelCurve arrives, and a heading left behind
        // on its own reads as a removed feature rather than a loading one.
        if (goals == null || goals.goals.isEmpty()) {
            binding.goalsHeader.visibility = View.GONE
            binding.goalsCard.visibility = View.GONE
            binding.goalsDoneCount.text = ""
            return
        }
        binding.goalsHeader.visibility = View.VISIBLE
        binding.goalsCard.visibility = View.VISIBLE

        binding.goalsDoneCount.text =
            getString(R.string.goals_done_count, goals.doneCount, goals.goals.size)
        val bonus = formatCount(goals.bonusPoints)
        binding.goalsBonus.setStarText(
            getString(R.string.goals_bonus, bonus),
            emphasise = bonus,
            emphasisColor = R.color.stars_accent
        )
        binding.goalsProgressBar.progress =
            goals.doneCount * 100 / goals.goals.size

        // The figure is gold in both states - it is Stars either way. What
        // changes on completion is the WORD beside it, which goes green: the
        // reward reads as earned rather than as another number on the card.
        // Set before the star text above would be overwritten by it, so this
        // paints the surrounding sentence only.
        binding.goalsBonus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (goals.allDone) R.color.success else R.color.text_ghost
            )
        )

        binding.goalsClaimButton.visibility =
            if (goals.allDone && !goals.bonusClaimed) View.VISIBLE else View.GONE
        binding.goalsClaimButton.renderClaimState(
            inFlight = goalClaimInFlight,
            idleText = getString(R.string.goals_claim)
        )

        // Once the bonus is paid there is nothing left to do today, so the
        // card collapses to the one line that still says something: what was
        // claimed. The three rows would all read 3/3, and the bar would be
        // full - a block of settled numbers occupying most of a screen.
        //
        // The card is not hidden outright: a claimed set is a thing the user
        // did, and a card that vanishes on the last tap reads as a bug.
        val settled = goals.allDone && goals.bonusClaimed
        binding.goalsSubtitle.setText(
            if (settled) R.string.goals_subtitle_claimed else R.string.goals_subtitle
        )
        binding.goalsProgressBar.visibility = if (settled) View.GONE else View.VISIBLE
        binding.goalRows.visibility = if (settled) View.GONE else View.VISIBLE
        if (settled) return

        val rows = listOf(
            Triple(binding.goalRow1, binding.goalMark1, binding.goalLabel1),
            Triple(binding.goalRow2, binding.goalMark2, binding.goalLabel2),
            Triple(binding.goalRow3, binding.goalMark3, binding.goalLabel3)
        )
        val progressViews = listOf(
            binding.goalProgress1, binding.goalProgress2, binding.goalProgress3
        )
        val rings = listOf(binding.goalRing1, binding.goalRing2, binding.goalRing3)

        rows.forEachIndexed { index, (row, mark, label) ->
            val goal = goals.goals.getOrNull(index)
            if (goal == null) {
                row.visibility = View.GONE
                return@forEachIndexed
            }
            row.visibility = View.VISIBLE

            mark.text = if (goal.done) "✓" else (index + 1).toString()
            mark.setBackgroundResource(
                if (goal.done) R.drawable.bg_goal_mark_done
                else android.R.color.transparent
            )
            mark.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (goal.done) R.color.surface_card else R.color.text_faint
                )
            )

            // The ring carries partial progress; a finished goal is the filled
            // disc instead, so the two never fight over the same 24dp.
            val ring = rings[index]
            ring.setIndicatorColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (goal.done) R.color.success else R.color.brand_violet_light
                )
            )
            ring.setProgressCompat(
                if (goal.target <= 0) 0
                else (goal.progress * 100 / goal.target).coerceIn(0, 100),
                true
            )

            label.text = goalLabel(goal)
            label.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (goal.done) R.color.text_faint else R.color.white
                )
            )

            progressViews[index].text =
                getString(R.string.goals_progress, goal.progress, goal.target)

            // A goal names something to go and DO, and until now said it on a
            // screen with no way to get there - the user had to read "play 8
            // games", find Play themselves, and remember which of the two
            // tabs it meant. Finished goals stay tappable: having done one is
            // not a reason to block the way back to it.
            row.setOnClickListener { openGoalTarget(goal.kind) }
        }
    }

    /**
     * The wording for a goal. The server sends a kind and a target; how that
     * reads is a client concern, which is what keeps it translatable.
     */
    private fun goalLabel(goal: UserRepository.DailyGoal): String {
        val plural = when (goal.kind) {
            DailyGoalEngine.KIND_PLAY_GAMES -> R.plurals.goal_play_games
            DailyGoalEngine.KIND_COMPLETE_QUIZZES -> R.plurals.goal_complete_quizzes
            else -> R.plurals.goal_correct_answers
        }
        return resources.getQuantityString(plural, goal.target, goal.target)
    }

    /**
     * Where a goal is actually done.
     *
     * The kinds fall into two destinations rather than three: answering
     * correctly is something that happens INSIDE a quiz, so it lands on the
     * same tab as completing them. The `else` arm matches progressFor, which
     * also treats any unrecognised kind as a correct-answers goal - a kind
     * added in the console but not yet known to this build lands somewhere
     * sensible instead of doing nothing when tapped.
     */
    private fun openGoalTarget(kind: String) {
        // Guarded like openLeaderboard: two taps in quick succession would
        // otherwise push Play onto the stack twice.
        if (findNavController().currentDestination?.id != R.id.navigation_home) return

        when (kind) {
            DailyGoalEngine.KIND_PLAY_GAMES -> navigateToGame()
            else -> navigateToQuizzes()
        }
    }

    /**
     * A claim button while the server is thinking.
     *
     * Both claims sit behind a Cloud Function that most users hit once a day,
     * so its instance is almost always cold - the gap between the ad closing
     * and the answer landing is seconds, not milliseconds. Disabling the
     * button was the only feedback, and a greyed-out button reads as one that
     * did nothing rather than one that is working.
     *
     * Driven from the render pass rather than set once at the tap, because a
     * claim WRITES to the user document: the snapshot listener fires
     * mid-claim, renders again, and would otherwise put the idle label back
     * underneath a still-spinning icon.
     */
    private fun MaterialButton.renderClaimState(
        inFlight: Boolean,
        idleText: String = text.toString()
    ) {
        if (inFlight) {
            // Guarded on the icon so a snapshot landing mid-claim restarts
            // neither the animation nor the label.
            if (icon == null) {
                startLoading(
                    getString(R.string.claim_in_progress),
                    resources.getDimensionPixelSize(R.dimen.claim_spinner_size)
                )
            }
        } else {
            // Idempotent, and it has to run every pass: the streak's idle
            // label alternates between "Claim" and "Try again".
            stopLoading(idleText)
        }
    }

    private fun confirmGoalClaim() {
        if (goalClaimInFlight) return
        val goals = mainViewModel.dailyGoals.value ?: return
        showAdClaimDialog(
            title = getString(R.string.goals_dialog_title),
            reward = getString(R.string.streak_reward_points, goals.bonusPoints),
            rewardIsStars = true,
            dotRes = R.drawable.bg_dot_success,
            onWatch = { playAdThenClaimGoals() }
        )
    }

    /**
     * As with the streak, a missing ad is not the user's fault - but here
     * there is no run to protect, so nothing is claimed and nothing is spent.
     * The set stays finished and the button stays available.
     */
    private fun playAdThenClaimGoals() {
        goalClaimInFlight = true
        binding.goalsClaimButton.renderClaimState(inFlight = true)

        var earned = false
        AdManager.getInstance().showRewardedAd(
            activity = requireActivity(),
            onRewarded = { earned = true },
            onAdClosed = { claimGoalBonus(adWatched = earned) },
            onAdFailedToShow = {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        R.string.streak_ad_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                goalClaimInFlight = false
                _binding?.goalsClaimButton
                    ?.renderClaimState(false, getString(R.string.goals_claim))
            }
        )
    }

    private fun claimGoalBonus(adWatched: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = mainViewModel.claimDailyGoalBonus(adWatched)) {
                is UserRepository.GoalBonusResult.Claimed -> {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.goals_claimed_toast, result.pointsAwarded),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                is UserRepository.GoalBonusResult.NotClaimed -> {
                    // "Already claimed" needs no comment; the card will have
                    // hidden the button by the time the user looks again.
                    val message = when (result.reason) {
                        "not_complete" -> R.string.goals_not_complete
                        "no_ad" -> R.string.goals_no_reward
                        else -> null
                    }
                    if (isAdded && message != null) {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }

                is UserRepository.GoalBonusResult.Error -> {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(), result.message, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            goalClaimInFlight = false
            // The card repaints through the snapshot listener; this only
            // restores the button, the way the streak claim does.
            _binding?.goalsClaimButton
                ?.renderClaimState(false, getString(R.string.goals_claim))
        }
    }

    /**
     * The pending redemption row, and its countdown toward the 48 hour
     * service target.
     *
     * The target is ours, not something the server enforces - approval is a
     * human step - so once the window passes the row stops counting and says
     * "in review". A timer that has run out, or run negative, would be worse
     * than no timer at all.
     */
    private fun renderPending(pending: UserRepository.PendingRedemptions) {
        val binding = _binding ?: return

        // Nothing waiting is the usual state, and an empty row saying so would
        // be noise on every screen for every user.
        if (pending.count == 0) {
            binding.pendingRedeemRow.visibility = View.GONE
            return
        }
        binding.pendingRedeemRow.visibility = View.VISIBLE

        // Tapping through goes to the Orders tab, which is the only place the
        // full picture lives - this row can only ever describe the oldest one.
        binding.pendingRedeemRow.setOnClickListener { openWalletOrders() }

        // One request names itself; several would not fit, so they are counted.
        val subject = if (pending.count == 1 && pending.title.isNotBlank()) {
            pending.title
        } else {
            getString(R.string.pending_redeem_many, pending.count)
        }

        val readyAt = pending.requestedAtMillis?.plus(REDEEM_TARGET_MILLIS)
        val remaining = readyAt?.minus(ServerClock.now()) ?: 0L

        binding.pendingRedeemValue.text = if (remaining > 0) {
            getString(R.string.pending_redeem_value, subject, remainingLabel(remaining))
        } else {
            getString(R.string.pending_redeem_overdue, subject)
        }
    }

    /**
     * Switches to the Wallet tab and asks it to open on Orders.
     *
     * The result is set BEFORE the tab switch: RedemptionFragment does not
     * exist yet, and a FragmentManager holds a result until a listener
     * appears, so setting it first is what guarantees it is seen.
     */
    private fun openWalletOrders() {
        parentFragmentManager.setFragmentResult(
            RedemptionFragment.RESULT_SHOW_ORDERS,
            Bundle.EMPTY
        )
        requireActivity()
            .findViewById<View>(R.id.navigation_redemption)
            ?.performClick()
    }

    /** Hours until the last one, then minutes - "41h", "35m". */
    private fun remainingLabel(remainingMs: Long): String =
        WalletFormat.remainingLabel(requireContext(), remainingMs)

    /**
     * The payout feed, as one line: the most recent approved payout, tappable
     * for the rest.
     *
     * Names arrive already masked - the raw ones are never in this collection,
     * so there is nothing here to get wrong client side.
     */
    private fun renderPayoutFeed(entries: List<UserRepository.PayoutFeedEntry>) {
        val binding = _binding ?: return

        val latest = entries.firstOrNull()
        if (latest == null) {
            binding.payoutFeedRow.visibility = View.GONE
            return
        }

        binding.payoutFeedRow.visibility = View.VISIBLE
        binding.payoutFeedText.text =
            getString(R.string.payout_feed_row, latest.name, latest.label)
        binding.payoutFeedTime.text = relativeTime(latest.atMillis)
        binding.payoutFeedRow.setOnClickListener { showPayoutFeedSheet() }

        // The overlapping circles, one initial per recent payout. Drawn from
        // the feed's own masked names rather than stock faces, so three
        // circles means three real payouts - a cell with nothing behind it is
        // hidden instead of filled with a placeholder.
        listOf(
            binding.payoutAvatar1, binding.payoutAvatar2, binding.payoutAvatar3
        ).forEachIndexed { index, avatar ->
            val initial = entries.getOrNull(index)?.name.orEmpty().firstOrNull()
            if (initial == null) {
                avatar.visibility = View.GONE
            } else {
                avatar.visibility = View.VISIBLE
                avatar.text = initial.toString()
            }
        }
    }

    /**
     * The whole feed, in a sheet. Rows are inflated rather than adapted: the
     * fetch is capped at ten, and a RecyclerView plus its adapter would be
     * more machinery than that justifies.
     *
     * The rows are FETCHED here rather than handed in. The live listener
     * holds only the three entries Home's row draws, because a listener is
     * billed a read per document in its window every time a fresh process
     * attaches it - ten reads on every launch, for every user, to render one
     * line. The other seven are worth reading when somebody asks to see them,
     * and worth nothing on the launches where nobody does.
     */
    private fun showPayoutFeedSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_payout_feed, null)
        val rows = view.findViewById<ViewGroup>(R.id.payoutSheetRows)

        // The sheet opens straight away and fills in - the alternative is a
        // tap that appears to do nothing while the fetch is in flight.
        val dialog = BottomSheetDialog(requireContext()).apply {
            setContentView(view)
            // The sheet paints its own rounded background; the default white
            // one would show as a band behind the corners.
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val entries = mainViewModel.fullPayoutFeed()
                .ifEmpty { mainViewModel.payoutFeed.value.orEmpty() }
            if (!dialog.isShowing) return@launch

            rows.removeAllViews()
            entries.forEach { entry ->
                val row = layoutInflater.inflate(R.layout.item_payout_feed, rows, false)
                row.findViewById<TextView>(R.id.payoutItemText).text =
                    getString(R.string.payout_feed_row, entry.name, entry.label)
                row.findViewById<TextView>(R.id.payoutItemTime).text =
                    relativeTime(entry.atMillis)
                rows.addView(row)
            }
        }
    }

    /**
     * "10m ago". Measured against the server clock, since approvedAt is a
     * server timestamp - on a device with a wrong clock, device time would
     * put recent payouts in the future.
     */
    private fun relativeTime(atMillis: Long): String {
        val elapsed = (ServerClock.now() - atMillis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)

        return when {
            minutes < 1 -> getString(R.string.time_just_now)
            minutes < 60 -> getString(R.string.time_minutes_ago, minutes)
            hours < 24 -> getString(R.string.time_hours_ago, hours)
            else -> getString(R.string.time_days_ago, days)
        }
    }

    private fun setDebugControlsEnabled(enabled: Boolean) {
        val binding = _binding ?: return
        binding.debugBoostButton.isEnabled = enabled
        binding.debugXpBoostButton.isEnabled = enabled
    }

    private fun navigateToRewards() {
        try {
            findNavController().navigate(R.id.navigation_rewards, null, defaultNavOptions())
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to rewards: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_rewards
        }
    }

    /**
     * Referral has no destination of its own - it is the same dialog the
     * activity shows on first run, reached here on demand.
     */
    private fun showReferralDialog() {
        ReferralDialogFragment().show(parentFragmentManager, "ReferralDialog")
    }

    private fun defaultNavOptions() = NavOptions.Builder()
        .setEnterAnim(R.anim.fade_in)
        .setExitAnim(R.anim.fade_out)
        .setPopEnterAnim(R.anim.fade_in)
        .setPopExitAnim(R.anim.fade_out)
        .build()

    private fun navigateToDetails(type: String) {
        try {
            val action = HomeFragmentDirections.actionHomeToDetails(type)
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()
                
            findNavController().navigate(action, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to details: ${e.message}")
        }
    }

    private fun navigateToRedemption() {
        try {
            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.slide_in_up)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.slide_out_down)
                .build()

            findNavController().navigate(R.id.navigation_redemption, null, navOptions)
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to redemption: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_redemption
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 