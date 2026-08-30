package com.example.pixelpayout.ui.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map  // Add this import
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.UserPreferences
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * How long the leaderboard is considered fresh. Measured against
 * elapsedRealtime so a device clock change cannot make it refetch forever.
 */
private const val LEADERBOARD_REFRESH_MS = 3 * 60 * 1000L

/**
 * How long today's goals are considered fresh.
 *
 * Short on purpose - see refreshDailyGoals. This only exists to absorb rapid
 * tab switching, not to serve a stale tracker; anything that actually moves a
 * counter invalidates it outright.
 */
private const val GOALS_REFRESH_MS = 60 * 1000L

private const val MILLIS_PER_DAY = 86_400_000L

class MainViewModel(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {
    val points: LiveData<Int> = userRepository.userData.map { userData ->
        userData.points
    }

    val xp: LiveData<Int> = userRepository.userData.map { userData ->
        userData.xp
    }

    /**
     * Goal counters advance in the same transaction that awards XP, so a
     * change here means the stored goals are behind. Observed forever rather
     * than per-screen: the view model outlives every fragment, and the point
     * is to know about a quiz finished while Home was not on screen.
     */
    private val goalsInvalidator = androidx.lifecycle.Observer<Int> { invalidateDailyGoals() }

    init {
        xp.observeForever(goalsInvalidator)
    }

    override fun onCleared() {
        super.onCleared()
        xp.removeObserver(goalsInvalidator)
    }

    val level: LiveData<Int> = userRepository.userData.map { userData ->
        userData.level
    }

    val activeBuff: LiveData<UserRepository.TimedBuff?> =
        userRepository.userData.map { userData ->
            Log.d("BUFF_DEBUG", "activeBuff = ${userData.activeBuff}")
            Log.d("BUFF_DEBUG", "isActive = ${userData.activeBuff?.isActive()}")
            userData.activeBuff
        }

    /**
     * The XP buff, tracked apart from the Points one because the server keeps
     * them as separate grants with separate eligibility - a user can be
     * running either, both, or neither.
     */
    val activeXpBuff: LiveData<UserRepository.TimedBuff?> =
        userRepository.userData.map { userData ->
            Log.d("BUFF_DEBUG", "activeXpBuff = ${userData.activeXpBuff}")
            userData.activeXpBuff
        }

    /**
     * The cheapest redemption the user cannot afford yet - what the balance
     * bar on the home screen fills toward.
     *
     * The label is the OPTION'S OWN TITLE ("100 UC", "Rs 500"), never a
     * points-to-currency rate computed here. redemptionOptions carries a
     * pointsCost and a free-text title but no machine-readable currency
     * amount, so any "= 90 UC" figure would mean parsing that title - which
     * silently produces a wrong number the moment someone edits a title in
     * Firestore. Showing the real target is honest; inventing a rate is not.
     *
     * Level-gated options the user cannot reach yet are skipped: filling a
     * bar toward something they are not allowed to buy is a false promise.
     */
    data class NextRedemption(
        val title: String,
        val pointsCost: Int,
        val pointsShort: Int,
        val percent: Int
    )

    /**
     * The same catalogue the Wallet grid draws, from the same shared store -
     * so opening Wallet does not re-read what this already has, and a price
     * edited in Firestore moves the bar here as well as the card there.
     */
    private val redemptionGames: LiveData<List<RedemptionGame>> =
        userRepository.redemptionGames

    val nextRedemption: LiveData<NextRedemption?> = MediatorLiveData<NextRedemption?>().apply {
        fun recompute() {
            val user = userRepository.userData.value
            val games = redemptionGames.value.orEmpty()
            if (user == null || games.isEmpty()) {
                value = null
                return
            }

            // Flattened across games: the bar fills toward the cheapest thing
            // the user cannot buy yet ANYWHERE in the catalogue, which is the
            // next thing that will actually become available to them - not
            // the cheapest pack of some arbitrary game.
            val target = games
                .filter { it.minLevel <= user.level }
                .flatMap { game -> game.packs.map { game to it } }
                .filter { (_, pack) -> pack.pointsCost > user.points }
                .minByOrNull { (_, pack) -> pack.pointsCost }
                ?.let { (game, pack) -> Triple(game.name, pack.amount, pack.pointsCost) }

            // Nothing left to reach means everything on offer is already
            // affordable - the bar has no meaning, so hide it rather than
            // showing a permanently full one.
            if (target == null) {
                value = null
                return
            }

            val (_, amount, cost) = target
            value = NextRedemption(
                title = amount,
                pointsCost = cost,
                pointsShort = (cost - user.points).coerceAtLeast(0),
                percent = (user.points * 100 / cost).coerceIn(0, 100)
            )
        }

        addSource(userRepository.userData) { recompute() }
        addSource(redemptionGames) { recompute() }
    }

    init {
        // Seeded from disk rather than listened to. Home's balance bar needs
        // a target, not a live feed - and paying fifteen document reads on
        // every launch to keep a progress bar's label current is the wrong
        // trade for a screen that is not the catalogue. Wallet opens the live
        // listener when it appears.
        userRepository.seedRedemptionGames()
    }

    /** The code this user hands out, for the invite card on Profile. */
    val referralCode: LiveData<String> = userRepository.userData.map { it.referralCode }

    /** Whether the "have a code?" input on Profile still has a job to do. */
    val hasUsedReferral: LiveData<Boolean> = userRepository.userData.map { it.hasUsedReferral }

    private val _referralStats = MutableLiveData<UserRepository.ReferralStats?>(null)

    /**
     * Referral progress for Profile.
     *
     * Refreshed rather than observed - it aggregates across other people's
     * documents, so there is no single snapshot to listen to.
     */
    val referralStats: LiveData<UserRepository.ReferralStats?> = _referralStats

    fun refreshReferralStats() {
        viewModelScope.launch {
            userRepository.getReferralStats()?.let { _referralStats.value = it }
        }
    }

    /** Whether the once-per-account first-redeem discount is already spent. */
    val hasUsedFirstRedeem: LiveData<Boolean> =
        userRepository.userData.map { it.hasUsedFirstRedeem }

    val streak: LiveData<UserRepository.Streak> = userRepository.userData.map { it.streak }

    val pendingRedemptions: LiveData<UserRepository.PendingRedemptions> =
        userRepository.pendingRedemptions

    /**
     * The live feed, holding only what Home's single row draws. The rest is
     * fetched by [fullPayoutFeed] when somebody opens the sheet.
     */
    val payoutFeed: LiveData<List<UserRepository.PayoutFeedEntry>> =
        userRepository.payoutFeed

    /** The whole feed, read once, for the sheet. */
    suspend fun fullPayoutFeed(): List<UserRepository.PayoutFeedEntry> =
        userRepository.getPayoutFeed()

    val resolvedRedemptions: LiveData<List<UserRepository.ResolvedRedemption>> =
        userRepository.resolvedRedemptions

    /** Claims today's streak reward. The server owns every rule about it. */
    suspend fun claimDailyStreak(adWatched: Boolean): UserRepository.StreakClaimResult =
        userRepository.claimDailyStreak(adWatched)

    private val _dailyGoals = MutableLiveData<UserRepository.DailyGoals?>(null)

    /**
     * Today's goals.
     *
     * Refreshed rather than observed: progress lives in counters the server
     * owns, and there is no snapshot to listen to. It is re-read whenever the
     * screen appears and after anything that could have moved a counter.
     */
    val dailyGoals: LiveData<UserRepository.DailyGoals?> = _dailyGoals

    private var goalsFetchedAt = 0L
    private var goalsFetchedDayUtc = -1L

    /**
     * Re-reads today's goals, but not on every single return to Home.
     *
     * This was the one unthrottled per-resume callable left, and it was worse
     * than it looked: HomeFragment called it from onViewCreated AND onResume,
     * and onResume always follows onViewCreated, so a first visit cost two
     * invocations rather than one.
     *
     * The throttle is deliberately SHORT, and deliberately not the three
     * minutes the leaderboard uses. Standings tolerate being a few minutes
     * stale; a goal tracker does not. The user returns to Home from a quiz
     * specifically to watch the bar move, and "played a quiz, came back,
     * nothing changed" reads as a broken feature rather than as a cache.
     *
     * Two things bypass it, so that case cannot happen:
     *
     *   * [invalidateDailyGoals], called whenever XP moves - which is exactly
     *     when a quiz or a game has paid out and a counter has advanced.
     *   * The UTC day rolling over, because goals reset there and the stored
     *     copy stops describing today at all.
     */
    fun refreshDailyGoals(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val today = System.currentTimeMillis() / MILLIS_PER_DAY

        val fresh = !force &&
            _dailyGoals.value != null &&
            today == goalsFetchedDayUtc &&
            now - goalsFetchedAt < GOALS_REFRESH_MS
        if (fresh) return

        goalsFetchedAt = now
        goalsFetchedDayUtc = today

        viewModelScope.launch {
            userRepository.getDailyGoals()?.let { _dailyGoals.value = it }
        }
    }

    /**
     * Marks the stored goals as stale without fetching anything.
     *
     * Called when XP moves, which is the signal that a quiz or game just paid
     * out - the same transaction that awards it advances the goal counters.
     * The next time Home appears it will actually re-read, so the throttle
     * never hides progress the user just earned.
     */
    private fun invalidateDailyGoals() {
        goalsFetchedAt = 0L
    }

    suspend fun claimDailyGoalBonus(adWatched: Boolean): UserRepository.GoalBonusResult {
        val result = userRepository.claimDailyGoalBonus(adWatched)
        refreshDailyGoals(force = true)
        return result
    }

    private val _leaderboard = MutableLiveData<UserRepository.Leaderboard?>(null)

    /**
     * The weekly board.
     *
     * Refreshed rather than observed - the standings live across every user's
     * documents, so there is no single snapshot to listen to. Re-read whenever
     * the screen appears, which is also when play could have moved it.
     */
    val leaderboard: LiveData<UserRepository.Leaderboard?> = _leaderboard

    private var leaderboardFetchedAt = 0L

    /**
     * Re-reads the board, but not more than once every few minutes.
     *
     * This runs on every return to Home, and standings do not move fast
     * enough to justify a round trip each time. The throttle is what keeps a
     * screen the user flicks in and out of from being the most expensive
     * thing in the app.
     */
    fun refreshLeaderboard(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - leaderboardFetchedAt < LEADERBOARD_REFRESH_MS) return
        leaderboardFetchedAt = now

        viewModelScope.launch {
            userRepository.getLeaderboard()?.let { _leaderboard.value = it }
        }
    }

    /** The full hundred, fetched only when the sheet is actually opened. */
    suspend fun getFullLeaderboard(): UserRepository.Leaderboard? =
        userRepository.getLeaderboard(full = true)

    private val _streakCycle = MutableLiveData<List<UserRepository.StreakDayReward>>(emptyList())

    /**
     * The streak reward table.
     *
     * Held here rather than fetched by the screen, for two reasons. It comes
     * from a callable, which unlike Firestore has no offline cache, so every
     * return to Home was a fresh network round trip that left the streak
     * cells blank until it landed. And this view model is activity scoped, so
     * one fetch now covers every visit to the tab.
     *
     * The disk copy makes a cold start immediate too. The table only changes
     * when the server is redeployed, so serving a stale copy for the second
     * it takes to refresh costs nothing.
     */
    val streakCycle: LiveData<List<UserRepository.StreakDayReward>> = _streakCycle

    private var streakCycleLoaded = false

    fun loadStreakCycle() {
        if (streakCycleLoaded) return
        streakCycleLoaded = true

        viewModelScope.launch {
            // Draw from disk first, so the card is never empty while the
            // network is asked.
            decodeCycle(userPreferences.streakCycle.firstOrNull())
                .takeIf { it.isNotEmpty() }
                ?.let { _streakCycle.value = it }

            val fresh = userRepository.getStreakConfig()
            if (fresh.isEmpty()) return@launch

            _streakCycle.value = fresh
            userPreferences.setStreakCycle(encodeCycle(fresh))
        }
    }

    /** "points:xp" pairs - small, human readable in a prefs dump, no parser. */
    private fun encodeCycle(cycle: List<UserRepository.StreakDayReward>): String =
        cycle.joinToString(",") { "${it.points}:${it.xp}" }

    private fun decodeCycle(raw: String?): List<UserRepository.StreakDayReward> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val points = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val xp = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            UserRepository.StreakDayReward(points, xp)
        }
    }

    /** Points and level together, for screens that gate on both. */
    data class UserState(val points: Int, val level: Int)

    val userState: LiveData<UserState> = userRepository.userData.map { userData ->
        UserState(userData.points, userData.level)
    }

    /**
     * Progress through the CURRENT level, rather than lifetime XP. Lifetime XP
     * is still what's stored and what drives leaderboards - this is just the
     * view of it that resets each level, which is what reads as progress.
     */
    data class LevelProgress(
        val level: Int,
        val xpIntoLevel: Int,
        val xpForNextLevel: Int,
        val isMaxLevel: Boolean,
        val totalXp: Int
    )

    val levelProgress: LiveData<LevelProgress> = MediatorLiveData<LevelProgress>().apply {
        fun recompute() {
            val user = userRepository.userData.value ?: return
            val curve = userRepository.levelCurve.value

            if (curve == null) {
                // Curve not loaded yet - show the level we know, no bar.
                value = LevelProgress(
                    level = user.level,
                    xpIntoLevel = 0,
                    xpForNextLevel = 0,
                    isMaxLevel = false,
                    totalXp = user.xp
                )
                return
            }

            val floor = curve.xpRequiredFor(user.level)
            val isMax = user.level >= curve.maxLevel

            value = LevelProgress(
                level = user.level,
                xpIntoLevel = (user.xp - floor).coerceAtLeast(0),
                xpForNextLevel = if (isMax) 0 else curve.xpRequiredFor(user.level + 1) - floor,
                isMaxLevel = isMax,
                totalXp = user.xp
            )
        }

        addSource(userRepository.userData) { recompute() }
        addSource(userRepository.levelCurve) { recompute() }
    }
}
