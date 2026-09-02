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
        if (progress == null) return

        val pending = progress.pendingLevelRewards
        if (pending.isEmpty()) return

        val rewards = viewModel.levelCurve.value?.levelRewards.orEmpty()
        // The curve observer below re-asks when it lands.
        if (rewards.isEmpty()) return

        // KEYED ON THE QUEUE, NOT ON THE LEVEL. The two usually agree, but not
        // on an account that crossed levels before any of this existed: those
        // levels were paid outright and are owed nothing, so a high-water mark
        // taken from the level number would sit above the first reward that
        // was ever actually queued and silently swallow its announcement.
        val top = pending.max()

        lifecycleScope.launch {
            val announced = userPreferences.lastAnnouncedLevel.firstOrNull() ?: 0
            if (top <= announced) return@launch
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
                userPreferences.setLastAnnouncedLevel(top)
            } else {
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

            // First run on this device: adopt the current history silently.
            // Everything already settled predates the app knowing about it, and
            // greeting a fresh install with news of a months-old payout would be
            // worse than saying nothing.
            if (lastSeen == 0L) {
                userPreferences.setLastSeenRedemptionResolvedAt(
                    resolved.maxOf { it.resolvedAtMillis }
                )
                return@launch
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
    }

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

                    if(!hasUsedReferral){
                        showReferralPopup()
                    } else {
                        Log.d("ReferralDebug", "User has already used a referral code.")
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
