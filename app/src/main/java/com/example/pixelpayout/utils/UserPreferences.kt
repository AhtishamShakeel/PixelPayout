package com.example.pixelpayout.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.pixelpayout.data.api.RedeemOption
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


private val Context.dataStore by preferencesDataStore("user_prefs")
private val USERNAME_KEY = stringPreferencesKey("username")

class UserPreferences(private val context: Context) {
    companion object {
        private val HAS_SEEN_REFERRAL_POPUP = booleanPreferencesKey("hasSeenReferralPopup");
        private val REDEEM_VERSION = intPreferencesKey("redeem_version")
        private val REDEEM_LIST_JSON = stringPreferencesKey("redeem_list_json")
    }

    val hasSeenReferralPopup: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HAS_SEEN_REFERRAL_POPUP] ?: false }

    suspend fun savedRedeemCache(version: Int, list: List<RedeemOption>){
        val json = Gson().toJson(list)
        context.dataStore.edit { preferences ->
            preferences[REDEEM_VERSION] = version
            preferences[REDEEM_LIST_JSON] = json
        }

    }

    suspend fun getRedeemCache(): Pair<Int, List<RedeemOption>>? {
        val preferences = context.dataStore.data.first()
        val version = preferences[REDEEM_VERSION] ?: -1
        Log.d("RedeemViewModel", "Cache Loaded version: $version")
        val json = preferences[REDEEM_LIST_JSON] ?: return null

        val type = object : TypeToken<List<RedeemOption>>() {}.type
        val list = Gson().fromJson<List<RedeemOption>>(json, type)

        return Pair(version, list)


    }

    suspend fun setHasSeenReferralPopup(value: Boolean){
        context.dataStore.edit { preferences ->
            preferences[HAS_SEEN_REFERRAL_POPUP] = value
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
