package com.example.pixelpayout.ui.redeem_section

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelpayout.data.api.RedeemOption
import com.example.pixelpayout.utils.UserPreferences
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class RedeemViewModel : ViewModel() {
    private val _redeemList = MutableLiveData<List<RedeemOption>>()
    val redeemList: LiveData<List<RedeemOption>> get() = _redeemList

    private val db = FirebaseFirestore.getInstance()
    private var hasLoaded = false

    private suspend fun fetchVersionFromFirestore(): Int {
        return try{
            val doc = db.collection("config")
                .document("redemption_version")
                .get()
                .await()
            doc.getLong("version")?.toInt() ?: 0
        } catch (e: Exception){
            Log.e("RedeemViewModel", "Failed to fetch version from Firestore", e)
            -1
        }
    }


    fun loadRedeemOptionsWithCache(userPreferences: UserPreferences) {
        if(hasLoaded) return


        viewModelScope.launch {
            val cache = userPreferences.getRedeemCache()
            Log.d("RedeemViewModel", "Cached Version: ${cache?.first}, List size: ${cache?.second?.size}")
            val latestVersion = fetchVersionFromFirestore()
            Log.d("RedeemViewModel", "Latest version: $latestVersion")

            if (latestVersion == -1 && cache != null) {
                _redeemList.value = cache.second
                hasLoaded = true
                return@launch
            }
            if (cache != null && cache.first == latestVersion) {
                Log.d("RedeemViewModel", "Using cached data")
                _redeemList.value = cache.second
                hasLoaded = true
                return@launch
            }
            loadRedeemOptions(userPreferences, latestVersion)
        }
    }


    suspend fun loadRedeemOptions(userPreferences: UserPreferences, latestVersion: Int) {
        try{
            val snapshot = db.collection("redemptionOptions")
                .whereEqualTo("active", true)
                .get()
                .await()
            val options = snapshot.toObjects(RedeemOption::class.java)
            _redeemList.value = options

            userPreferences.savedRedeemCache(latestVersion, options)
            hasLoaded=true
        } catch (e: Exception){
            Log.e("RedemptionViewModel", "Failed to load redemption options", e)
        }
    }

    fun forceRefresh(userPreferences: UserPreferences){
        hasLoaded = false
        loadRedeemOptionsWithCache(userPreferences)

    }


}