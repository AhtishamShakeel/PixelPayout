package com.example.pixelpayout.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import coil.load
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
import com.example.pixelpayout.ui.main.showGameChooser
import com.example.pixelpayout.ui.play.PlayFragment
import com.example.pixelpayout.ui.profile.ProfileFragment
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.startLoading
import com.example.pixelpayout.utils.stopLoading
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.bottomsheet.BottomSheetDialog
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

/** Device-local Home state; see updateGuideVisibility. Keyed per account. */
private const val HOME_PREFS = "home"
private const val KEY_HAS_REDEEMED = "has_redeemed_"

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
        // Goal progress lives in server state rather than in a snapshot, so
        // coming back from a game or a quiz is the moment to re-read it. The
        // standings used to be re-read here too; the board moved to Earn, and
        // that tab now refreshes it.
        mainViewModel.refreshDailyGoals()
    }
    
    override fun onPause() {
        super.onPause()
        // Stop the timer when fragment is paused
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun observeViewModel(){
        mainViewModel.firstRedeemFinished.observe(viewLifecycleOwner) { finished ->
            binding.rewardTitle.setText(if (finished) R.string.home_your_reward else R.string.home_first_reward)
            updateGuideVisibility()
        }
        // Fills toward the cheapest redemption not yet affordable in the
        // chosen game. No target means the bar is hidden rather than full.
        mainViewModel.starsCard.observe(viewLifecycleOwner) { card ->
            val next = card?.next
            if (next == null) {
                binding.nextTierGroup.visibility = View.GONE
                binding.rewardAmount.setRewardAmount(card?.redeemable?.amount
                    ?: mainViewModel.preferredGame.value?.displayName
                    ?: getString(R.string.home_choose_reward))
            } else {
                binding.nextTierGroup.visibility = View.VISIBLE
                binding.rewardAmount.setRewardAmount(next.title)
                binding.redemptionProgress.progress = next.percent
                val cost = formatCount(next.pointsCost)
                binding.balanceTarget.setStarText(
                    getString(R.string.home_unlock, cost),
                    emphasise = getString(R.string.home_star_cost, cost),
                    emphasisColor = R.color.stars_accent
                )
                binding.balanceCurrent.setStarText(getString(R.string.home_star_ratio, formatCount(next.pointsHeld), cost))
            }
            renderRedeemButton(card?.redeemable)
        }

        mainViewModel.preferredGame.observe(viewLifecycleOwner) { game ->
            // Always on the card, beside Redeem: "Change" once a currency is
            // chosen, "Choose" before.
            binding.starsGameSwitch.setText(if (game != null) R.string.home_change else R.string.home_choose)
            // A new Coil request clears any previous game's image, including
            // when the catalogue has no artwork or the download fails.
            binding.rewardArtwork.load(game?.currencyImageUrl?.takeIf { it.isNotBlank() } ?: game?.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_gift)
                error(R.drawable.ic_gift)
                fallback(R.drawable.ic_gift)
            }
            binding.rewardArtwork.contentDescription = game?.displayName
        }

        // First run, or the chosen game was switched off in the console.
        mainViewModel.needsGameChoice.observe(viewLifecycleOwner) { needs ->
            if (needs) showGameChooser(required = true)
        }

        mainViewModel.levelProgress.observe(viewLifecycleOwner) { progress ->
            binding.levelTitle.text = getString(R.string.level_card_title, progress.level)
            binding.levelBadgeNumber.text = progress.level.toString()

            // The way into the ladder becomes an errand while stars are owed.
            // Level bonuses are released by a rewarded ad now (see
            // claimLevelReward), and a level-up happens inside a game or quiz
            // that has since closed - so without this the only sign that
            // something is waiting would be a toast the player has dismissed.
            val owed = progress.pendingLevelRewards.size
            renderLevelRewardsButton(progress, owed)

            // Progress and the next bonus remain visible while the button
            // offers any previously earned, unclaimed stars.
            renderLevelBar(progress)
            renderLevelCaption(progress)
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

        mainViewModel.pendingRedemptions.observe(viewLifecycleOwner) {
            renderPending(it)
            updateGuideVisibility()
        }

        mainViewModel.payoutFeed.observe(viewLifecycleOwner) { renderPayoutFeed(it) }

        mainViewModel.dailyGoals.observe(viewLifecycleOwner) { renderGoals(it) }

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

        // Both of these land on Earn, so both follow the same switch the
        // bottom bar does. Leaving a tile here that opens an empty screen
        // would undo the point of hiding the tab, and this one is worse than
        // the tab: it is the gold tile, the most promising thing in the row.
        //
        // The section survives - Play and Quizzes still earn - so only the
        // offer tile and the "View all" link that points past it go. The row
        // is weighted columns, so two tiles simply fill it.
        mainViewModel.offerwallAvailable.observe(viewLifecycleOwner) { available ->
            val binding = _binding ?: return@observe
            // The Offers & Tournament tile stays either way: the tournament
            // is on Earn whether or not any offerwall is.
            binding.earnAction.isVisible = available
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

        val availability = if (remaining > 0) {
            resources.getQuantityString(R.plurals.quizzes_left, remaining, remaining)
        } else {
            resetCountdownText()
        }
        binding.quizTile.contentDescription = getString(R.string.home_quiz_accessibility, availability)
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
            // Each illustrated tile keeps its existing destination.
            playTile.setOnClickListener { navigateToGame() }
            quizTile.setOnClickListener { navigateToQuizzes() }

            // The activity hint and the Offers & Tournament tile both open
            // Earn, which holds the tournament card and the offerwalls.
            earnAction.setOnClickListener { navigateToRewards() }
            offerCard.setOnClickListener { navigateToRewards() }
            referAction.setOnClickListener { navigateToReferral() }

            streakClaimButton.setOnClickListener { confirmStreakClaim() }
            goalsClaimButton.setOnClickListener { confirmGoalClaim() }

            levelRewardsButton.setOnClickListener { openLevelRewards() }

            btnPayout.setOnClickListener { onRedeemButton() }
            starsGameSwitch.setOnClickListener { showGameChooser(required = false) }
        }
    }

    /**
     * "See rewards" while nothing is affordable;
     * "Redeem 60 UC now" once something is. The action updates with the balance.
     */
    private fun renderRedeemButton(redeemable: MainViewModel.Redeemable?) {
        binding.btnPayout.text = if (redeemable == null) getString(R.string.home_see_rewards)
            else getString(R.string.home_redeem_now, redeemable.amount)
    }

    /**
     * Straight into the redeem sheet when there is something to redeem,
     * otherwise the Wallet.
     *
     * The sheet is opened BY Wallet, not here: its results (track the order,
     * first redeem already taken) are listened for there, and a sheet shown
     * over Home would deliver them to nobody. So Home leaves a request and
     * switches tab - the same handoff the pending-redemption row uses.
     */
    private fun onRedeemButton() {
        val redeemable = mainViewModel.starsCard.value?.redeemable
        if (redeemable != null) {
            parentFragmentManager.setFragmentResult(
                RedemptionFragment.RESULT_OPEN_REDEEM,
                bundleOf(
                    RedemptionFragment.KEY_GAME_ID to redeemable.game.id,
                    RedemptionFragment.KEY_FIRST_REDEEM to redeemable.viaFirstRedeem
                )
            )
        }
        requireActivity()
            .findViewById<View>(R.id.navigation_redemption)
            ?.performClick()
    }

    private var gameChooser: Dialog? = null

    /** The currency chooser; see [com.example.pixelpayout.ui.main.showGameChooser]. */
    private fun showGameChooser(required: Boolean) {
        if (gameChooser?.isShowing == true) return
        gameChooser = showGameChooser(mainViewModel, required)
    }

    /**
     * The way into the ladder, which becomes a claim button while stars are
     * owed: solid gold, quoting the total waiting, against the quiet outline
     * it wears the rest of the time.
     *
     * Every property is set in BOTH directions. This view is rebound on every
     * emission, so a one-way change would leave the card gold for the rest of
     * the session after the queue emptied.
     */
    private fun renderLevelRewardsButton(progress: MainViewModel.LevelProgress, owed: Int) {
        val rewards = mainViewModel.levelCurve.value?.levelRewards.orEmpty()
        val stars = progress.pendingLevelRewards.sumOf { rewards[it] ?: 0 }
        if (owed > 0 && stars > 0) {
            binding.levelRewardsButton.setStarText(getString(R.string.level_rewards_button_claim, stars))
        } else {
            binding.levelRewardsButton.setText(R.string.home_view_levels)
        }
    }

    /** The bar and the two XP figures under it. */
    private fun renderLevelBar(progress: MainViewModel.LevelProgress) {
        when {
            progress.isMaxLevel -> {
                // No next level to fill toward. A full bar says "nothing left
                // to earn here", which is the truth; an empty one would read
                // as no progress at all.
                binding.levelProgressBar.progress = 100
                binding.levelXpCurrent.text = ""
            }

            // The curve is fetched once per session and can still be in
            // flight, or have failed. Level is known either way; the XP
            // figures are not, so they are left blank rather than shown as
            // 0 / 0.
            progress.xpForNextLevel <= 0 -> {
                binding.levelProgressBar.progress = 0
                binding.levelXpCurrent.text = ""
            }

            else -> {
                binding.levelProgressBar.progress =
                    (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
                binding.levelXpCurrent.text = getString(R.string.home_xp_full_ratio, formatCount(progress.xpIntoLevel), formatCount(progress.xpForNextLevel))
            }
        }
    }

    /** The next level's star bonus; pending claims remain on the action button. */
    private fun renderLevelCaption(progress: MainViewModel.LevelProgress) {
        binding.levelRewardContext.isVisible = true
        binding.levelRewardContext.setText(R.string.home_next_level)
        when {
            progress.isMaxLevel -> {
                binding.levelReward.setText(R.string.level_reached_max)
                binding.levelRewardContext.isVisible = false
            }
            progress.xpForNextLevel <= 0 -> {
                binding.levelReward.text = ""
                binding.levelRewardContext.isVisible = false
            }
            else -> {
                if (progress.nextLevelReward > 0) {
                    binding.levelReward.setStarText(
                        getString(R.string.home_level_bonus, formatCount(progress.nextLevelReward))
                    )
                } else {
                    binding.levelReward.setText(R.string.home_no_star_bonus)
                }
            }
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
     * Where the check-in card sits. Unclaimed, it is today's first errand and
     * goes above Start earning; once claimed it has nothing left to ask, and
     * drops below Today's bonus. Moved rather than duplicated, so there is
     * one card and one set of bindings, and only when its place actually
     * changes - this runs on every streak emission.
     */
    private fun placeStreakCard(claimedToday: Boolean) {
        val binding = _binding ?: return
        val card = binding.streakCard
        val parent = card.parent as? ViewGroup ?: return
        val anchor = if (claimedToday) binding.goalsCard else binding.quickActions
        val current = parent.indexOfChild(card)
        val anchorIndex = parent.indexOfChild(anchor)
        val inPlace = if (claimedToday) current == anchorIndex + 1 else current == anchorIndex - 1
        if (inPlace) return
        parent.removeView(card)
        val target = parent.indexOfChild(anchor).let { if (claimedToday) it + 1 else it }
        parent.addView(card, target)
    }

    /**
     * How it works is for accounts that have not redeemed yet. Once one has -
     * the discounted first redeem, a normal pack, or a first redeem refused
     * because the UID already had one - the card goes for good, and the
     * pending row takes its place at the top while an order is open.
     *
     * The user document only records the FIRST-REDEEM outcome, so a plain
     * first redemption is recognised by its pending order instead, and
     * remembered on this device so the card does not return once that order
     * settles. Only a hint about which card to show; nothing is gated on it.
     */
    private fun updateGuideVisibility() {
        val binding = _binding ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val prefs = requireContext().getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
        val key = "$KEY_HAS_REDEEMED$uid"
        val pendingNow = (mainViewModel.pendingRedemptions.value?.count ?: 0) > 0
        if (uid != null && pendingNow && !prefs.getBoolean(key, false)) {
            prefs.edit().putBoolean(key, true).apply()
        }
        val redeemed = mainViewModel.firstRedeemFinished.value == true ||
            pendingNow ||
            (uid != null && prefs.getBoolean(key, false))
        binding.earningGuide.isVisible = !redeemed
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

        placeStreakCard(claimedToday = rewardedToday)

        binding.streakCard.contentDescription = if (streak.isAlive(todayUtc) && streak.count > 0) {
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

            val claimed = filled && !isNext
            val today = (rewardedToday && index == claimPosition - 1) || isNext
            cell.tag = when { today -> "reference_day_active"; claimed -> "reference_day_claimed"; else -> "reference_day" }
            cell.setBackgroundResource(when {
                today -> R.drawable.bg_home_day_active
                claimed -> R.drawable.bg_home_day_claimed
                else -> R.drawable.bg_home_day
            })
            // A CLAIMED day drops its figure for a tick. What day three paid
            // stops being information the moment it is banked, and seven spent
            // figures compete with the days still to come, which are the only
            // ones the strip is really for.
            //
            // Everywhere else the label carries the REWARD TYPE and nothing
            // else - the box already said reached, in play, or ahead. A Stars
            // day is gold, as a star is on every screen in this app; an XP day
            // is neutral, and leans brighter while it is the one in play.
            // No "Day N" line: the strip's order already says which day is
            // which, and the content description still names it.
            if (claimed) {
                cell.text = getString(R.string.home_claimed_day,
                    getString(if (today) R.string.home_today else R.string.home_day_done))
                cell.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
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
            cell.contentDescription = getString(R.string.home_checkin_day_desc, index + 1,
                reward?.let { describeReward(it) }.orEmpty(), getString(when {
                    claimed -> R.string.home_claimed
                    isNext -> R.string.home_available
                    else -> R.string.home_upcoming
                }))
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
        binding.streakFooter.isVisible = rewardedToday
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

    /** Reward lines underneath the day label in the seven-day strip. */
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

    /** Thousands separators - a rank of 24247 is unreadable without them. */
    private fun formatCount(value: Int): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

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
            return
        }
        binding.goalsHeader.visibility = View.VISIBLE
        binding.goalsCard.visibility = View.VISIBLE

        binding.goalsCard.contentDescription =
            getString(R.string.goals_done_count, goals.doneCount, goals.goals.size)
        val bonus = formatCount(goals.bonusPoints)
        binding.goalsBonus.setStarText(
            getString(R.string.goals_bonus, bonus),
            emphasise = bonus,
            emphasisColor = R.color.stars_accent
        )

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
        binding.goalsBonus.isVisible = !binding.goalsClaimButton.isVisible
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
        binding.goalsSubtitle.text = if (settled) getString(R.string.goals_subtitle_claimed)
            else resources.getQuantityString(R.plurals.home_finish_tasks, goals.goals.size, goals.goals.size)
        binding.goalsCard.designHeight = if (settled) 90f else 267f
        binding.goalsCard.minimumHeight = ((if (settled) 80 else 190) * resources.displayMetrics.density).toInt()
        binding.goalRows.visibility = if (settled) View.GONE else View.VISIBLE
        if (settled) return

        val rows = listOf(
            Triple(binding.goalRow1, binding.goalImage1, binding.goalLabel1),
            Triple(binding.goalRow2, binding.goalImage2, binding.goalLabel2),
            Triple(binding.goalRow3, binding.goalImage3, binding.goalLabel3)
        )
        val progressViews = listOf(
            binding.goalProgress1, binding.goalProgress2, binding.goalProgress3
        )
        val rings = listOf(binding.goalRing1, binding.goalRing2, binding.goalRing3)
        val actions = listOf(binding.goalAction1, binding.goalAction2, binding.goalAction3)

        rows.forEachIndexed { index, (row, mark, label) ->
            val goal = goals.goals.getOrNull(index)
            if (goal == null) {
                row.visibility = View.GONE
                return@forEachIndexed
            }
            row.visibility = View.VISIBLE

            mark.tag = if (goal.kind == DailyGoalEngine.KIND_PLAY_GAMES) "goal_game" else "goal_quiz"
            mark.invalidate()
            val action = actions[index]
            action.setText(when {
                goal.done -> R.string.home_done
                goal.kind == DailyGoalEngine.KIND_PLAY_GAMES -> R.string.home_play
                else -> R.string.home_start
            })
            action.contentDescription = "${goalLabel(goal)}: ${action.text}"
            action.setOnClickListener { openGoalTarget(goal.kind) }

            // The illustration stays inside the progress ring in every state.
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
        // Guarded like openLevelRewards: two taps in quick succession would
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

        // "4 Pending Redeems · ready in 41h" - the count is the subject, and
        // the time is the oldest order's, the one that settles first.
        val subject = resources.getQuantityString(R.plurals.pending_redeem_count, pending.count, pending.count)

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
     * Referral lives on Profile, which holds the whole picture: the code, the
     * share sheet, the invited/qualified/paid funnel and the list of people
     * who actually joined. The dialog this row used to open carries only the
     * code, so it was the smaller half of what the row promises.
     *
     * The flag asks Profile to scroll down to the invite block rather than
     * landing on the account header and leaving the user to find it.
     */
    private fun navigateToReferral() {
        try {
            val args = bundleOf(ProfileFragment.ARG_SCROLL_TO_REFERRAL to true)
            findNavController().navigate(R.id.navigation_profile, args, defaultNavOptions())
        } catch (e: Exception) {
            Log.e("Navigation", "Error navigating to referral: ${e.message}")
            (activity as? MainActivity)?.binding?.bottomNav?.selectedItemId = R.id.navigation_profile
        }
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

    override fun onDestroyView() {
        // Otherwise a rotation leaks the window, and the new view's observer
        // could not open a replacement past the isShowing guard.
        gameChooser?.dismiss()
        gameChooser = null
        super.onDestroyView()
        _binding = null
    }
}
