package com.example.pixelpayout.utils

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
         * The highest level whose reward has already been announced.
         *
         * A high-water mark rather than a "seen" flag, because the thing being
         * announced comes back: every level-up owes a new reward, and the
         * dialog has to appear again for it. Comparing against the current
         * level means one announcement per climb, however many screens the
         * player passes through afterwards - and tapping Later does not bring
         * it back on the next return to Home, which is the difference between
         * a prompt and a nag.
         *
         * On this device only. Firestore holds what is OWED; this holds
         * whether we have mentioned it, which is a property of the screen
         * rather than of the account.
         */
        private val LAST_ANNOUNCED_LEVEL = intPreferencesKey("lastAnnouncedLevel")
    }

    val lastAnnouncedLevel: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[LAST_ANNOUNCED_LEVEL] ?: 0 }

    suspend fun setLastAnnouncedLevel(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_ANNOUNCED_LEVEL] = value
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
