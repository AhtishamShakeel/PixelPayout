package com.example.pixelpayout.utils

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


private val Context.dataStore by preferencesDataStore("user_prefs")

class UserPreferences(private val context: Context) {
    companion object {
        private val HAS_SEEN_REFERRAL_POPUP = booleanPreferencesKey("hasSeenReferralPopup")
    }

    val hasSeenReferralPopup: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HAS_SEEN_REFERRAL_POPUP] ?: false }

    suspend fun setHasSeenReferralPopup(value: Boolean){
        context.dataStore.edit { preferences ->
            preferences[HAS_SEEN_REFERRAL_POPUP] = value
        }
    }
}
