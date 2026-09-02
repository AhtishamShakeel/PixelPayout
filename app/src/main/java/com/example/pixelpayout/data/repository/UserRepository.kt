package com.example.pixelpayout.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.model.RedemptionPack
import com.example.pixelpayout.ui.redemption.RedemptionResult
import com.example.pixelpayout.ui.redemption.ReferralResult
import com.example.pixelpayout.utils.ServerClock

private const val MILLIS_PER_DAY = 86_400_000L

class UserRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val _userData = MutableLiveData<UserData>()
    val userData: LiveData<UserData> = _userData

    /**
     * The published curve, from the process-level store rather than a field
     * here: this repository is constructed per view model, and re-reading one
     * deploy-time document for each of them was costing a handful of reads a
     * session. See [LevelCurveStore].
     */
    val levelCurve: LiveData<LevelCurve?> = LevelCurveStore.curve

    /**
     * The daily goal pool, published alongside the level curve.
     *
     * Held rather than re-fetched: the pool only changes on a deploy, and the
     * goals card is now drawn from it plus the user snapshot, with no callable
     * in between.
     */
    val goalPool: LiveData<DailyGoalEngine.GoalPool> = LevelCurveStore.goalPool

    private val _goalBonusPoints = MutableLiveData(DEFAULT_GOAL_BONUS_POINTS)

    /**
     * What finishing all three pays.
     *
     * Read from config/dailyGoals directly rather than copied onto the curve
     * document, and that is deliberate: the console edits this value, and a
     * copy refreshed only on a function cold start could show one figure while
     * claimDailyGoalBonus paid another. One read per sign-in keeps the number
     * on screen and the number paid the same for the whole session.
     */
    val goalBonusPoints: LiveData<Int> = _goalBonusPoints

    init {
        waitForUserLogin()
    }

    fun getCurrentUserId(): String? {
        return auth.currentUser?.uid
    }
    private fun waitForUserLogin() {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            auth.currentUser?.uid?.let { userId ->
                setupRealtimeUpdates(userId)  // ✅ Ensure setup runs AFTER login
                LevelCurveStore.load()
                fetchGoalBonus()
                listenToRedemptions(userId)
                listenToPayoutFeed()
                // Seed only. The live listener costs a read per document
                // every time a fresh process attaches it, and most sessions
                // never open Wallet - so it waits until something actually
                // shows the catalogue.
                RedemptionOptionsStore.seedFromCache()
            }
        }
    }

    /**
     * One read per sign-in for the goal bonus. Clamped the same way the
     * server's resolveBonusPoints clamps it, so a console typo shows the same
     * capped figure the server would actually pay.
     */
    private fun fetchGoalBonus() {
        firestore.collection(COLLECTION_CONFIG).document(DOC_DAILY_GOALS).get()
            .addOnSuccessListener { snapshot ->
                val raw = snapshot.get("bonusPoints")
                val points = (raw as? Number)?.toInt()
                _goalBonusPoints.postValue(
                    if (points == null || points < 0) DEFAULT_GOAL_BONUS_POINTS
                    else minOf(points, MAX_GOAL_BONUS_POINTS)
                )
            }
    }

    data class LevelCurve(
        val maxLevel: Int,
        /** Cumulative XP required to reach level (index + 2). */
        val thresholds: List<Int>,
        /**
         * Level -> the one-time star bonus reaching it pays.
         *
         * Every level from 2 up pays something; level 1 is where accounts are
         * created, so it is never crossed and never appears. Empty when the
         * curve document predates the field, which draws a ladder with no
         * star rows rather than inventing figures.
         */
        val levelRewards: Map<Int, Int> = emptyMap(),
        /**
         * The XP an invitee must reach before their referrer is paid. Zero
         * when the curve document predates this field being published.
         */
        val referralUnlockXp: Int = 0
    ) {
        /** Total XP needed to reach [level]; 0 for level 1. */
        fun xpRequiredFor(level: Int): Int = when {
            level <= 1 -> 0
            else -> thresholds.getOrElse(minOf(level, maxLevel) - 2) { thresholds.last() }
        }

        /**
         * The level somebody holding [xp] is at. Mirrors the server's
         * levelForXp, from the same published thresholds.
         *
         * Used to place the referral threshold on the ladder: the server
         * states that rule in XP, not levels, so the row is positioned from
         * the curve rather than from a level number written down twice. The
         * row's own wording still quotes the XP figure, because the level it
         * sits on is reached slightly before the threshold is.
         */
        fun levelForXp(xp: Int): Int {
            if (xp <= 0) return 1
            var level = 1
            for (threshold in thresholds) {
                if (xp >= threshold) level++ else break
            }
            return minOf(level, maxLevel)
        }
    }

    private fun setupRealtimeUpdates(userId: String) {
        auth.currentUser?.uid?.let { userId ->
            firestore.collection(COLLECTION_USERS).document(userId)
                .addSnapshotListener { snapshot, error ->

                    Log.d("BUFF_DEBUG", "Snapshot listener fired")
                    Log.d("BUFF_DEBUG", "Error = $error")
                    Log.d("BUFF_DEBUG", "Snapshot exists = ${snapshot?.exists()}")
                    Log.d(
                        "BUFF_DEBUG",
                        "Raw activeBuff = ${snapshot?.get(FIELD_ACTIVE_BUFF)}"
                    )

                    snapshot?.let {
                        _userData.postValue(
                            UserData(
                                points = it.getLong(FIELD_POINTS)?.toInt() ?: 0,
                                xp = it.getLong(FIELD_XP)?.toInt() ?: 0,
                                level = it.getLong(FIELD_LEVEL)?.toInt() ?: 1,
                                pendingLevelRewards =
                                    parsePendingLevelRewards(
                                        it.get(FIELD_PENDING_LEVEL_REWARDS)
                                    ),
                                activeBuff = parseBuff(it.get(FIELD_ACTIVE_BUFF)),
                                activeXpBuff = parseBuff(it.get(FIELD_ACTIVE_XP_BUFF)),
                                streak = Streak(
                                    count = it.getLong(FIELD_STREAK_COUNT)?.toInt() ?: 0,
                                    lastClaimedDayUtc = it.getLong(FIELD_LAST_STREAK_DAY),
                                    lastRewardedDayUtc =
                                        it.getLong(FIELD_LAST_STREAK_REWARD_DAY)
                                ),
                                referralCode = it.getString(FIELD_REFERRAL_CODE).orEmpty(),
                                hasUsedReferral =
                                    it.getBoolean(FIELD_HAS_USED_REFERRAL) ?: false,
                                hasUsedFirstRedeem =
                                    it.getBoolean(FIELD_HAS_USED_FIRST_REDEEM) ?: false,
                                // Both were already arriving in this snapshot
                                // and being thrown away, which is what made
                                // getDailyGoals a read per return to Home.
                                dailyStats = parseDailyStats(it.get(FIELD_DAILY_STATS)),
                                lastGoalBonusDayUtc =
                                    it.getLong(FIELD_LAST_GOAL_BONUS_DAY),
                                quizAttempts =
                                    it.getLong(FIELD_QUIZ_ATTEMPTS)?.toInt() ?: 0,
                                gameAttempts =
                                    it.getLong(FIELD_GAME_ATTEMPTS)?.toInt() ?: 0,
                                bonusQuizAttempts =
                                    it.getLong(FIELD_BONUS_QUIZ_ATTEMPTS)?.toInt() ?: 0,
                                bonusGameAttempts =
                                    it.getLong(FIELD_BONUS_GAME_ATTEMPTS)?.toInt() ?: 0,
                                attemptsStampedAtMillis =
                                    it.getTimestamp(FIELD_LAST_RESET_TIME)
                                        ?.toDate()?.time
                            )
                        )
                    }
                }
        }
    }

    data class UserData(
        val points: Int,
        val xp: Int = 0,
        val level: Int = 1,
        /**
         * Levels whose one-time star bonus is earned but still locked behind
         * a rewarded ad, lowest first. Mirrors the server's
         * `pendingLevelRewards`; see claimLevelReward for why the order
         * matters and why this rides the user snapshot rather than a query.
         *
         * Empty is the normal state - it fills the moment a level is crossed
         * and empties one ad at a time.
         */
        val pendingLevelRewards: List<Int> = emptyList(),
        val activeBuff: TimedBuff? = null,
        val activeXpBuff: TimedBuff? = null,
        val streak: Streak = Streak(),
        /** The code this user hands out. Written once at signup. */
        val referralCode: String = "",
        /**
         * Whether this user has already redeemed someone else's code. It is
         * a one-time thing, so the input on Profile is retired once it is
         * true rather than left there to be rejected.
         */
        val hasUsedReferral: Boolean = false,
        /**
         * Whether the once-per-account discounted first redeem is spent.
         * Set by redeemReward inside the same transaction as the debit, so a
         * retry cannot spend it twice.
         */
        val hasUsedFirstRedeem: Boolean = false,
        /** Today's activity counters, as the server increments them. */
        val dailyStats: DailyStats = DailyStats(),
        /** The UTC day the goal bonus was last paid, or null. */
        val lastGoalBonusDayUtc: Long? = null,
        /**
         * Quiz attempts used, as stored. Read [quizAttemptsToday] instead -
         * this figure belongs to whatever day [quizAttemptsStampedAtMillis]
         * names, which is not necessarily today.
         */
        val quizAttempts: Int = 0,
        /**
         * Game runs claimed, as stored. Read [gameAttemptsToday] instead, for
         * the same reason as [quizAttempts].
         */
        val gameAttempts: Int = 0,
        /**
         * Extra quiz attempts bought with a rewarded ad today, as stored.
         * Read [bonusQuizAttemptsToday]; it rides the same day stamp as the
         * counter it raises, so it goes stale in exactly the same way.
         */
        val bonusQuizAttempts: Int = 0,
        /** Extra game runs bought today, on the same terms. */
        val bonusGameAttempts: Int = 0,
        /**
         * When the stored attempt counts were last reset, per the server.
         * Shared by both counters: the server re-stamps it on whichever
         * activity the user does first on a new day, and zeroes the other.
         */
        val attemptsStampedAtMillis: Long? = null
    ) {
        /**
         * Attempts used TODAY.
         *
         * The stored counter is only reset when a claim actually lands (see
         * claimReward), so a user who has not played since yesterday still
         * carries yesterday's number. Applying the rollover here is what lets
         * the quiz screens read the count straight off the snapshot instead
         * of calling checkAndResetQuizAttempts to be told the same thing.
         *
         * This is display only. The cap is enforced inside the claim
         * transaction, against the server's own clock.
         */
        fun quizAttemptsToday(todayUtc: Long): Int =
            attemptsToday(quizAttempts, todayUtc)

        /** Game runs claimed TODAY. Display only, exactly like the quiz count. */
        fun gameAttemptsToday(todayUtc: Long): Int =
            attemptsToday(gameAttempts, todayUtc)

        /**
         * Extra attempts bought TODAY, per activity.
         *
         * All four counters share one day stamp on the server, so they all
         * go stale together and all four have to apply the same rollover
         * here. Reading a bonus straight off the document would show
         * yesterday's purchase as today's headroom - the client half of the
         * bug the server guards against in readDailyAttempts.
         */
        fun bonusQuizAttemptsToday(todayUtc: Long): Int =
            attemptsToday(bonusQuizAttempts, todayUtc)

        /** Extra game runs bought TODAY. */
        fun bonusGameAttemptsToday(todayUtc: Long): Int =
            attemptsToday(bonusGameAttempts, todayUtc)

        private fun attemptsToday(stored: Int, todayUtc: Long): Int {
            val stamped = attemptsStampedAtMillis ?: return 0
            return if (stamped / MILLIS_PER_DAY == todayUtc) stored else 0
        }
    }

    /**
     * Per-day activity counters, mirroring the server's DailyStats.
     *
     * [dayUtc] is what makes these safe to read directly: the server replaces
     * the whole map when the day rolls over, so a stored map from yesterday is
     * spent. [statsForToday] is the only way these should be consumed.
     */
    data class DailyStats(
        val dayUtc: Long = -1,
        val games: Int = 0,
        val quizzes: Int = 0,
        val correct: Int = 0
    )

    /**
     * The counters to measure today against.
     *
     * Anything stamped with an earlier day reads as zero rather than being
     * carried forward - the same rule as the server's statsForDay, and the
     * reason a stale map cannot complete a goal.
     */
    fun statsForToday(stored: DailyStats, todayUtc: Long): DailyStats =
        if (stored.dayUtc == todayUtc) stored else DailyStats(dayUtc = todayUtc)

    private fun parseDailyStats(raw: Any?): DailyStats {
        val map = raw as? Map<*, *> ?: return DailyStats()
        fun int(key: String) = (map[key] as? Number)?.toInt() ?: 0
        return DailyStats(
            dayUtc = (map["dayUtc"] as? Number)?.toLong() ?: -1,
            games = int("games"),
            quizzes = int("quizzes"),
            correct = int("correct")
        )
    }

    /**
     * The daily streak, as stored on the user document.
     *
     * [lastClaimedDayUtc] is whole UTC days since the epoch, matching the
     * server's own representation. An integer rather than a timestamp because
     * the only question asked of it is how many days ago - and because it
     * makes a streak trivial to set up by hand while testing.
     */
    data class Streak(
        val count: Int = 0,
        val lastClaimedDayUtc: Long? = null,
        /**
         * Tracked apart from [lastClaimedDayUtc] on purpose. The streak
         * advances whether or not an ad played; the reward does not. Until
         * this catches up, today's reward is still there to be claimed.
         */
        val lastRewardedDayUtc: Long? = null
    ) {
        /**
         * A streak survives one missed midnight, not two: yesterday still
         * counts, anything older has already lapsed.
         */
        fun isAlive(todayUtc: Long): Boolean =
            lastClaimedDayUtc != null && todayUtc - lastClaimedDayUtc <= 1

        /**
         * The streak has already advanced for today.
         *
         * `>=`, not `==`, to mirror resolveStreakClaim server-side: a last
         * claim dated in the future is treated as already claimed rather than
         * as a lapse, so a corrupt field cannot wipe a real streak. Testing
         * with a hand-set future date lands here too, and the client has to
         * agree with the server about it or the dialog names a day the claim
         * will not pay for.
         */
        fun movedOn(todayUtc: Long): Boolean =
            lastClaimedDayUtc != null && lastClaimedDayUtc >= todayUtc

        /** Today's reward has been paid. */
        fun rewardedOn(todayUtc: Long): Boolean = lastRewardedDayUtc == todayUtc

        /** Days completed in the current seven-day cycle, 0 when lapsed. */
        fun cyclePosition(todayUtc: Long, cycleDays: Int): Int {
            if (!isAlive(todayUtc) || count <= 0) return 0
            return (count - 1) % cycleDays + 1
        }
    }

    /**
     * A temporary multiplier with an expiry. The same shape backs both the
     * Points buff and the XP buff - which one it is comes from the field it
     * was read out of, not from anything in here.
     *
     * Named for its shape rather than its effect on purpose: it was called
     * PointsBuff while also being labelled an "XP boost" in the UI, and the
     * two drifted apart precisely because the type claimed to know.
     *
     * Server-side eligibility decides what a buff actually reaches:
     * MULTIPLIER_ELIGIBLE for Points, XP_MULTIPLIER_ELIGIBLE for XP.
     */
    data class TimedBuff(
        val multiplier: Double,
        val expiresAtMillis: Long
    ) {
        /**
         * Measured against the server's clock, not the device's: expiresAt was
         * issued by the server, so comparing it to device time makes a wrong
         * device clock look like an expired buff.
         */
        fun isActive(nowMillis: Long = ServerClock.now()): Boolean =
            multiplier > 1.0 && expiresAtMillis > nowMillis
    }

    private fun parseBuff(raw: Any?): TimedBuff? {
        val map = raw as? Map<*, *> ?: return null
        val multiplier = (map["multiplier"] as? Number)?.toDouble() ?: return null
        val expiresAt = (map["expiresAt"] as? Number)?.toLong() ?: return null
        return TimedBuff(multiplier, expiresAt)
    }

    /**
     * The queue of unclaimed level bonuses, sorted low to high.
     *
     * Sorted HERE rather than trusted from the document: the server appends
     * with arrayUnion, which preserves insertion order and makes no promise
     * about it, and everything on the client that reads this - the claim
     * button's label, the ladder's tags - is describing "the next one you
     * can claim". The server picks the minimum for itself when it pays, so
     * this is about showing the same answer, not about deciding it.
     */
    private fun parsePendingLevelRewards(raw: Any?): List<Int> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toInt() }
            .filter { it > 1 }
            .distinct()
            .sorted()
    }

    data class RewardClaimResult(
        val pointsAwarded: Int,
        val totalPoints: Int,
        /**
         * The ledger entry this claim wrote, and the handle [claimDoubleXp]
         * doubles against.
         *
         * Comes from the server rather than being rebuilt here: a game entry
         * is `game:<sessionId>` and could have been reconstructed, but a quiz
         * entry is an auto-generated document name that exists nowhere else.
         */
        val eventId: String = "",
        val xpAwarded: Int = 0,
        val totalXp: Int = 0,
        val level: Int = 1,
        val leveledUp: Boolean = false,
        /**
         * The one-time star bonus this claim LOCKED, from any milestone level
         * it crossed. Not paid, and deliberately not in [totalPoints]: level
         * bonuses are released by a rewarded ad on the Level rewards screen
         * (see [claimLevelReward]), so this is what is now waiting there.
         */
        val milestonePoints: Int = 0,
        val wasCorrect: Boolean = false
    )

    suspend fun startGameSession(gameId: String): String {
        val result = functions
            .getHttpsCallable("startGameSession")
            .call(mapOf("gameId" to gameId))
            .await()

        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected game session response")

        return data["sessionId"] as? String
            ?: throw IllegalStateException("Missing game session id")
    }

    suspend fun claimGameReward(gameId: String, score: Int, sessionId: String): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "game",
                "gameId" to gameId,
                "score" to score,
                "sessionId" to sessionId
            )
        )
    }

    /**
     * Doubles the XP a finished game run or quiz answer already paid, having
     * watched a rewarded ad.
     *
     * Deliberately a SECOND call rather than a flag on the claim. The base
     * claim goes in the moment the activity ends, before the offer is even on
     * screen, so an ad that never fills or gets closed early cannot cost the
     * player XP they earned by playing - the offer is upside only. It also
     * means the number on the results screen is real before anyone is asked
     * to watch anything.
     *
     * Keyed on [eventId], the ledger entry the claim returned, which is what
     * lets one call serve both games and quizzes: a quiz attempt is a single
     * question, so it has exactly one entry, the same shape a game run has.
     *
     * No amount is sent. The server reads what it actually paid from its own
     * ledger and matches it, so there is nothing here for a client to
     * inflate, and the double is idempotent on the entry id - a retry after a
     * dropped response cannot pay twice.
     *
     * The ad is asserted rather than proven, as it is for [grantBonusAttempt];
     * one double per session is what bounds it.
     *
     * Short timeout for the same reason the bonus grant has one: the player is
     * watching a spinner on a finished game, and offline this would otherwise
     * hang for a minute with nothing to explain it.
     */
    suspend fun claimDoubleXp(eventId: String): DoubleXpResult {
        return try {
            val result = functions
                .getHttpsCallable("claimDoubleXp")
                .withTimeout(20, TimeUnit.SECONDS)
                .call(mapOf("eventId" to eventId))
                .await()
            val data = result.data as? Map<*, *>
                ?: return DoubleXpResult.Error

            DoubleXpResult.Paid(
                xpAwarded = (data["xpAwarded"] as? Number)?.toInt() ?: 0,
                level = (data["level"] as? Number)?.toInt() ?: 1,
                leveledUp = data["leveledUp"] == true,
                milestonePoints = (data["milestonePoints"] as? Number)?.toInt() ?: 0
            )
        } catch (e: Exception) {
            // ALREADY_EXISTS means a previous attempt got through and the
            // response was lost. The XP is banked either way, so this is a
            // success the player should see as one - reporting it as an error
            // would tell somebody who has already been paid that they were not.
            val code = (e as? FirebaseFunctionsException)?.code
            if (code == FirebaseFunctionsException.Code.ALREADY_EXISTS) {
                DoubleXpResult.AlreadyDoubled
            } else {
                DoubleXpResult.Error
            }
        }
    }

    sealed class DoubleXpResult {
        data class Paid(
            val xpAwarded: Int,
            val level: Int,
            val leveledUp: Boolean,
            val milestonePoints: Int
        ) : DoubleXpResult()

        /** The double had already landed; nothing more to pay, nothing wrong. */
        data object AlreadyDoubled : DoubleXpResult()

        data object Error : DoubleXpResult()
    }

    /**
     * Correctness is decided by the server against its own answer key - the
     * client no longer reports whether the answer was right.
     */
    suspend fun claimQuizReward(
        category: String,
        quizId: String,
        questionIndex: Int,
        selectedAnswer: Int
    ): RewardClaimResult {
        return claimReward(
            mapOf(
                "rewardType" to "quiz",
                "category" to category,
                "quizId" to quizId,
                "questionIndex" to questionIndex,
                "selectedAnswer" to selectedAnswer
            )
        )
    }

    /**
     * The redemption catalogue. Server-managed; the client only reads it.
     *
     * Delegated to [RedemptionOptionsStore] rather than fetched here: this
     * repository is constructed once per view model, so a per-instance fetch
     * meant the same documents were read again for every screen and every
     * return to a tab. The store reads them once per process and then follows
     * the collection live. See its comment for the caching rules.
     */
    val redemptionGames: LiveData<List<RedemptionGame>> = RedemptionOptionsStore.games

    /**
     * Opens the LIVE catalogue listener. Called by the screens that show the
     * catalogue, so a console edit lands where somebody is looking at it.
     * App start uses [RedemptionOptionsStore.seedFromCache] instead.
     */
    fun observeRedemptionGames() = RedemptionOptionsStore.start()

    /** Fills the catalogue from disk without opening a listener. */
    fun seedRedemptionGames() = RedemptionOptionsStore.seedFromCache()

    /**
     * Spends points on one pack of one game.
     *
     * Nothing about the price is sent from here - the server reads the game
     * document, resolves `packs[packId]` and charges what it finds. The same
     * call is used for a discounted first redeem, with [useFirstRedeem] set;
     * the server decides whether the caller is actually entitled to it.
     */
    suspend fun redeem(
        game: RedemptionGame,
        pack: RedemptionPack,
        playerId: String,
        username: String,
        server: String,
        useFirstRedeem: Boolean = false
    ): RedemptionResult {
        return try {
            val payload = mutableMapOf<String, Any>(
                "optionId" to game.id,
                "packId" to pack.id,
                "playerId" to playerId,
                "username" to username,
                "server" to server
            )
            if (useFirstRedeem) payload["useFirstRedeem"] = true

            val result = functions.getHttpsCallable("redeemReward").call(payload).await()
            val data = result.data as? Map<*, *>
                ?: return RedemptionResult.Error("Unexpected response")

            RedemptionResult.Success(
                pointsSpent = (data["pointsSpent"] as? Number)?.toInt() ?: 0,
                remainingPoints = (data["remainingPoints"] as? Number)?.toInt() ?: 0,
                redemptionId = (data["redemptionId"] as? String).orEmpty()
            )
        } catch (e: FirebaseFunctionsException) {
            RedemptionResult.Error(redemptionErrorMessage(e.message))
        } catch (e: Exception) {
            RedemptionResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    /**
     * What this user last entered for a game, so the form can prefill.
     *
     * Their own copy under their own document - deliberately NOT the
     * playerLinks collection, which answers "who owns this ID" and is closed
     * to every client. Returning null just means an empty form.
     */
    suspend fun getGameProfile(gameId: String): GameProfile? {
        val userId = auth.currentUser?.uid ?: return null
        return try {
            val doc = firestore.collection(COLLECTION_USERS).document(userId)
                .collection(COLLECTION_GAME_PROFILES).document(gameId)
                .get().await()
            if (!doc.exists()) return null

            GameProfile(
                playerId = doc.getString("playerId").orEmpty(),
                username = doc.getString("username").orEmpty(),
                server = doc.getString("server").orEmpty()
            )
        } catch (e: Exception) {
            Log.e("Redemption", "Game profile read failed: ${e.message}")
            null
        }
    }

    /**
     * Who used this account's referral code, and how far along they are.
     *
     * Read through a callable, not a query: firestore.rules never grants a
     * client read across users, so the server decides how much of somebody
     * else's account a referrer is allowed to see. Names arrive already
     * masked and there is no uid or email in the response at all.
     *
     * Null means the call failed - most often because getReferralStats is not
     * deployed yet - which the screen shows as "no invites yet" rather than
     * as an error the user can do nothing about.
     */
    suspend fun getReferralStats(): ReferralStats? {
        return try {
            val result = functions.getHttpsCallable("getReferralStats").call().await()
            val data = result.data as? Map<*, *> ?: return null

            val invitees = (data["invitees"] as? List<*>).orEmpty().mapNotNull { raw ->
                val row = raw as? Map<*, *> ?: return@mapNotNull null
                Invitee(
                    name = row["name"] as? String ?: return@mapNotNull null,
                    joinedAtMillis = (row["joinedAtMillis"] as? Number)?.toLong(),
                    xp = (row["xp"] as? Number)?.toInt() ?: 0,
                    xpTarget = (row["xpTarget"] as? Number)?.toInt() ?: 0,
                    qualified = row["qualified"] == true,
                    paid = row["paid"] == true
                )
            }

            ReferralStats(
                invitees = invitees,
                invited = (data["invited"] as? Number)?.toInt() ?: invitees.size,
                qualified = (data["qualified"] as? Number)?.toInt() ?: 0,
                paid = (data["paid"] as? Number)?.toInt() ?: 0,
                unlockXp = (data["unlockXp"] as? Number)?.toInt() ?: 0,
                referrerReward = (data["referrerReward"] as? Number)?.toInt() ?: 0,
                refereeReward = (data["refereeReward"] as? Number)?.toInt() ?: 0
            )
        } catch (e: Exception) {
            Log.w("Referral", "Referral stats unavailable: ${e.message}")
            null
        }
    }

    data class ReferralStats(
        val invitees: List<Invitee>,
        val invited: Int,
        val qualified: Int,
        val paid: Int,
        /** XP a referee must earn before the referrer is paid. */
        val unlockXp: Int,
        val referrerReward: Int,
        val refereeReward: Int
    )

    /**
     * One person who used this account's code.
     *
     * [name] is already masked server-side. There is deliberately no uid,
     * email or balance here - inviting somebody does not entitle you to watch
     * their account.
     */
    data class Invitee(
        val name: String,
        val joinedAtMillis: Long?,
        val xp: Int,
        val xpTarget: Int,
        val qualified: Boolean,
        val paid: Boolean
    )

    data class GameProfile(
        val playerId: String,
        val username: String,
        val server: String
    )

    /**
     * The level at which the discounted first redeem unlocks.
     *
     * Read from config rather than hardcoded so the number can be retuned
     * without an app release - the server reads the same field when it
     * validates the claim, so the two cannot disagree for long. A failed read
     * falls back to the same default the server uses.
     *
     * MEMOISED FOR THE PROCESS, and cache-first before that, for the same
     * reason LevelCurveStore is: the repository is constructed per view
     * model, so Wallet and the Level rewards screen each used to pay their
     * own SERVER round trip for a document that only changes when somebody
     * edits the console. Worse, that round trip was on the path of the Level
     * rewards ladder - the first-redeem rung could not be placed until the
     * network answered, so the whole ladder was built once without it and
     * again with it.
     *
     * The refresh below keeps "briefly stale" from becoming "permanently
     * stale": the disk copy is what this call returns, and one server read
     * behind it corrects the memo for the next open. Nothing here decides
     * what is GRANTED - validateRedemption re-reads the same field on every
     * claim - so a display that is one screen-open behind is harmless.
     */
    suspend fun getFirstRedeemMinLevel(): Int {
        cachedFirstRedeemMinLevel?.let { return it }

        val doc = firestore.collection(COLLECTION_CONFIG).document(DOC_REDEMPTION)

        // Free and instant whenever the disk copy is there, which after the
        // first read it always is.
        val cached = try {
            doc.get(Source.CACHE).await().getLong("firstRedeemMinLevel")?.toInt()
        } catch (e: Exception) {
            null
        }

        if (cached != null) {
            cachedFirstRedeemMinLevel = cached
            // One billed read, in the background, off the render path.
            doc.get(Source.SERVER).addOnSuccessListener { fresh ->
                fresh.getLong("firstRedeemMinLevel")?.toInt()?.let {
                    cachedFirstRedeemMinLevel = it
                }
            }
            return cached
        }

        // Nothing on disk yet - first launch, or cleared data.
        return try {
            val level = doc.get(Source.SERVER).await()
                .getLong("firstRedeemMinLevel")?.toInt() ?: DEFAULT_FIRST_REDEEM_MIN_LEVEL
            cachedFirstRedeemMinLevel = level
            level
        } catch (e: Exception) {
            // The default is deliberately NOT memoised: it is a guess made
            // because the read failed, and the next caller should try again
            // rather than inherit it for the life of the process.
            DEFAULT_FIRST_REDEEM_MIN_LEVEL
        }
    }

    /**
     * Turns a server rejection code into something a player can act on.
     *
     * The codes are the server's vocabulary and are deliberately not shown
     * raw. The linked-ID message is the one that has to be unambiguous: it is
     * the anti-farming rule speaking, and a vague "something went wrong"
     * there would read as a bug rather than as a warning.
     */
    private fun redemptionErrorMessage(raw: String?): String = when (raw) {
        "insufficient_points" -> "You don't have enough stars yet."
        "level_too_low" -> "Reach a higher level to unlock this game."
        "option_disabled" -> "This game is no longer available."
        "pack_disabled" -> "This pack is no longer available."
        "unknown_option" -> "This game could not be found."
        "unknown_pack" -> "This pack could not be found."
        "invalid_option" -> "This pack is misconfigured. Try another one."
        "player_id_required" -> "Enter a valid player ID."
        "username_required" -> "Enter your in-game username."
        "server_required" -> "Choose your server."
        "uid_linked_to_another_account" ->
            "This UID is linked with another account. If we notice spam we " +
                "will ban the user and that UID forever."
        "first_redeem_used" -> "You have already used your first-redeem discount."
        "first_redeem_level_too_low" -> "Reach the required level to unlock this offer."
        "first_redeem_unavailable" -> "This pack is not part of the first-redeem offer."
        else -> raw ?: "Redemption failed"
    }

    /**
     * One of today's goals.
     *
     * [kind] is an identifier, not a label: the wording lives in strings.xml
     * so it can be translated, while the server stays the authority on what
     * is being asked and how far along it is.
     */
    data class DailyGoal(
        val id: String,
        val kind: String,
        val target: Int,
        val progress: Int,
        val done: Boolean
    )

    data class DailyGoals(
        val goals: List<DailyGoal>,
        val bonusPoints: Int,
        val bonusClaimed: Boolean,
        val dayUtc: Long
    ) {
        val doneCount: Int get() = goals.count { it.done }
        val allDone: Boolean get() = goals.isNotEmpty() && doneCount == goals.size
    }

    /** One place on the weekly board. The name arrives already masked. */
    data class LeaderboardEntry(
        val rank: Int,
        val name: String,
        val xp: Int,
        val prize: Int,
        val isMe: Boolean
    )

    data class Leaderboard(
        val entries: List<LeaderboardEntry>,
        val myRank: Int,
        val myXp: Int,
        val myPrize: Int,
        val prizePool: Int,
        val size: Int,
        /** When the standings reset, as the server reckons it. */
        val weekEndsAtMillis: Long
    ) {
        /** Zero rank means no play this week, not last place. */
        val isRanked: Boolean get() = myRank > 0
    }

    /** One day of the streak cycle, as the server describes it. */
    data class StreakDayReward(val points: Int, val xp: Int)

    sealed class StreakClaimResult {
        /** The reward was paid. */
        data class Rewarded(
            val day: Int,
            val pointsAwarded: Int,
            val xpAwarded: Int
        ) : StreakClaimResult()

        /**
         * The streak moved on but nothing was paid - no ad was watched, or
         * today was already rewarded. Not an error: with no ad the reward is
         * simply still waiting, and the user can try again all day.
         */
        data class NotRewarded(val day: Int, val reason: String?) : StreakClaimResult()

        data class Error(val message: String) : StreakClaimResult()
    }

    /**
     * Advances the streak and, if [adWatched], pays today's reward.
     *
     * [adWatched] is only ever true when AdMob's onRewarded callback fired.
     * The server treats it as a claim, not proof - see resolveStreakReward for
     * why that is still worth gating on.
     */
    suspend fun claimDailyStreak(adWatched: Boolean): StreakClaimResult {
        return try {
            // Well under the 70s default. This call happens with the user
            // watching the button, and offline it would otherwise hang for
            // over a minute with the button disabled and nothing to explain
            // why. Failing fast lets them retry - the reward is still there,
            // because a call that never reached the server changed nothing.
            val result = functions
                .getHttpsCallable("claimDailyStreak")
                .withTimeout(20, TimeUnit.SECONDS)
                .call(mapOf("adWatched" to adWatched))
                .await()
            val data = result.data as? Map<*, *>
                ?: return StreakClaimResult.Error("Unexpected response")
            syncClock(data)

            val day = (data["day"] as? Number)?.toInt() ?: 0

            if (data["rewarded"] == true) {
                StreakClaimResult.Rewarded(
                    day = day,
                    pointsAwarded = (data["pointsAwarded"] as? Number)?.toInt() ?: 0,
                    xpAwarded = (data["xpAwarded"] as? Number)?.toInt() ?: 0
                )
            } else {
                StreakClaimResult.NotRewarded(day, data["reason"] as? String)
            }
        } catch (e: Exception) {
            StreakClaimResult.Error(e.message ?: "Could not claim your streak")
        }
    }

    /** Which allowance a bonus attempt is being bought for. */
    enum class BonusActivity(val wireName: String) {
        QUIZ("quiz"),
        GAME("game")
    }

    sealed class BonusAttemptResult {
        /** [allowance] is the new total for today, base plus bonuses. */
        data class Granted(val allowance: Int, val bonusAttempts: Int) : BonusAttemptResult()

        /** The server refused - already at the daily bonus cap. */
        data object AtCap : BonusAttemptResult()

        data class Error(val message: String) : BonusAttemptResult()
    }

    /**
     * Buys one extra attempt for today, having watched a rewarded ad.
     *
     * The ad is asserted, not proven: there is no server-side verification,
     * so `adWatched` is taken on trust exactly as claimDailyStreak takes it.
     * What makes that survivable is the server's per-day cap, which a lying
     * client cannot get past - see MAX_DAILY_BONUS_ATTEMPTS.
     *
     * Short timeout for the same reason the streak claim has one: the user is
     * watching a button, and offline this would otherwise hang for over a
     * minute with nothing to explain it. A call that never reached the server
     * changed nothing, so retrying is safe - and the ad itself is already
     * spent either way, which is why this is called the moment the reward
     * fires rather than after the ad is dismissed.
     */
    suspend fun grantBonusAttempt(activity: BonusActivity): BonusAttemptResult {
        return try {
            val result = functions
                .getHttpsCallable("grantBonusAttempt")
                .withTimeout(20, TimeUnit.SECONDS)
                .call(mapOf("activity" to activity.wireName, "adWatched" to true))
                .await()
            val data = result.data as? Map<*, *>
                ?: return BonusAttemptResult.Error("Unexpected response")
            syncClock(data)

            if (data["granted"] == true) {
                BonusAttemptResult.Granted(
                    allowance = (data["allowance"] as? Number)?.toInt() ?: 0,
                    bonusAttempts = (data["bonusAttempts"] as? Number)?.toInt() ?: 0
                )
            } else {
                // The only refusal the server returns rather than throws. The
                // button should have been hidden before it could be reached,
                // so this is worth a specific message rather than a shrug.
                BonusAttemptResult.AtCap
            }
        } catch (e: Exception) {
            BonusAttemptResult.Error(e.message ?: "Could not add an attempt")
        }
    }

    sealed class LevelRewardResult {
        /**
         * One level was released. [pendingLevels] is what is LEFT, so the
         * screen can go straight on to offering the next one rather than
         * waiting for the snapshot to come back and tell it.
         */
        data class Claimed(
            val level: Int,
            val pointsAwarded: Int,
            val totalPoints: Int,
            val pendingLevels: List<Int>
        ) : LevelRewardResult()

        /**
         * The server had nothing queued, or the level was already settled.
         *
         * Not an error and not worth a red toast: it means this screen was
         * looking at a stale snapshot, or the button was tapped twice. The
         * ad, sadly, is already spent either way - which is why the claim is
         * fired the moment the reward callback lands rather than after the ad
         * is dismissed.
         */
        data class NothingToClaim(val pendingLevels: List<Int>) : LevelRewardResult()

        data class Error(val message: String) : LevelRewardResult()
    }

    /**
     * Releases the LOWEST locked level bonus, having watched a rewarded ad.
     *
     * The level is deliberately not a parameter. The server drains its own
     * queue lowest-first (see claimLevelReward), so a client cannot skip
     * ahead to the biggest one - and passing a level here would suggest it
     * could, which is exactly the misunderstanding a stale screen would act
     * on.
     *
     * The ad is asserted, not proven, on the same terms as the streak and
     * bonus-attempt claims. It is the mildest case of that trade in the app:
     * the amount was fixed by the server when the level was crossed, so
     * lying skips an ad rather than minting stars.
     *
     * Short timeout for the reason every user-facing callable here has one -
     * somebody is watching a button, and offline this would otherwise hang
     * for over a minute with nothing to explain it.
     */
    suspend fun claimLevelReward(): LevelRewardResult {
        return try {
            val result = functions
                .getHttpsCallable("claimLevelReward")
                .withTimeout(20, TimeUnit.SECONDS)
                .call()
                .await()
            val data = result.data as? Map<*, *>
                ?: return LevelRewardResult.Error("Unexpected response")
            syncClock(data)

            val pending = parsePendingLevelRewards(data["pendingLevels"])
            if (data["claimed"] == true) {
                LevelRewardResult.Claimed(
                    level = (data["level"] as? Number)?.toInt() ?: 0,
                    pointsAwarded = (data["pointsAwarded"] as? Number)?.toInt() ?: 0,
                    totalPoints = (data["totalPoints"] as? Number)?.toInt() ?: 0,
                    pendingLevels = pending
                )
            } else {
                LevelRewardResult.NothingToClaim(pending)
            }
        } catch (e: Exception) {
            LevelRewardResult.Error(e.message ?: "Could not claim that reward")
        }
    }

    /**
     * The reward for every day of the cycle, so the strip can show what each
     * day pays before anything is claimed. Read from the server rather than
     * duplicated here, for the same reason the level curve is.
     */
    suspend fun getStreakConfig(): List<StreakDayReward> {
        return try {
            val result = functions.getHttpsCallable("getStreakConfig").call().await()
            val data = result.data as? Map<*, *>
            syncClock(data)
            parseCycle(data?.get("cycle"))
        } catch (e: Exception) {
            Log.e("Streak", "Could not load streak config: ${e.message}")
            emptyList()
        }
    }

    /**
     * Every callable returns the server's clock, so whichever one lands first
     * sets it. Before this the offset came only from the quiz reset, which
     * left every other countdown drawing against device time until that one
     * call happened to return.
     */
    private fun syncClock(data: Map<*, *>?) {
        (data?.get("serverTime") as? Number)?.toLong()?.let { ServerClock.sync(it) }
    }

    private fun parseCycle(raw: Any?): List<StreakDayReward> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            StreakDayReward(
                points = (map["points"] as? Number)?.toInt() ?: 0,
                xp = (map["xp"] as? Number)?.toInt() ?: 0
            )
        }
    }

    /**
     * What the user has redeemed and is waiting on.
     *
     * [title] and [requestedAtMillis] describe the OLDEST outstanding request -
     * the one that will clear first, and so the one a countdown should track.
     */
    data class PendingRedemptions(
        val count: Int,
        val title: String,
        val requestedAtMillis: Long?
    )

    private val _pendingRedemptions = MutableLiveData(PendingRedemptions(0, "", null))
    val pendingRedemptions: LiveData<PendingRedemptions> = _pendingRedemptions

    /** A redemption the admin has settled, approved or rejected. */
    data class ResolvedRedemption(
        val id: String,
        val title: String,
        val approved: Boolean,
        val resolvedAtMillis: Long,
        val refundedPoints: Int,
        val rejectionReason: String?
    )

    private val _resolvedRedemptions = MutableLiveData<List<ResolvedRedemption>>(emptyList())
    val resolvedRedemptions: LiveData<List<ResolvedRedemption>> = _resolvedRedemptions

    /**
     * One row on the Orders tab.
     *
     * Built from the same `redemptions` snapshot the pending row already
     * listens to, rather than a second query: the documents are identical,
     * and one listener means the two views can never disagree about what is
     * outstanding.
     */
    data class Order(
        val id: String,
        val gameName: String,
        val code: String,
        val packAmount: String,
        val playerId: String,
        val username: String,
        val server: String,
        val pointsCost: Int,
        val status: OrderStatus,
        val createdAtMillis: Long?,
        val rejectionReason: String?
    )

    /**
     * The three states the tracker draws, mapped from the two the server
     * actually stores.
     *
     * The design shows Placed -> Processing -> Delivered. `pending` covers
     * the first two: a request that has been recorded but not yet actioned is
     * genuinely at step one, and there is no separate "an operator picked
     * this up" state to read. Rather than invent one, PENDING renders at the
     * Processing dot - the honest reading of "we have it, it is not done".
     */
    enum class OrderStatus { PENDING, DELIVERED, REJECTED }

    private val _orders = MutableLiveData<List<Order>>(emptyList())
    val orders: LiveData<List<Order>> = _orders

    /**
     * One entry in the public payout feed. The name arrives already masked.
     *
     * [packAmount] is what the row says the user received ("1000 UC"); it is
     * empty on entries written before resolveRedemption started publishing
     * it, which is why [label] falls back to the game name rather than
     * showing a blank line for old payouts.
     */
    data class PayoutFeedEntry(
        val name: String,
        val optionTitle: String,
        val packAmount: String,
        val atMillis: Long
    ) {
        /** What was received, as the feed should word it. */
        val label: String get() = packAmount.ifBlank { optionTitle }
    }

    private val _payoutFeed = MutableLiveData<List<PayoutFeedEntry>>(emptyList())
    val payoutFeed: LiveData<List<PayoutFeedEntry>> = _payoutFeed

    /**
     * Listens to this user's redemptions, settled and unsettled alike.
     *
     * One equality filter and no ordering, deliberately: that is served from
     * the automatic single-field indexes, so no composite index has to be
     * deployed alongside. Splitting settled from unsettled and sorting both
     * happens here instead, over a handful of documents.
     *
     * The uid filter is also what makes the query legal - the rules allow a
     * read only where the document's uid matches the caller, and a query has
     * to prove that up front rather than per document.
     */
    private fun listenToRedemptions(userId: String) {
        firestore.collection(COLLECTION_REDEMPTIONS)
            .whereEqualTo(FIELD_UID, userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e("Redemption", "Listener failed: ${error?.message}")
                    return@addSnapshotListener
                }

                val (pending, resolved) = snapshot.documents.partition {
                    it.getString(FIELD_STATUS) == STATUS_PENDING
                }

                // The oldest request is the one closest to being ready, so it
                // is the one the row describes.
                val oldest = pending
                    .filter { it.getTimestamp(FIELD_CREATED_AT) != null }
                    .minByOrNull { it.getTimestamp(FIELD_CREATED_AT)!!.toDate().time }

                _pendingRedemptions.postValue(
                    PendingRedemptions(
                        count = pending.size,
                        title = oldest?.getString(FIELD_OPTION_TITLE).orEmpty(),
                        requestedAtMillis =
                            oldest?.getTimestamp(FIELD_CREATED_AT)?.toDate()?.time
                    )
                )

                _resolvedRedemptions.postValue(
                    resolved.mapNotNull { doc ->
                        val at = doc.getTimestamp(FIELD_RESOLVED_AT)?.toDate()?.time
                            ?: return@mapNotNull null
                        ResolvedRedemption(
                            id = doc.id,
                            title = doc.getString(FIELD_OPTION_TITLE).orEmpty(),
                            approved = doc.getString(FIELD_STATUS) == STATUS_APPROVED,
                            resolvedAtMillis = at,
                            refundedPoints =
                                doc.getLong(FIELD_REFUNDED_POINTS)?.toInt() ?: 0,
                            rejectionReason = doc.getString(FIELD_REJECTION_REASON)
                        )
                    }.sortedByDescending { it.resolvedAtMillis }
                )

                _orders.postValue(
                    snapshot.documents.map { doc ->
                        Order(
                            id = doc.id,
                            gameName = doc.getString(FIELD_OPTION_TITLE).orEmpty(),
                            code = doc.getString(FIELD_OPTION_TYPE).orEmpty(),
                            packAmount = doc.getString(FIELD_PACK_AMOUNT).orEmpty(),
                            playerId = doc.getString(FIELD_PLAYER_ID).orEmpty(),
                            username = doc.getString(FIELD_USERNAME).orEmpty(),
                            server = doc.getString(FIELD_SERVER).orEmpty(),
                            pointsCost = doc.getLong(FIELD_POINTS_COST)?.toInt() ?: 0,
                            status = when (doc.getString(FIELD_STATUS)) {
                                STATUS_APPROVED -> OrderStatus.DELIVERED
                                STATUS_PENDING -> OrderStatus.PENDING
                                else -> OrderStatus.REJECTED
                            },
                            createdAtMillis =
                                doc.getTimestamp(FIELD_CREATED_AT)?.toDate()?.time,
                            rejectionReason = doc.getString(FIELD_REJECTION_REASON)
                        )
                    }.sortedByDescending { it.createdAtMillis ?: 0L }
                )
            }
    }

    /**
     * The earning history behind the Activity list.
     *
     * Read from the ledger the economy already writes - every points movement
     * has been landing in users/{uid}/rewardEvents since the award path was
     * built, and firestore.rules has always allowed the owner to read it. So
     * there is no new collection and no new callable here; this is the screen
     * that ledger was written for.
     *
     * Spending shows up in the same list as earning (a REDEMPTION entry
     * carries negative finalPoints), and a declined payout writes a refund
     * entry while marking the original reversed - so one query tells the
     * whole truth rather than a flattering half of it.
     *
     * ONLY entries that moved stars are returned. Quizzes and games award XP
     * and no points at all (see rewardConfig.ts - levelling is their payoff),
     * and they are also by far the most frequent thing in the ledger, so a
     * list that included them would bury the handful of lines that actually
     * explain the balance above it.
     *
     * The filter is part of the QUERY, not a pass over the results: the
     * server stamps `affectsPoints` on every ledger entry, so this reads
     * exactly as many documents as it displays. It used to read a wide window
     * and discard most of it, which cost a read per XP event nobody was going
     * to see.
     *
     * Needs the (affectsPoints, createdAt) composite index in
     * firestore.indexes.json - an equality plus an order-by on a different
     * field is the one shape Firestore will not serve automatically.
     *
     * FALLBACK: `affectsPoints` is written by the server, so a project whose
     * functions have not been redeployed yet has entries without it - and a
     * Firestore equality filter does not match a missing field, so the fast
     * query silently returns nothing. That looked exactly like a broken
     * screen. When the fast path yields nothing, this re-reads a plain window
     * and filters here instead, which works on old and new entries alike.
     *
     * The fallback costs a wider read, so it is not the normal path - it is
     * what keeps the screen honest between deploying the app and deploying
     * the backend, and it stops paying for itself the moment the flag and the
     * index are live.
     */
    suspend fun getEarningHistory(limit: Long = HISTORY_LIMIT): List<LedgerEntry> {
        val userId = auth.currentUser?.uid ?: return emptyList()
        val events = firestore.collection(COLLECTION_USERS).document(userId)
            .collection(COLLECTION_REWARD_EVENTS)

        val indexed = runCatching {
            events
                .whereEqualTo(FIELD_AFFECTS_POINTS, true)
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull(::toLedgerEntry)
        }.onFailure {
            // Almost always the composite index not being deployed yet.
            Log.w("History", "Indexed ledger read failed, falling back: ${it.message}")
        }.getOrNull()

        if (!indexed.isNullOrEmpty()) return indexed

        return runCatching {
            events
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .limit(LEGACY_STAR_WINDOW)
                .get().await()
                .documents.mapNotNull(::toLedgerEntry)
                .filter { it.points != 0 }
                .take(limit.toInt())
        }.onFailure {
            Log.e("History", "Ledger read failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun toLedgerEntry(doc: com.google.firebase.firestore.DocumentSnapshot): LedgerEntry? {
        // A locked level-up bonus records a promise, not a movement, so it has
        // no place in a list that explains the balance above it. The indexed
        // query already excludes it (affectsPoints is false until it is
        // claimed); this is what keeps the wide fallback query honest too,
        // since that one filters on the amount alone.
        if (doc.getString(FIELD_LEDGER_STATUS) == LEDGER_LOCKED) return null
        return LedgerEntry(
            id = doc.id,
            source = doc.getString(FIELD_SOURCE).orEmpty(),
            points = doc.getLong(FIELD_FINAL_POINTS)?.toInt() ?: 0,
            xp = doc.getLong(FIELD_XP_AWARDED)?.toInt() ?: 0,
            atMillis = doc.getTimestamp(FIELD_CREATED_AT)?.toDate()?.time ?: return null,
            reversed = doc.getString(FIELD_LEDGER_STATUS) == LEDGER_REVERSED,
            // Free-form per source. The only key read is the one that names
            // what a redemption was for; everything else is described by
            // `source` alone.
            detail = (doc.get(FIELD_METADATA) as? Map<*, *>)?.get("packAmount") as? String
        )
    }

    /**
     * One line of the ledger.
     *
     * No display string is stored - the ledger records what happened, not how
     * to word it - so the label is built on the client from [source] and
     * [detail]. That is deliberate: a stored label would be frozen in
     * whatever language and wording it had when it was written.
     */
    data class LedgerEntry(
        val id: String,
        val source: String,
        val points: Int,
        val xp: Int,
        val atMillis: Long,
        val reversed: Boolean,
        val detail: String?
    )

    /**
     * The rest of the feed, fetched only when the sheet is actually opened.
     *
     * Deliberately a one-shot rather than widening the live listener: the
     * other nineteen rows are worth a read when somebody asks to see them,
     * and worth nothing on a launch where nobody does.
     */
    suspend fun getPayoutFeed(limit: Long = PAYOUT_FEED_SHEET_LIMIT): List<PayoutFeedEntry> {
        return try {
            firestore.collection(COLLECTION_PAYOUT_FEED)
                .orderBy(FIELD_APPROVED_AT, Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents.mapNotNull(::parsePayoutFeedEntry)
        } catch (e: Exception) {
            Log.e("PayoutFeed", "Feed read failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * The public payout feed - other people's approved redemptions, names
     * already masked server-side. Nothing identifying is in this collection;
     * see economy/payoutFeed.ts for why it is separate from `redemptions`.
     */
    private fun listenToPayoutFeed() {
        firestore.collection(COLLECTION_PAYOUT_FEED)
            .orderBy(FIELD_APPROVED_AT, Query.Direction.DESCENDING)
            .limit(PAYOUT_FEED_ROW_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e("PayoutFeed", "Feed listener failed: ${error?.message}")
                    return@addSnapshotListener
                }

                _payoutFeed.postValue(snapshot.documents.mapNotNull(::parsePayoutFeedEntry))
            }
    }

    /** Shared by the live row listener and the on-demand sheet fetch. */
    private fun parsePayoutFeedEntry(
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): PayoutFeedEntry? {
        val at = doc.getTimestamp(FIELD_APPROVED_AT)?.toDate()?.time ?: return null
        return PayoutFeedEntry(
            name = doc.getString("name").orEmpty(),
            optionTitle = doc.getString("optionTitle").orEmpty(),
            packAmount = doc.getString("packAmount").orEmpty(),
            atMillis = at
        )
    }

    // getDailyGoals was removed here and on the server. MainViewModel.dailyGoals
    // derives the same three goals from DailyGoalEngine plus the user snapshot,
    // so this was a Firestore read per return to Home for an answer already in
    // memory - and a second implementation of the selection rule to keep in
    // step with the one claimDailyGoalBonus actually enforces.

    sealed class GoalBonusResult {
        data class Claimed(val pointsAwarded: Int) : GoalBonusResult()
        data class NotClaimed(val reason: String?) : GoalBonusResult()
        data class Error(val message: String) : GoalBonusResult()
    }

    suspend fun claimDailyGoalBonus(adWatched: Boolean): GoalBonusResult {
        return try {
            val result = functions
                .getHttpsCallable("claimDailyGoalBonus")
                .withTimeout(20, TimeUnit.SECONDS)
                .call(mapOf("adWatched" to adWatched))
                .await()
            val data = result.data as? Map<*, *>
                ?: return GoalBonusResult.Error("Unexpected response")
            syncClock(data)

            if (data["claimed"] == true) {
                GoalBonusResult.Claimed((data["pointsAwarded"] as? Number)?.toInt() ?: 0)
            } else {
                GoalBonusResult.NotClaimed(data["reason"] as? String)
            }
        } catch (e: Exception) {
            GoalBonusResult.Error(e.message ?: "Could not claim your bonus")
        }
    }

    /**
     * The weekly board and the caller's place on it.
     *
     * [full] asks for every place rather than the podium. Home wants the
     * caller's rank and the top few; only the sheet needs a hundred rows.
     *
     * Rank is computed server-side with a count query, so it costs the same
     * whether the caller is twelfth or twenty-thousandth.
     */
    suspend fun getLeaderboard(full: Boolean = false): Leaderboard? {
        return try {
            val result = functions
                .getHttpsCallable("getLeaderboard")
                .call(mapOf("full" to full))
                .await()
            val data = result.data as? Map<*, *> ?: return null
            syncClock(data)

            val entries = (data["entries"] as? List<*>).orEmpty().mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                LeaderboardEntry(
                    rank = (map["rank"] as? Number)?.toInt() ?: return@mapNotNull null,
                    name = map["name"] as? String ?: "",
                    xp = (map["xp"] as? Number)?.toInt() ?: 0,
                    prize = (map["prize"] as? Number)?.toInt() ?: 0,
                    isMe = map["isMe"] == true
                )
            }

            Leaderboard(
                entries = entries,
                myRank = (data["myRank"] as? Number)?.toInt() ?: 0,
                myXp = (data["myXp"] as? Number)?.toInt() ?: 0,
                myPrize = (data["myPrize"] as? Number)?.toInt() ?: 0,
                prizePool = (data["prizePool"] as? Number)?.toInt() ?: 0,
                size = (data["size"] as? Number)?.toInt() ?: 0,
                weekEndsAtMillis = (data["weekEndsAt"] as? Number)?.toLong() ?: 0L
            )
        } catch (e: Exception) {
            Log.e("Leaderboard", "Could not load the board: ${e.message}")
            null
        }
    }

    suspend fun submitReferral(referralCode: String): ReferralResult {
        return try {
            val result = functions
                .getHttpsCallable("submitReferral")
                .call(mapOf("referralCode" to referralCode))
                .await()

            val data = result.data as? Map<*, *>
            when (data?.get("status") as? String) {
                "success" -> ReferralResult.Success
                "invalid_code" -> ReferralResult.InvalidCode
                else -> ReferralResult.Error("Unexpected referral response")
            }
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                ReferralResult.AlreadyUsed
            } else {
                ReferralResult.Error(e.message ?: "Unknown error occurred")
            }
        } catch (e: Exception) {
            ReferralResult.Error(e.message ?: "Unknown error occurred")
        }
    }

    private suspend fun claimReward(payload: Map<String, Any>): RewardClaimResult {
        val result = functions
            .getHttpsCallable("claimReward")
            .call(payload)
            .await()

        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected reward response")

        return RewardClaimResult(
            pointsAwarded = data.getInt("pointsAwarded"),
            totalPoints = data.getInt("totalPoints"),
            eventId = data["eventId"] as? String ?: "",
            xpAwarded = data.optInt("xpAwarded"),
            totalXp = data.optInt("totalXp"),
            level = data.optInt("level", default = 1),
            leveledUp = data["leveledUp"] as? Boolean ?: false,
            milestonePoints = data.optInt("milestonePoints"),
            wasCorrect = data["wasCorrect"] as? Boolean ?: false
        )
    }

    private fun Map<*, *>.getInt(key: String): Int {
        return (this[key] as? Number)?.toInt()
            ?: throw IllegalStateException("Missing reward field: $key")
    }

    private fun Map<*, *>.optInt(key: String, default: Int = 0): Int {
        return (this[key] as? Number)?.toInt() ?: default
    }

    companion object {
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_CONFIG = "config"
        private const val FIELD_POINTS = "points"
        private const val FIELD_XP = "xp"
        private const val FIELD_LEVEL = "level"
        private const val FIELD_PENDING_LEVEL_REWARDS = "pendingLevelRewards"
        private const val FIELD_LAST_RESET_TIME = "last_reset_time"
        private const val FIELD_QUIZ_ATTEMPTS = "quiz_attempts"
        private const val FIELD_GAME_ATTEMPTS = "game_attempts"
        private const val FIELD_BONUS_QUIZ_ATTEMPTS = "bonus_quiz_attempts"
        private const val FIELD_BONUS_GAME_ATTEMPTS = "bonus_game_attempts"
        private const val FIELD_DAILY_STATS = "dailyStats"
        private const val FIELD_LAST_GOAL_BONUS_DAY = "lastGoalBonusDayUtc"
        private const val DOC_DAILY_GOALS = "dailyGoals"
        /** Mirrors DAILY_GOAL_BONUS_POINTS / MAX_DAILY_GOAL_BONUS_POINTS. */
        private const val DEFAULT_GOAL_BONUS_POINTS = 30
        private const val MAX_GOAL_BONUS_POINTS = 200
        private const val FIELD_ACTIVE_BUFF = "activeBuff"
        private const val FIELD_ACTIVE_XP_BUFF = "activeXpBuff"
        private const val FIELD_STREAK_COUNT = "streakCount"
        private const val FIELD_LAST_STREAK_DAY = "lastStreakDayUtc"
        private const val FIELD_LAST_STREAK_REWARD_DAY = "lastStreakRewardDayUtc"
        private const val FIELD_REFERRAL_CODE = "referralCode"
        private const val FIELD_HAS_USED_REFERRAL = "hasUsedReferral"
        private const val FIELD_HAS_USED_FIRST_REDEEM = "hasUsedFirstRedeem"
        private const val COLLECTION_GAME_PROFILES = "gameProfiles"
        private const val DOC_REDEMPTION = "redemption"
        /** Matches DEFAULT_FIRST_REDEEM_MIN_LEVEL in the functions package. */
        private const val DEFAULT_FIRST_REDEEM_MIN_LEVEL = 10

        /**
         * The published first-redeem level, once read. Process-wide because
         * the repository is not - see [getFirstRedeemMinLevel].
         */
        @Volatile
        private var cachedFirstRedeemMinLevel: Int? = null

        private const val COLLECTION_REDEMPTIONS = "redemptions"
        private const val COLLECTION_PAYOUT_FEED = "payoutFeed"
        private const val FIELD_UID = "uid"
        private const val FIELD_STATUS = "status"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_APPROVED = "approved"
        private const val FIELD_RESOLVED_AT = "resolvedAt"
        private const val FIELD_REFUNDED_POINTS = "refundedPoints"
        private const val FIELD_REJECTION_REASON = "rejectionReason"
        private const val FIELD_OPTION_TITLE = "optionTitle"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_APPROVED_AT = "approvedAt"
        /**
         * How many feed entries the LIVE listener holds.
         *
         * Three, because Home's payout row draws three overlapping initials
         * beside its one line of text. A snapshot listener bills a read per
         * document in its result set every time a fresh process attaches it,
         * so a window of twenty cost twenty reads on every launch to draw a
         * single line - for every user, whether or not they ever opened the
         * full list. Three is the smallest window the row can be drawn from;
         * the two reads over the text line's own needs buy the cluster, and
         * nothing wider is read until somebody opens the sheet.
         *
         * Note what this does NOT change: the fan-out. Each approved payout
         * puts one new document into the window, so every connected listener
         * is billed one read per payout whether the window holds one entry or
         * twenty. Shrinking the window fixes the per-launch cost; only a
         * digest document fixes the per-payout one, and that is not worth
         * building until the numbers ask for it.
         */
        private const val PAYOUT_FEED_ROW_LIMIT = 3L

        /**
         * How many the sheet fetches on demand, when somebody opens it.
         *
         * Ten rather than twenty: the sheet is social proof, and the tenth
         * row is already old enough that nobody reads to it - so the second
         * ten were ten reads billed per open for nothing.
         */
        const val PAYOUT_FEED_SHEET_LIMIT = 10L

        private const val FIELD_OPTION_TYPE = "optionType"
        private const val FIELD_PACK_AMOUNT = "packAmount"
        private const val FIELD_PLAYER_ID = "playerId"
        private const val FIELD_USERNAME = "username"
        private const val FIELD_SERVER = "server"
        private const val FIELD_POINTS_COST = "pointsCost"

        private const val COLLECTION_REWARD_EVENTS = "rewardEvents"
        private const val FIELD_SOURCE = "source"
        private const val FIELD_FINAL_POINTS = "finalPoints"
        private const val FIELD_XP_AWARDED = "xpAwarded"
        private const val FIELD_METADATA = "metadata"
        private const val FIELD_LEDGER_STATUS = "status"
        private const val LEDGER_REVERSED = "reversed"
        /** A level-up bonus earned but not yet released by an ad. */
        private const val LEDGER_LOCKED = "locked"
        private const val FIELD_AFFECTS_POINTS = "affectsPoints"

        /**
         * How many star movements to read.
         *
         * Sized against what the wallet actually draws (see ACTIVITY_PREVIEW
         * in RedemptionFragment) plus a little headroom, because the query
         * now filters server-side - every document paid for is a document
         * that could be shown. Raise this when there is a full history screen
         * to fill; until then a bigger number is just a bigger bill.
         */
        private const val HISTORY_LIMIT = 15L

        /**
         * The fallback window, used only until `affectsPoints` and its index
         * are deployed. Sized against the daily streak and daily-goals
         * bonuses, which land once each per day, so it holds several star
         * movements even for a heavy quiz player.
         */
        private const val LEGACY_STAR_WINDOW = 80L
    }
}
