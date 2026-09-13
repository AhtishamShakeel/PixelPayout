package com.example.pixelpayout.ui.main

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.map  // Add this import
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.model.OfferwallEntry
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.repository.DailyGoalEngine
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.utils.ServerClock
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

/** Mirrors the server's DAILY_GOAL_BONUS_POINTS fallback. */
private const val DEFAULT_GOAL_BONUS_POINTS = 30

/** Mirrors the server's MAX_DAILY_QUIZ_ATTEMPTS. */
const val MAX_DAILY_QUIZ_ATTEMPTS = 10

/** Mirrors the server's MAX_DAILY_GAME_SESSIONS. */
const val MAX_DAILY_GAME_SESSIONS = 10

/**
 * Mirrors the server's MAX_DAILY_BONUS_ATTEMPTS - extra attempts buyable with
 * a rewarded ad, per activity, per day.
 *
 * Display only, like the two above it. The server clamps against its own copy
 * inside the grant transaction, so a client out of step with a retune shows a
 * wrong button rather than buying a wrong allowance.
 */
const val MAX_DAILY_BONUS_ATTEMPTS = 3

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

    override fun onCleared() {
        super.onCleared()
    }

    val level: LiveData<Int> = userRepository.userData.map { userData ->
        userData.level
    }

    /**
     * Whether there are any offerwalls to show.
     *
     * NO LONGER DECIDES WHETHER THE EARN TAB EXISTS. Earn is permanent now
     * that the weekly leaderboard opens it - see MainActivity.
     * setupOfferwallTab. What this still decides is everything that points
     * specifically at OFFERS: the shortcut on Home, the "Earn more" button in
     * the wallet, and the list on Earn itself. A button promising offers that
     * leads to a screen with none is the failure this is built to avoid.
     *
     * Both halves matter and both can change while the app is open: a wall
     * is switched on in the console, or the user levels past a wall's gate.
     * Combining them here rather than in each screen is what keeps those
     * three from ever disagreeing about whether offerwalls exist.
     *
     * EMITS NOTHING UNTIL THE CATALOGUE HAS ACTUALLY ANSWERED. That is what
     * the null check below is: an unanswered catalogue is not the same as an
     * empty one, and treating it as empty would publish a false that every
     * observer would act on - hiding the offer shortcuts on every launch and
     * restoring them a beat later, for every user who has walls. Staying
     * silent leaves them on the shape they were drawn with, which is right
     * far more often than "no offers" is.
     *
     * The level is allowed to be missing, though, and defaults to 1: an
     * unknown level should see the ungated walls, not silently unlock the
     * gated ones.
     */
    val offerwallAvailable: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        fun recompute() {
            val walls = userRepository.offerwallWalls.value ?: return
            val available =
                OfferwallEntry.visibleTo(walls, level.value ?: 1).isNotEmpty()
            // Only on a real change: this has two sources and the level one
            // re-emits on every user snapshot, which is often.
            if (value != available) value = available
        }

        addSource(userRepository.offerwallWalls) { recompute() }
        addSource(level) { recompute() }
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
     * While the first-redeem offer is still live for this account it is the
     * offer that gets measured, at its discounted price. See the recompute
     * below for why.
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
        val percent: Int,
        /**
         * The balance the bar is filled to. Carried rather than derived from
         * pointsCost - pointsShort: that subtraction is only correct while
         * pointsShort has not hit its floor of zero, and the card prints this
         * figure under the bar where being off by the overshoot would show.
         */
        val pointsHeld: Int
    )

    /**
     * The same catalogue the Wallet grid draws, from the same shared store -
     * so opening Wallet does not re-read what this already has, and a price
     * edited in Firestore moves the bar here as well as the card there.
     */
    val redemptionGames: LiveData<List<RedemptionGame>> =
        userRepository.redemptionGames

    /**
     * The published curve: thresholds, the milestone table and the referral
     * threshold. Level rewards lists all three; Home only needs the
     * thresholds, which is why [levelProgress] below reads it too.
     */
    val levelCurve: LiveData<UserRepository.LevelCurve?> = userRepository.levelCurve

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
            //
            // Amount and price only. The game itself used to ride along in
            // this tuple and was discarded at the other end, and it is not
            // something that goes in front of a user anyway - see
            // RedemptionGame.currencyName.
            val reachable = games.filter { it.minLevel <= user.level }

            // THE OFFER IS THE TARGET WHILE IT IS STILL THERE.
            //
            // The first redeem is the cheapest price in the catalogue by
            // design, it is reachable only through the offer, and it is what
            // this account will genuinely pay next. Measuring the ordinary
            // floor instead asks for a 600-star climb toward a purchase that
            // would have cost 150 - a number that is wrong for this user and
            // discouraging for no reason.
            //
            // "Still there" is the offer card's own test, and for the same
            // two reasons: unfinished for this account, and some pack
            // actually carrying a discounted price. Both halves of finished
            // count - spent, or retired because the UID had already had one -
            // which is what firstRedeemFinished merges; this reads the same
            // two fields directly because it is already holding the user.
            val offerLive = !(user.hasUsedFirstRedeem || user.firstRedeemUnavailable)
            val offers = if (!offerLive) emptyList() else reachable
                .flatMap { game ->
                    game.packs.mapNotNull { pack ->
                        pack.firstRedeemCost?.let { pack.amount to it }
                    }
                }

            // Falls through to the ordinary catalogue on any of three counts:
            // the offer is finished, nothing carries a discounted price, or
            // every discounted pack is ALREADY affordable. That last one is
            // the interesting case - the offer has stopped being a climb, so
            // the bar goes back to measuring what still is one.
            val target = offers
                .filter { (_, cost) -> cost > user.points }
                .minByOrNull { (_, cost) -> cost }
                ?: reachable
                    .flatMap { game ->
                        game.purchasablePacks.map { it.amount to it.pointsCost }
                    }
                    .filter { (_, cost) -> cost > user.points }
                    .minByOrNull { (_, cost) -> cost }

            // Nothing left to reach means everything on offer is already
            // affordable - the bar has no meaning, so hide it rather than
            // showing a permanently full one.
            if (target == null) {
                value = null
                return
            }

            val (amount, cost) = target
            value = NextRedemption(
                title = amount,
                pointsCost = cost,
                pointsShort = (cost - user.points).coerceAtLeast(0),
                // Long arithmetic: a balance and a price are both Ints and
                // their product overflows a little past two million points,
                // which this economy reaches. The Wallet bar already does
                // this; Home was still overflowing.
                percent = (user.points.toLong() * 100 / cost).toInt().coerceIn(0, 100),
                pointsHeld = user.points
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

    /**
     * Whether the first-redeem offer is finished for this account, for any
     * reason.
     *
     * The two reasons are different facts on the server and are kept apart
     * there - one is "you spent it", the other is "the game account you tried
     * had already had one". Neither is recoverable by the user, and the card
     * has exactly one thing to do about either, so they are merged here
     * rather than at the three call sites that would otherwise each have to
     * remember to ask both.
     */
    val firstRedeemFinished: LiveData<Boolean> =
        userRepository.userData.map { it.hasUsedFirstRedeem || it.firstRedeemUnavailable }

    val streak: LiveData<UserRepository.Streak> = userRepository.userData.map { it.streak }

    val pendingRedemptions: LiveData<UserRepository.PendingRedemptions> =
        userRepository.pendingRedemptions

    /**
     * The live feed, holding only what Home's payout row draws - its line of
     * text and its three initials. The rest is fetched by [fullPayoutFeed]
     * when somebody opens the sheet.
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

    /**
     * Releases the lowest locked level bonus. No level parameter by design -
     * see UserRepository.claimLevelReward.
     */
    suspend fun claimLevelReward(): UserRepository.LevelRewardResult =
        userRepository.claimLevelReward()

    /**
     * Today's goals, derived rather than fetched.
     *
     * This used to be a callable on every return to Home - the single most
     * frequent read in the app, and an avoidable one. Everything it answered
     * is already on the client:
     *
     *   * WHICH three goals today holds is a pure function of (uid, UTC day),
     *     computed by [DailyGoalEngine] from the pool the server publishes on
     *     config/levelCurve.
     *   * HOW FAR ALONG they are comes from `dailyStats` on the user
     *     document, which the snapshot listener already delivers - and which
     *     the same transaction that awards a game or a quiz writes, so this
     *     is as live as the balance is.
     *   * WHETHER the bonus is claimed is `lastGoalBonusDayUtc`, same
     *     document.
     *
     * Only the bonus FIGURE needs the network, and that is one read per
     * sign-in rather than one per resume.
     *
     * The client still decides nothing. claimDailyGoalBonus re-derives every
     * one of these values server-side inside the transaction that pays, so a
     * device that lies about its counters is refused - this is a readout, and
     * the day it stops being one is the day goals start printing stars.
     */
    val dailyGoals: LiveData<UserRepository.DailyGoals?> =
        MediatorLiveData<UserRepository.DailyGoals?>().apply {
            fun recompute() {
                val user = userRepository.userData.value ?: return
                val pool = userRepository.goalPool.value ?: return
                val uid = userRepository.getCurrentUserId().orEmpty()

                // No pool yet means config/levelCurve has not landed. Null
                // renders as "no card" rather than as a guessed set of goals.
                if (pool.isEmpty || uid.isEmpty()) {
                    value = null
                    return
                }

                val todayUtc = ServerClock.now() / MILLIS_PER_DAY
                val stats = userRepository.statsForToday(user.dailyStats, todayUtc)
                val templates = DailyGoalEngine.selectGoals(pool, uid, todayUtc)

                value = UserRepository.DailyGoals(
                    goals = templates.map { template ->
                        UserRepository.DailyGoal(
                            id = template.id,
                            kind = template.kind,
                            target = template.target,
                            progress = DailyGoalEngine.progressFor(template, stats),
                            done = DailyGoalEngine.isDone(template, stats)
                        )
                    },
                    bonusPoints = userRepository.goalBonusPoints.value
                        ?: DEFAULT_GOAL_BONUS_POINTS,
                    bonusClaimed = user.lastGoalBonusDayUtc == todayUtc,
                    dayUtc = todayUtc
                )
            }

            addSource(userRepository.userData) { recompute() }
            addSource(userRepository.goalPool) { recompute() }
            addSource(userRepository.goalBonusPoints) { recompute() }
        }

    /**
     * Kept as a no-op entry point.
     *
     * Home calls this from onResume, and the goals are now recomputed from
     * the user snapshot the moment anything moves - so there is nothing left
     * to refresh. Retained rather than deleted so the screen keeps reading
     * the same way if goals ever need a server round trip again.
     */
    fun refreshDailyGoals(force: Boolean = false) = Unit


    /**
     * Claims the bonus. Nothing is re-fetched afterwards: the transaction that
     * pays stamps `lastGoalBonusDayUtc` on the user document, and the snapshot
     * listener turns that into a redraw on its own.
     */
    suspend fun claimDailyGoalBonus(adWatched: Boolean): UserRepository.GoalBonusResult =
        userRepository.claimDailyGoalBonus(adWatched)

    /**
     * The last weekly prize this account won, straight off the user snapshot.
     *
     * Unlike [leaderboard] this needs no fetch and no throttle: the
     * settlement writes it onto the user document, which is already being
     * listened to, so it is in memory before any callable answers. MainActivity
     * turns it into the congratulation.
     */
    val leaderboardPrize: LiveData<UserRepository.LeaderboardPrize?> =
        userRepository.userData.map { it.lastLeaderboardPrize }

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

    /**
     * The whole board, fetched when the leaderboard screen opens.
     *
     * Published into [leaderboard] as well, with the throttle restarted: this
     * is the freshest answer the app has, and keeping it to the screen that
     * asked meant Earn went back to showing its older copy for up to three
     * minutes after the player had just seen the real one.
     */
    suspend fun getFullLeaderboard(): UserRepository.Leaderboard? =
        userRepository.getLeaderboard(full = true)?.also {
            _leaderboard.value = it
            leaderboardFetchedAt = SystemClock.elapsedRealtime()
        }

    /** The caller's own tournament standing, as the user snapshot has it. */
    data class TournamentSelf(val weeklyXp: Int, val entered: Boolean)

    /**
     * Live weekly XP and entry, straight off the user document listener.
     *
     * claimReward and enterTournament both write to that document, so this
     * moves the moment a quiz, a game or an entry lands - with no call of its
     * own. The week is reckoned on the server's clock, the same boundary the
     * server resets on.
     */
    val tournamentSelf: LiveData<TournamentSelf> = userRepository.userData.map { user ->
        val week = currentWeekKey()
        TournamentSelf(user.weeklyXpFor(week), user.hasEnteredWeek(week))
    }.distinctUntilChanged()

    /**
     * The board as the tournament cards should draw it: the last server
     * answer, with the caller's own XP, entry, rank and prize kept live from
     * [tournamentSelf]. See Leaderboard.withLiveStanding.
     */
    val tournament: LiveData<UserRepository.Leaderboard?> =
        MediatorLiveData<UserRepository.Leaderboard?>().apply {
            fun update() {
                val board = _leaderboard.value
                val self = tournamentSelf.value
                value = if (board != null && self != null) {
                    board.withLiveStanding(self.weeklyXp, self.entered)
                } else {
                    board
                }
            }
            addSource(_leaderboard) { update() }
            addSource(tournamentSelf) { update() }
        }

    /** Whole weeks since the epoch, starting Monday - the server's utcWeekFor. */
    fun currentWeekKey(): Int =
        ((Math.floorDiv(ServerClock.now(), MILLIS_PER_DAY) + 3) / 7).toInt()

    /**
     * Buys into this week's tournament, then re-reads the shared board so the
     * Earn card stops offering an entry the player already holds. The refresh
     * is forced past the throttle: the entry is exactly the change it exists
     * to wait out, and waiting three minutes for it would look like a failure.
     */
    suspend fun enterTournament(expectedFee: Int): UserRepository.TournamentEntryResult {
        val result = userRepository.enterTournament(expectedFee)
        if (result is UserRepository.TournamentEntryResult.Entered ||
            result is UserRepository.TournamentEntryResult.AlreadyEntered ||
            result is UserRepository.TournamentEntryResult.EntriesClosed ||
            result is UserRepository.TournamentEntryResult.FeeChanged
        ) {
            refreshLeaderboard(force = true)
        }
        return result
    }

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

    /**
     * One activity's standing for today: what has been used, what the ceiling
     * currently is, and whether another ad would raise it.
     *
     * Both counter cards need all three and they have to agree, which is why
     * this is one value rather than three LiveDatas the screens recombine.
     */
    data class Allowance(
        val used: Int,
        /** Base cap plus the bonuses already bought today. */
        val allowance: Int,
        /** Bonuses bought today, against [MAX_DAILY_BONUS_ATTEMPTS]. */
        val bonusBought: Int
    ) {
        val remaining: Int get() = (allowance - used).coerceAtLeast(0)

        /**
         * Whether the "+" is worth offering. Deliberately NOT conditional on
         * the allowance being spent: an attempt can be bought at any point in
         * the day, so a user can bank one before they need it.
         */
        val canBuyMore: Boolean get() = bonusBought < MAX_DAILY_BONUS_ATTEMPTS
    }

    private fun allowanceOf(
        base: Int,
        used: Int,
        bonusBought: Int
    ): Allowance {
        // Clamped the same way the server clamps, so a field edited in the
        // console cannot draw a card with thirty pips on it.
        val bonus = bonusBought.coerceIn(0, MAX_DAILY_BONUS_ATTEMPTS)
        return Allowance(
            used = used.coerceIn(0, base + bonus),
            allowance = base + bonus,
            bonusBought = bonus
        )
    }

    /**
     * Both allowances come straight off the shared user snapshot.
     *
     * This is what replaced checkAndResetQuizAttempts on every screen that
     * only DISPLAYS a count: all five figures involved - the two counters,
     * their two bonus counters and `last_reset_time` - are already on the
     * user document the listener holds, so asking a callable was paying a
     * Firestore read to be told something in memory.
     *
     * The rollover is applied in UserData rather than trusted from the stored
     * counters: a user who has not played since yesterday carries yesterday's
     * numbers until their next claim resets them.
     */
    val quizAllowance: LiveData<Allowance> = userRepository.userData.map {
        val today = ServerClock.now() / MILLIS_PER_DAY
        allowanceOf(
            MAX_DAILY_QUIZ_ATTEMPTS,
            it.quizAttemptsToday(today),
            it.bonusQuizAttemptsToday(today)
        )
    }

    val gameAllowance: LiveData<Allowance> = userRepository.userData.map {
        val today = ServerClock.now() / MILLIS_PER_DAY
        allowanceOf(
            MAX_DAILY_GAME_SESSIONS,
            it.gameAttemptsToday(today),
            it.bonusGameAttemptsToday(today)
        )
    }

    /** The same figures for callers on a timer, which cannot await LiveData. */
    fun quizAllowanceNow(): Allowance {
        val user = userRepository.userData.value ?: return allowanceOf(MAX_DAILY_QUIZ_ATTEMPTS, 0, 0)
        val today = ServerClock.now() / MILLIS_PER_DAY
        return allowanceOf(
            MAX_DAILY_QUIZ_ATTEMPTS,
            user.quizAttemptsToday(today),
            user.bonusQuizAttemptsToday(today)
        )
    }

    fun gameAllowanceNow(): Allowance {
        val user = userRepository.userData.value ?: return allowanceOf(MAX_DAILY_GAME_SESSIONS, 0, 0)
        val today = ServerClock.now() / MILLIS_PER_DAY
        return allowanceOf(
            MAX_DAILY_GAME_SESSIONS,
            user.gameAttemptsToday(today),
            user.bonusGameAttemptsToday(today)
        )
    }

    /**
     * Buys one extra attempt after a rewarded ad has played.
     *
     * Suspending rather than posting to shared LiveData: this view model is
     * activity-scoped and two screens use it, so a result event would have to
     * carry which screen asked. The caller awaits its own answer in its own
     * view lifecycle scope, and a screen that goes away simply stops caring -
     * the grant still lands, and the snapshot listener picks it up.
     */
    suspend fun buyBonusAttempt(
        activity: UserRepository.BonusActivity
    ): UserRepository.BonusAttemptResult = userRepository.grantBonusAttempt(activity)

    /**
     * The next UTC midnight, as the server reckons it. Quizzes and games share
     * this boundary - one day stamp on the user document resets both counters
     * - which is why this is no longer named for quizzes alone.
     *
     * Derived from the clock rather than from the stored `last_reset_time`,
     * which is what the old countdown did - and why it could stick at zero.
     * A stale stamp put the "next reset" in the past and left the countdown
     * permanently expired; a boundary computed from the current day is always
     * in the future.
     */
    fun nextAttemptsResetMillis(): Long =
        (ServerClock.now() / MILLIS_PER_DAY + 1) * MILLIS_PER_DAY

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
        val totalXp: Int,
        /**
         * The one-time star bonus the NEXT level pays, or 0 when the curve
         * publishes none for it (or has not loaded).
         *
         * Carried here so the home card can say what the next level is worth
         * rather than only how far away it is. Zero is rendered as the plain
         * "N XP to Level M" - never as "claim 0 stars", which would be a
         * promise the server does not keep.
         */
        val nextLevelReward: Int = 0,
        /**
         * Levels already reached whose star bonus is still locked behind an
         * ad, lowest first. Empty is the normal state.
         *
         * Carried on LevelProgress rather than read separately because every
         * screen that draws the level already observes this - the Home card
         * turns its "Level rewards" link into a claim prompt from it, and the
         * ladder tags its rungs from it.
         */
        val pendingLevelRewards: List<Int> = emptyList()
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
                    totalXp = user.xp,
                    pendingLevelRewards = user.pendingLevelRewards
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
                totalXp = user.xp,
                nextLevelReward =
                    if (isMax) 0 else curve.levelRewards[user.level + 1] ?: 0,
                pendingLevelRewards = user.pendingLevelRewards
            )
        }

        addSource(userRepository.userData) { recompute() }
        addSource(userRepository.levelCurve) { recompute() }
    }
}
