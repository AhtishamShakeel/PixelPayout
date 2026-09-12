package com.example.pixelpayout.ui.main

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.example.pixelpayout.utils.UserPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.pixelpayout.R
import com.example.pixelpayout.data.repository.UserRepository
import com.pixelpayout.databinding.ActivityMainBinding
import com.example.pixelpayout.ui.dialogs.ReferralDialogFragment
import com.example.pixelpayout.ui.quiz.QuizListViewModel
import com.example.pixelpayout.ui.redemption.ReferralViewModel
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.utils.ServerClock
import com.example.pixelpayout.utils.showLeaderboardPrize
import com.example.pixelpayout.utils.showPendingLevelRewards
import com.example.pixelpayout.utils.showRedemptionResult
import com.example.pixelpayout.ui.dialogs.NoInternetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    private lateinit var userPreferences: UserPreferences
    private val quizViewModel: QuizListViewModel by viewModels()
    private lateinit var referralViewModel: ReferralViewModel
    private lateinit var connectivityCheck: AndroidConnectivityCheck
    private var noInternetDialog: NoInternetDialog? = null
    private var isConnectedToInternet = true

    // False until the level ring has painted a real value once; the first
    // paint jumps straight to the value, later ones animate.
    private var hasShownLevelRing = false


    /**
     * True while the level-reward celebration is on screen.
     *
     * The announcement is driven by a LiveData that emits on every user
     * snapshot, and the preference that gates it is read asynchronously - so
     * without this a second emission arriving before the first write landed
     * would stack a second dialog on the first.
     */
    private var announcingLevelRewards = false

    /** The same guard, for the payout-settled dialog. */
    private var announcingRedemption = false

    /** Guards the weekly prize dialog the same way the two above are guarded. */
    private var announcingLeaderboardPrize = false
    
    // Cache for Lottie compositions
    private val lottieCache = mutableMapOf<Int, LottieComposition>()

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(UserRepository(), UserPreferences(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        userPreferences = UserPreferences(this)
        connectivityCheck = AndroidConnectivityCheck(this)

        setupConnectivityCheck()
        quizViewModel.loadCachedQuizzes(this)

        // Observe categories and preload animations efficiently
        quizViewModel.categories.observe(this) { categories ->
            lifecycleScope.launch(Dispatchers.IO) {
                categories.forEach { category ->
                    // Skip if already cached
                    if (lottieCache.containsKey(category.lottieAnimationResId)) {
                        return@forEach
                    }
                    
                    try {
                        // Load composition in background
                        val result = withContext(Dispatchers.Main) {
                            LottieCompositionFactory.fromRawRes(
                                this@MainActivity, 
                                category.lottieAnimationResId
                            ).addListener { composition ->
                                composition?.let {
                                    lottieCache[category.lottieAnimationResId] = it
                                    Log.d("LottiePreload", "Cached animation for category: ${category.lottieAnimationResId}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LottiePreload", "Failed to load animation: ${e.message}")
                    }
                }
                Log.d("LottiePreload", "Finished preloading ${categories.size} animations")
            }
        }

        Log.d("ReferralDebug", "Initializing ReferralViewModel...")
        lifecycleScope.launch {
            referralViewModel = ReferralViewModel(UserRepository())
            checkAndShowReferralPopup()
        }

        setupToolbar()
        setupNavigation()
        observeViewModel()
        observeLevelRewards()
        observeRedemptionResults()
        observeLeaderboardPrize()
    }

    /**
     * Re-asks whether there are level rewards to announce.
     *
     * THREE TRIGGERS, ALL CHEAP, because any one of them alone has a hole in
     * it. The two observers catch the queue changing while the player is
     * looking at a tab, and the curve landing after it. onResume catches the
     * ordinary case - a level crossed inside the game, whose snapshot arrived
     * while this activity was stopped and its MediatorLiveData detached, so by
     * the time anyone is watching again there is no emission left to hear.
     *
     * Everything it reads is already in memory: the pending queue rides the
     * user snapshot and the amounts come from the published curve, so asking
     * repeatedly costs no reads.
     */
    private fun maybeAnnounceLevelRewards() {
        if (announcingLevelRewards) return

        val progress = viewModel.levelProgress.value
        if (progress == null) {
            Log.d(TAG_LEVEL_ANNOUNCE, "No level progress yet")
            return
        }

        val pending = progress.pendingLevelRewards
        if (pending.isEmpty()) {
            // Nothing is owed. The normal state, and also what a server that
            // failed to queue the level looks like - hence the log.
            Log.d(TAG_LEVEL_ANNOUNCE, "Nothing pending at level ${progress.level}")
            return
        }

        val rewards = viewModel.levelCurve.value?.levelRewards.orEmpty()
        // The curve observer below re-asks when it lands.
        if (rewards.isEmpty()) {
            Log.d(TAG_LEVEL_ANNOUNCE, "Curve not loaded; pending=$pending")
            return
        }

        lifecycleScope.launch {
            val announced = userPreferences.announcedLevelRewards.firstOrNull().orEmpty()

            // ANYTHING IN THE QUEUE WE HAVE NOT MENTIONED, rather than a
            // level compared against a high-water mark. The mark only ever
            // rose, so a queue whose maximum sat at or below it never spoke
            // again - which is what happens as soon as a level is claimed
            // (the queue empties from the bottom) or an account's XP is reset
            // and climbs back through levels already announced. The set has
            // no such trapdoor: a level is either owed-and-unmentioned or it
            // is not.
            if (pending.all { it in announced }) {
                Log.d(TAG_LEVEL_ANNOUNCE, "Already announced $pending")
                return@launch
            }
            if (announcingLevelRewards || isFinishing) return@launch

            announcingLevelRewards = true
            val shown = showPendingLevelRewards(
                level = progress.level,
                pendingLevels = pending,
                rewards = rewards,
                onDismissed = { announcingLevelRewards = false },
                onClaim = { openLevelRewards() }
            )

            if (shown) {
                // Recorded only on a dialog that really appeared, so a run
                // that bailed out can still announce later.
                //
                // The live queue, not a union with what was already there:
                // that is what prunes levels which have since been claimed
                // and keeps this from growing for the life of the install.
                userPreferences.setAnnouncedLevelRewards(pending.toSet())
            } else {
                // The dialog refused: the published curve pays nothing for
                // any pending level, so there is no figure to show. Logged
                // because it is otherwise indistinguishable from the popup
                // being broken.
                Log.w(
                    TAG_LEVEL_ANNOUNCE,
                    "Dialog declined; pending=$pending rewards=$rewards"
                )
                announcingLevelRewards = false
            }
        }
    }

    private fun observeLevelRewards() {
        viewModel.levelProgress.observe(this) { maybeAnnounceLevelRewards() }
        viewModel.levelCurve.observe(this) { maybeAnnounceLevelRewards() }
    }

    /**
     * Tells the user about a payout that was settled while they were away.
     *
     * MOVED UP FROM HomeFragment, which could only speak when Home happened to
     * be the visible tab. Approval is a manual action on our side and almost
     * always lands with the app closed, so whichever screen they open next is
     * arbitrary - and on any other one the pending row simply vanished and the
     * balance quietly changed, with nothing saying that the thing they had been
     * waiting for actually happened. A rejection mattered more still: their
     * stars came back and nothing on screen explained why.
     *
     * Two triggers, for the same reason the level announcement has three. The
     * observer catches a settlement landing while they are watching; onResume
     * catches the ordinary case, where it landed while this activity was
     * stopped and there is no emission left to hear by the time anyone is.
     *
     * "Once" is a stored timestamp rather than a set of seen ids: one
     * comparison, it never grows, and anything settled before it is by
     * definition already known.
     */
    private fun maybeAnnounceRedemptionResult() {
        if (announcingRedemption) return

        val resolved = viewModel.resolvedRedemptions.value.orEmpty()
        if (resolved.isEmpty()) return

        lifecycleScope.launch {
            val lastSeen = userPreferences.lastSeenRedemptionResolvedAt.firstOrNull() ?: 0L

            // FIRST RUN ON THIS DEVICE, WHICH IS NOT THE SAME AS "NOTHING HAS
            // HAPPENED YET", and telling the two apart is what the window
            // below is for.
            //
            // The mark is only written when something has actually resolved,
            // so on a fresh account the first thing ever to resolve was also
            // the thing that wrote the mark - and adopting unconditionally
            // meant that settlement was silently swallowed, every time. It
            // read as "the discounted order never announces", because a
            // first-redeem order is by definition the first order an account
            // places, so it was always the one that landed on this branch.
            //
            // So: anything OLD is history this install never saw and is
            // adopted silently; anything recent is news and falls through to
            // the announcement below, first settlement included.
            if (lastSeen == 0L) {
                val newestAt = resolved.maxOf { it.resolvedAtMillis }
                if (ServerClock.now() - newestAt > RESULT_FRESHNESS_MS) {
                    userPreferences.setLastSeenRedemptionResolvedAt(newestAt)
                    return@launch
                }
                // Falls through with lastSeen still 0, so every resolved entry
                // counts as unseen - the newest is announced and the rest are
                // marked with it, which is what the code below already does
                // when several settle at once.
            }

            val unseen = resolved.filter { it.resolvedAtMillis > lastSeen }
            if (unseen.isEmpty()) return@launch
            if (announcingRedemption || isFinishing) return@launch

            announcingRedemption = true
            // The repository sorts newest first, and the newest is the one
            // worth a dialog.
            val newest = unseen.first()
            val shown = showRedemptionResult(
                result = newest,
                onRedeemAgain = { openRedemption() },
                onDismissed = { announcingRedemption = false }
            )

            if (shown) {
                // Marked only on a dialog that really appeared, so a run that
                // bailed out can still announce later. Everything unseen is
                // marked, not just the one shown - if several were settled at
                // once the rest would otherwise queue up behind it.
                userPreferences.setLastSeenRedemptionResolvedAt(
                    unseen.maxOf { it.resolvedAtMillis }
                )
            } else {
                announcingRedemption = false
            }
        }
    }

    private fun observeRedemptionResults() {
        viewModel.resolvedRedemptions.observe(this) { maybeAnnounceRedemptionResult() }
    }

    /**
     * Congratulates a winner of the weekly tournament.
     *
     * THIS IS THE ONLY THING THAT SAYS A PRIZE WAS WON. The settlement runs at
     * five past midnight on Monday, credits the stars and moves on; before
     * this, the entire experience of winning a week was a balance that was
     * larger than it had been. That is a poor return for the one reward in
     * this app that has to be competed for.
     *
     * Two triggers, for the same reason the other two announcements have
     * them: the observer catches a settlement landing while the app is open -
     * rare, but it is exactly what happens to somebody playing at midnight on
     * a Sunday - and onResume catches the ordinary case, where it landed days
     * ago and there is no emission left to hear by the time anyone is
     * listening.
     *
     * "Once" is the week index rather than a timestamp: weeks are already a
     * monotonic integer, a settlement writes exactly one per account, and the
     * comparison never grows.
     *
     * A FRESHNESS WINDOW ON TOP OF THAT MARK, which the level and redemption
     * announcements do not have and do not need. The mark is local, so a
     * reinstall or a new device resets it to zero - and without the window
     * that would greet a returning user with a fanfare about a week they
     * placed in two months ago, which reads as a bug rather than as good
     * news. Anything settled inside the window is still news; anything older
     * is adopted silently.
     */
    private fun maybeAnnounceLeaderboardPrize() {
        if (announcingLeaderboardPrize) return

        val prize = viewModel.leaderboardPrize.value ?: return

        lifecycleScope.launch {
            val announced = userPreferences.lastAnnouncedLeaderboardWeek.firstOrNull() ?: 0
            if (prize.weekKey <= announced) return@launch

            val age = ServerClock.now() - prize.settledAtMillis
            if (age > PRIZE_FRESHNESS_MS) {
                // Old news. Marked as said so it is never reconsidered, but
                // never actually said.
                userPreferences.setLastAnnouncedLeaderboardWeek(prize.weekKey)
                return@launch
            }

            if (announcingLeaderboardPrize || isFinishing) return@launch

            announcingLeaderboardPrize = true
            val shown = showLeaderboardPrize(
                prize = prize,
                // What the board pays down to, so "#4 of 30 paid" is true
                // rather than invented. Falls back to the server's own cap
                // when no board has been fetched on this run.
                boardSize = viewModel.leaderboard.value?.size ?: DEFAULT_BOARD_SIZE,
                onViewBoard = { openLeaderboard() },
                onDismissed = { announcingLeaderboardPrize = false }
            )

            if (shown) {
                // Recorded only on a dialog that really appeared, so a run
                // that bailed out can still announce later.
                userPreferences.setLastAnnouncedLeaderboardWeek(prize.weekKey)
            } else {
                announcingLeaderboardPrize = false
            }
        }
    }

    private fun observeLeaderboardPrize() {
        viewModel.leaderboardPrize.observe(this) { maybeAnnounceLeaderboardPrize() }
    }

    /** "See this week's board", from the prize dialog. */
    private fun openLeaderboard() {
        val navController = (supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController ?: return

        if (navController.currentDestination?.id == R.id.leaderboardFragment) return

        try {
            navController.navigate(R.id.leaderboardFragment, null, defaultNavOptions())
        } catch (e: Exception) {
            Log.e("Navigation", "Could not open the leaderboard: ${e.message}")
        }
    }

    /**
     * "Redeem again", from the declined sheet.
     *
     * The tab rather than the sheet: the payout that was turned down is
     * usually turned down over a detail of it - a mistyped player id, the
     * wrong server - so the catalogue, where they pick the reward and enter
     * those again, is the honest place to land.
     */
    private fun openRedemption() {
        binding.bottomNav.selectedItemId = R.id.navigation_redemption
    }

    /**
     * The ladder, from wherever the player happens to be.
     *
     * A flat destination in the graph, so this works from any tab - unlike
     * HomeFragment's own version, which refuses unless Home is on top because
     * a fragment can only navigate its own controller safely.
     */
    private fun openLevelRewards() {
        val navController = (supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)
            ?.navController ?: return

        if (navController.currentDestination?.id == R.id.levelRewardsFragment) return

        try {
            navController.navigate(
                R.id.levelRewardsFragment,
                null,
                NavOptions.Builder()
                    .setEnterAnim(R.anim.fade_in)
                    .setExitAnim(R.anim.fade_out)
                    .setPopEnterAnim(R.anim.fade_in)
                    .setPopExitAnim(R.anim.fade_out)
                    .build()
            )
        } catch (e: Exception) {
            Log.e("Navigation", "Could not open level rewards: ${e.message}")
        }
    }

    /**
     * Connectivity can change while this activity is stopped, and both dialog
     * paths refuse to act during that window - showNoInternetDialog returns
     * early on a saved state. Without this, dropping the connection in the
     * background left the user looking at a live screen with no warning.
     */
    override fun onResume() {
        super.onResume()
        updateBlockingDialogState()
        // Asked here as well as from the observers below, because "the player
        // is back on a normal screen" is a lifecycle fact rather than a data
        // one - and the data it depends on usually settled while this activity
        // was stopped, so there may be no emission left to react to.
        maybeAnnounceLevelRewards()
        maybeAnnounceRedemptionResult()
        maybeAnnounceLeaderboardPrize()
    }

    private fun setupConnectivityCheck() {
        lifecycleScope.launch {
            connectivityCheck.isConnected.collect { isConnected ->
                isConnectedToInternet = isConnected
                updateBlockingDialogState()
            }
        }
    }

    private fun showNoInternetDialog() {
        if (supportFragmentManager.isStateSaved) return

        if (noInternetDialog == null) {
            noInternetDialog = NoInternetDialog()
            noInternetDialog?.show(supportFragmentManager, NoInternetDialog.TAG)
        }
    }

    private fun hideNoInternetDialog() {
        // dismissAllowingStateLoss rather than dismiss: connectivity can come
        // back while the activity is stopped, and a plain dismiss then throws
        // IllegalStateException. The dialog still goes away - only the
        // state-loss check is skipped, and there is no state here worth
        // keeping. showNoInternetDialog already guards the same window.
        noInternetDialog?.dismissAllowingStateLoss()
        noInternetDialog = null
    }

    // Function to get cached composition
    fun getCachedAnimation(resId: Int): LottieComposition? = lottieCache[resId]

    private fun setupToolbar() {
        setSupportActionBar(binding.customToolbar.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        lifecycleScope.launch {
            userPreferences.username.collect {username ->
                binding.customToolbar.usernameText.text = "Hey, ${username ?: "User"}"
            }
        }

        binding.customToolbar.pointsHeader.root.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.navigation_redemption
        }

        observeLevelRing()
    }

    /**
     * Drives the progress ring around the avatar. This is the only place the
     * level is surfaced in the toolbar, so the separate "Lv N / X XP" row and
     * its bar are no longer needed on the home screen.
     *
     * The ring shows progress through the CURRENT level, not lifetime XP, so
     * it visibly resets on each level up. At max level there is no next level
     * to fill toward, so the ring is shown full rather than empty - an empty
     * ring would read as "no progress" when the truth is the opposite.
     */
    private fun observeLevelRing() {
        val ring = binding.customToolbar.levelAvatar.levelRing
        val badge = binding.customToolbar.levelAvatar.levelBadge

        viewModel.levelProgress.observe(this) { progress ->
            badge.text = getString(R.string.level_badge_value, progress.level)

            val percent = when {
                progress.isMaxLevel -> 100
                progress.xpForNextLevel <= 0 -> 0
                else -> (progress.xpIntoLevel * 100 / progress.xpForNextLevel).coerceIn(0, 100)
            }

            // Animate only once a real value has been shown, so the ring does
            // not sweep up from zero every time the activity is recreated.
            ring.setProgressCompat(percent, hasShownLevelRing)
            hasShownLevelRing = true
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // The bar is our own view now, so there is no setupWithNavController:
        // taps navigate here, and the destination listener below moves the
        // highlight back when navigation happens some other way (system back).
        binding.bottomNav.setOnItemSelectedListener { itemId ->
            val currentDestinationId = navController.currentDestination?.id ?: 0
            if (currentDestinationId == itemId) return@setOnItemSelectedListener true

            val navOptions = NavOptions.Builder()
                .setEnterAnim(R.anim.fade_in)
                .setExitAnim(R.anim.fade_out)
                .setPopEnterAnim(R.anim.fade_in)
                .setPopExitAnim(R.anim.fade_out)
                .build()

            try {
                navController.navigate(itemId, null, navOptions)
            } catch (e: Exception) {
                // Keep the tab selected even if the destination refused us;
                // the bar staying put would be the more confusing failure.
                Log.e("Navigation", "Failed to navigate: ${e.message}")
            }
            true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNav.setSelectedItemIdSilently(destination.id)
        }

        setupOfferwallTab()
    }

    /**
     * Earn is now a permanent tab. It used to come and go with the offerwall
     * catalogue, and this is the record of why it no longer does.
     *
     * The old rule was sound for what the screen was then. The catalogue
     * lives in `config/offerwallWalls` and every entry has an `enabled` flag,
     * so a network is turned on the hour it approves us and off the hour it
     * breaks - no release either way. With everything off, the tab still sat
     * in the bar, raised on its accent disc, promising an empty screen; so it
     * was hidden, remembered across launches to stop it popping in a beat
     * after Firestore answered, and anyone standing on the screen when the
     * last wall went away was moved off it.
     *
     * What changed is the screen, not the rule: Earn now opens on the weekly
     * leaderboard, which is always there. Hiding the tab would hide the
     * tournament along with the offers, and the tournament does not depend on
     * a network approving us. The flag still decides the LIST - see
     * RewardsFragment, where an empty catalogue draws its own empty state -
     * and that is all it decides now.
     *
     * The remembered flag and the bounce-off-the-empty-tab guard went with
     * it. Both existed only to manage a tab that could disappear.
     */
    private fun setupOfferwallTab() {
        binding.bottomNav.setItemVisible(R.id.navigation_rewards, true)
    }

    private fun defaultNavOptions(): NavOptions = NavOptions.Builder()
        .setEnterAnim(R.anim.fade_in)
        .setExitAnim(R.anim.fade_out)
        .setPopEnterAnim(R.anim.fade_in)
        .setPopExitAnim(R.anim.fade_out)
        .build()

    private fun observeViewModel() {
        viewModel.points.observe(this) { points ->
            binding.customToolbar.pointsHeader.pointsText.text =
                getString(R.string.points_value, points)
        }
    }

    /**
     * Now only about connectivity.
     *
     * A second, app-wide blocking dialog used to live here, driven by the
     * checkAndResetQuizAttempts round trip - so every cold start put a spinner
     * over the whole app while the server was asked for a number the user
     * snapshot already carried. That callable is gone and so is the dialog;
     * Firestore's own offline cache covers the case it was really guarding.
     */
    private fun updateBlockingDialogState() {
        if (!isConnectedToInternet) {
            showNoInternetDialog()
        } else {
            hideNoInternetDialog()
        }
    }

    private fun checkAndShowReferralPopup() {
        val user = FirebaseAuth.getInstance().currentUser ?: return

        lifecycleScope.launch {
            val hasSeenPopup = userPreferences.hasSeenReferralPopup.firstOrNull() ?: false
            if (hasSeenPopup) {
                Log.d("ReferralDebug", "User has seen the popup. Skipping Firebase check")
                return@launch
            }
            userPreferences.setHasSeenReferralPopup(true)

            try{
                val document = FirebaseFirestore.getInstance().collection("users")
                    .document(user.uid)
                    .get()
                    .await()
                if (document.exists()){
                    val hasUsedReferral = document.getBoolean("hasUsedReferral") ?: false
                    Log.d("ReferralDebug", "Firebase hasUsedReferral: $hasUsedReferral")

                    // The window closes at the unlock level, and submitReferral
                    // refuses a late code - so offering the popup past it would
                    // be inviting somebody to type something that can only be
                    // rejected. Normally unreachable (this is a first-run
                    // prompt), but a reinstall reaches it with a played account.
                    val unlockLevel =
                        viewModel.levelCurve.value?.referralUnlockLevel ?: 0
                    val level = document.getLong("level")?.toInt() ?: 1
                    val expired = unlockLevel > 0 && level >= unlockLevel

                    if(!hasUsedReferral && !expired){
                        showReferralPopup()
                    } else {
                        Log.d("ReferralDebug", "Referral entry unavailable (used=$hasUsedReferral expired=$expired)")
                    }
                } else {
                    Log.d("ReferralDebug", "User document does not exist in Firebase.")
                }
            } catch (e: Exception) {
                Log.e("ReferralDebug", "Error fetching Firebase data: ${e.message}")
            }
        }
    }



    private fun showReferralPopup() {
        val dialog = ReferralDialogFragment()
        dialog.show(supportFragmentManager, "ReferralDialog")
    }

    // Add this method to be called from other activities
    companion object {
        /**
         * Every reason the level-reward dialog can decline to appear logs
         * under one tag, because all of them are silent from the outside and
         * all of them look identical to the popup being broken.
         */
        private const val TAG_LEVEL_ANNOUNCE = "LevelAnnounce"

        /**
         * How recent a settlement has to be to be worth a fanfare.
         *
         * Three weeks: long enough that somebody who wins and does not open
         * the app for a fortnight is still congratulated, short enough that a
         * reinstall months later does not celebrate a week they have
         * forgotten. See maybeAnnounceLeaderboardPrize.
         */
        private const val PRIZE_FRESHNESS_MS = 21L * 24 * 60 * 60 * 1000

        /**
         * The same question for a settled payout, and the same answer.
         *
         * It only ever decides what a FIRST run does - once the mark is set,
         * age stops mattering and anything past it is announced however long
         * it took. So the cost of being generous is one stale "reward paid"
         * after a reinstall, and the cost of being mean is silently eating
         * news the user was waiting for. See maybeAnnounceRedemptionResult.
         */
        private const val RESULT_FRESHNESS_MS = 21L * 24 * 60 * 60 * 1000

        /**
         * The board size to quote when no board has been fetched this run.
         *
         * Matches LEADERBOARD_SIZE in functions/src/economy/leaderboard.ts.
         * Only ever reached when the dialog beats the first getLeaderboard
         * call, which is the common case - the prize rides the user snapshot
         * and arrives well before any callable answers.
         */
        private const val DEFAULT_BOARD_SIZE = 30

        fun handleInternetDisconnection(activity: AppCompatActivity) {
            if (activity !is MainActivity) {
                activity.runOnUiThread {
                    try {
                        // Finish the current activity and return to MainActivity
                        activity.finish()
                        activity.startActivity(
                            Intent(activity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        )
                    } catch (e: Exception) {
                        // Log but don't crash if there's an issue
                        Log.e("MainActivity", "Error handling internet disconnection: ${e.message}")
                    }
                }
            }
        }
    }
}
