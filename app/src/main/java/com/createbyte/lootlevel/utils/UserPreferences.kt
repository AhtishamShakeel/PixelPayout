package com.createbyte.lootlevel.utils

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


private val Context.dataStore by preferencesDataStore("user_prefs")
private val USERNAME_KEY = stringPreferencesKey("username")

class UserPreferences(private val context: Context) {
    companion object {
        private val HAS_SEEN_REFERRAL_POPUP = booleanPreferencesKey("hasSeenReferralPopup")

        /**
         * When the user was last told about a resolved redemption.
         *
         * A timestamp rather than a set of ids: it is one comparison, it never
         * grows, and anything resolved before it is by definition already
         * seen. Redemptions resolve in order, so there is no case where an
         * older one arrives after a newer one has been acknowledged.
         */
        private val LAST_SEEN_REDEMPTION = longPreferencesKey("lastSeenRedemptionResolvedAt")

        /**
         * The streak reward table, as "points:xp" pairs.
         *
         * Cached because it comes from a callable, and callables have no
         * offline cache the way Firestore does - so every return to Home
         * refetched it over the network and drew a card full of blank cells
         * until it landed. The table only changes when the server is
         * redeployed, so showing yesterday's copy for a second is harmless.
         */
        private val STREAK_CYCLE = stringPreferencesKey("streakCycle")

        /**
         * Which pending levels have already been announced, comma-separated.
         *
         * A SET RATHER THAN A HIGH-WATER MARK, which is what this was and
         * what made the dialog stop appearing. The mark only ever rose, so
         * any queue whose maximum sat at or below it was silently swallowed
         * forever - and a queue can perfectly well drop below a level that
         * has already been mentioned: claiming empties it from the bottom,
         * and an account whose XP is reset climbs back through levels it has
         * already been congratulated for. The dialog then never returned on
         * that install until app data was cleared, while levels, XP and the
         * ledger all carried on working.
         *
         * The set answers the question actually being asked - is there
         * anything in the queue we have not mentioned yet - and it cannot
         * grow without bound because it is pruned to the live queue on every
         * read: a level that is no longer owed is no longer tracked.
         *
         * On this device only. Firestore holds what is OWED; this holds
         * whether we have mentioned it, which is a property of the screen
         * rather than of the account.
         */
        private val ANNOUNCED_LEVEL_REWARDS =
            stringPreferencesKey("announcedLevelRewards")

        /**
         * The most recent week whose leaderboard prize has been celebrated.
         *
         * Local for the same reason the level mark is: the prize itself lives
         * on the account, but whether we have shown a dialog about it is a
         * property of this install. A user with two devices should be
         * congratulated on both.
         */
        private val LAST_ANNOUNCED_LEADERBOARD_WEEK =
            intPreferencesKey("lastAnnouncedLeaderboardWeek")

        /** Whether the "Tournament unlocked" dialog has been shown on this install. */
        private val TOURNAMENT_UNLOCK_ANNOUNCED =
            booleanPreferencesKey("tournamentUnlockAnnounced")

        /**
         * The redemption game (catalogue document id) the Stars card on Home
         * measures against.
         *
         * On the device rather than the account: it only decides which prices
         * a progress bar quotes, nothing the server acts on, so it is not
         * worth a Cloud Function. The cost is being asked again after a
         * reinstall, which is one tap.
         */
        private val PREFERRED_GAME_ID = stringPreferencesKey("preferredRedemptionGameId")
    }

    /** Null until the user has picked one. */
    val preferredGameId: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[PREFERRED_GAME_ID] }

    suspend fun setPreferredGameId(value: String) {
        context.dataStore.edit { preferences ->
            preferences[PREFERRED_GAME_ID] = value
        }
    }

    val tournamentUnlockAnnounced: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[TOURNAMENT_UNLOCK_ANNOUNCED] ?: false }

    suspend fun setTournamentUnlockAnnounced() {
        context.dataStore.edit { preferences ->
            preferences[TOURNAMENT_UNLOCK_ANNOUNCED] = true
        }
    }

    val lastAnnouncedLeaderboardWeek: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[LAST_ANNOUNCED_LEADERBOARD_WEEK] ?: 0 }

    suspend fun setLastAnnouncedLeaderboardWeek(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_ANNOUNCED_LEADERBOARD_WEEK] = value
        }
    }

    /**
     * The levels already announced. Anything unparseable reads as "none
     * announced", which costs one extra dialog rather than losing one.
     */
    val announcedLevelRewards: Flow<Set<Int>> = context.dataStore.data
        .map { preferences ->
            preferences[ANNOUNCED_LEVEL_REWARDS]
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                .orEmpty()
        }

    suspend fun setAnnouncedLevelRewards(levels: Set<Int>) {
        context.dataStore.edit { preferences ->
            preferences[ANNOUNCED_LEVEL_REWARDS] =
                levels.sorted().joinToString(",")
        }
    }

    val hasSeenReferralPopup: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HAS_SEEN_REFERRAL_POPUP] ?: false }

    suspend fun setHasSeenReferralPopup(value: Boolean){
        context.dataStore.edit { preferences ->
            preferences[HAS_SEEN_REFERRAL_POPUP] = value
        }
    }
    val lastSeenRedemptionResolvedAt: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_SEEN_REDEMPTION] ?: 0L }

    suspend fun setLastSeenRedemptionResolvedAt(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_SEEN_REDEMPTION] = value
        }
    }

    val streakCycle: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[STREAK_CYCLE] }

    suspend fun setStreakCycle(value: String) {
        context.dataStore.edit { preferences ->
            preferences[STREAK_CYCLE] = value
        }
    }

    val username: Flow<String?> = context.dataStore.data
        .map { preferences -> preferences[USERNAME_KEY] }

    suspend fun setUsername(value: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = value
        }
    }
}
