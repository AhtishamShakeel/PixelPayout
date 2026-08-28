package com.example.pixelpayout.ui.home

import android.app.Dialog
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
import com.pixelpayout.R
import com.pixelpayout.databinding.FragmentHomeBinding
import com.example.pixelpayout.ui.main.MainActivity
import com.example.pixelpayout.ui.main.MainViewModel
import com.example.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.example.pixelpayout.ui.play.PlayFragment
import com.example.pixelpayout.ui.quiz.QuizListViewModel
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.ServerClock
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MILLIS_PER_DAY = 86_400_000L

/** Matches STREAK_CYCLE_DAYS server-side; the strip draws one cycle. */
private const val STREAK_CYCLE_DAYS = 7

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels()
    private val quizViewModel: QuizListViewModel by activityViewModels()
    
    /** The server's reward table, fetched once so the strip can show it. */
    private var cycleRewards: List<UserRepository.StreakDayReward> = emptyList()
    private var streakClaimInFlight = false
    /** What the next claim would pay, and which day, for the dialog. */
    private var pendingRewardLabel: String? = null
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
        loadStreakConfig()
        observeViewModel()

    }
    
    override fun onResume() {
        super.onResume()
        // Start the timer to update the quiz status if needed
        timerHandler.post(timerRunnable)
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
                binding.balanceTarget.text =
                    getString(R.string.balance_target, next.pointsCost)
                // Derived rather than read from points separately: taking both
                // halves of the ratio from the same snapshot stops the bar and
                // the numbers under it disagreeing mid-update.
                binding.balanceRatio.text = getString(
                    R.string.balance_ratio,
                    next.pointsCost - next.pointsShort,
                    next.pointsCost
                )
                binding.nextTierText.text =
                    getString(R.string.next_tier, next.pointsShort, next.title)
            }
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.levelChip.text = progress.level.toString()
            binding.levelTitle.text = getString(R.string.level_card_title, progress.level)

            when {
                progress.isMaxLevel -> {
                    // No next level to fill toward. A full bar says "nothing
                    // left to earn here", which is the truth; an empty one
                    // would read as no progress at all.
                    binding.levelProgressBar.progress = 100
                    binding.levelXpRatio.setText(R.string.level_xp_max)
                    binding.levelFooter.setText(R.string.level_reached_max)
                }

                // The curve is fetched once per session and can still be in
                // flight, or have failed. Level is known either way; the XP
                // figures are not, so they are left blank rather than shown
                // as 0 / 0.
                progress.xpForNextLevel <= 0 -> {
                    binding.levelProgressBar.progress = 0
                    binding.levelXpRatio.text = ""
                    binding.levelFooter.text = ""
                }

                else -> {
                    binding.levelProgressBar.progress =
                        (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
                    binding.levelXpRatio.text = getString(
                        R.string.level_xp_ratio,
                        progress.xpIntoLevel,
                        progress.xpForNextLevel
                    )
                    binding.levelFooter.text = getString(
                        R.string.level_to_next,
                        progress.xpForNextLevel - progress.xpIntoLevel,
                        progress.level + 1
                    )
                }
            }
        }

        // The badge only appears while a buff is running; the countdown is
        // driven by the existing per-second timer below.
        mainViewModel.activeBuff.observe(viewLifecycleOwner) { updateBuffBadge() }
        mainViewModel.activeXpBuff.observe(viewLifecycleOwner) { updateBuffBadge() }

        mainViewModel.streak.observe(viewLifecycleOwner) { renderStreak(it) }

        mainViewModel.pendingRedemptions.observe(viewLifecycleOwner) { pending ->
            // Nothing waiting is the usual state, and an empty row saying so
            // would be noise on every screen for every user.
            if (pending.count == 0) {
                binding.pendingRedeemRow.visibility = View.GONE
            } else {
                binding.pendingRedeemRow.visibility = View.VISIBLE
                binding.pendingRedeemValue.text =
                    getString(R.string.pending_redeem_value, pending.points)
            }
        }

        mainViewModel.payoutFeed.observe(viewLifecycleOwner) { renderPayoutFeed(it) }

        // Observe quiz attempts
        quizViewModel.dailyAttempts.observe(viewLifecycleOwner) { attempts ->
            updateQuizStatusText()
        }
        
        // Observe next reset time to update timer when needed
        quizViewModel.nextResetTime.observe(viewLifecycleOwner) { nextResetTime ->
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
        val attempts = quizViewModel.dailyAttempts.value ?: 0
        val remaining = maxOf(quizViewModel.MAX_DAILY_ATTEMPTS - attempts, 0)

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
        val nextResetTime = quizViewModel.nextResetTime.value
            ?: return getString(R.string.quiz_reset_unknown)

        val remainingMs = nextResetTime - quizViewModel.getCurrentServerTime()
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

            earnAction.setOnClickListener { navigateToRewards() }
            // Both the card and its button land on Earn; tapping the card body
            // is what most people try first.
            offerCard.setOnClickListener { navigateToRewards() }
            btnOffer.setOnClickListener { navigateToRewards() }
            referAction.setOnClickListener { showReferralDialog() }

            streakClaimButton.setOnClickListener { confirmStreakClaim() }

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

        // Every cell shows what that day of the cycle pays, claimed or not, so
        // the week ahead is legible rather than a row of blanks.
        streakCells(binding).forEachIndexed { index, cell ->
            val reward = cycleRewards.getOrNull(index)
            val filled = index < done

            cell.setBackgroundResource(
                if (filled) R.drawable.bg_streak_cell_done
                else R.drawable.bg_streak_cell_todo
            )
            cell.text = reward?.let { cellLabel(it) }.orEmpty()
            cell.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when {
                        filled -> R.color.on_gold
                        reward != null && reward.points > 0 -> R.color.gold
                        else -> R.color.text_faint
                    }
                )
            )
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
        binding.streakClaimButton.isEnabled = !streakClaimInFlight
        // A day whose streak already moved on but paid nothing is a retry, and
        // saying so is the difference between "come back tomorrow" and "have
        // another go".
        binding.streakClaimButton.setText(
            if (streakMovedToday) R.string.streak_try_again else R.string.streak_claim
        )

        pendingClaimDay = claimPosition
        pendingRewardLabel = claimReward?.let { describeReward(it) }
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

        val view = layoutInflater.inflate(R.layout.dialog_streak_claim, null)
        val dialog = Dialog(requireContext(), R.style.CustomDialogTheme).apply {
            setContentView(view)
        }

        view.findViewById<TextView>(R.id.streakDialogTitle).text =
            getString(R.string.streak_dialog_title, pendingClaimDay)

        val reward = pendingRewardLabel
        val rewardView = view.findViewById<TextView>(R.id.streakDialogReward)
        if (reward != null) {
            rewardView.text = getString(R.string.streak_dialog_reward, reward)
        } else {
            // The table has not arrived; better to say nothing than a figure
            // the claim might not pay.
            rewardView.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.streakDialogMessage)
            .setText(R.string.streak_dialog_message_short)

        view.findViewById<View>(R.id.streakDialogWatch).setOnClickListener {
            dialog.dismiss()
            playAdThenClaim()
        }
        view.findViewById<View>(R.id.streakDialogCancel).setOnClickListener {
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
        binding.streakClaimButton.isEnabled = false

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
     * The reward table, fetched once per screen. Without it the strip has no
     * figures to show, so the cells render empty rather than guessing.
     */
    private fun loadStreakConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cycle = mainViewModel.getStreakConfig()
            if (cycle.isEmpty()) return@launch
            cycleRewards = cycle
            mainViewModel.streak.value?.let { renderStreak(it) }
        }
    }

    /**
     * The three most recent approved payouts. Names arrive already masked -
     * the raw ones are never in this collection, so there is nothing here to
     * get wrong client-side.
     */
    private fun renderPayoutFeed(entries: List<UserRepository.PayoutFeedEntry>) {
        val binding = _binding ?: return

        binding.payoutFeedCard.visibility =
            if (entries.isEmpty()) View.GONE else View.VISIBLE

        val rows = listOf(
            Triple(binding.payoutRow1, binding.payoutRow1Text, binding.payoutRow1Time),
            Triple(binding.payoutRow2, binding.payoutRow2Text, binding.payoutRow2Time),
            Triple(binding.payoutRow3, binding.payoutRow3Text, binding.payoutRow3Time)
        )

        rows.forEachIndexed { index, (row, text, time) ->
            val entry = entries.getOrNull(index)
            if (entry == null) {
                row.visibility = View.GONE
                return@forEachIndexed
            }
            row.visibility = View.VISIBLE
            text.text = getString(R.string.payout_feed_row, entry.name, entry.optionTitle)
            time.text = relativeTime(entry.atMillis)
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