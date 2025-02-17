package com.pixelpayout.utils

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class AdManager private constructor() {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var adAvailabilityCallback: ((Boolean) -> Unit)? = null

    fun setAdAvailabilityCallback(callback: (Boolean) -> Unit) {
        adAvailabilityCallback = callback
        callback(rewardedAd != null)
    }

    fun loadRewardedAd(context: Context) {
        if (rewardedAd != null || isLoading) return

        isLoading = true
        val adRequest = AdRequest.Builder().build()

        // Use test ad unit ID for development
        RewardedAd.load(context,
            "ca-app-pub-3940256099942544/5224354917", // Replace with your actual ad unit ID in production
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    adAvailabilityCallback?.invoke(true)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    adAvailabilityCallback?.invoke(false)
                }
            })
    }

    fun showRewardedAd(
        activity: Activity,
        onRewarded: () -> Unit,
        onAdClosed: () -> Unit,
        onAdFailedToShow: () -> Unit
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onAdFailedToShow()
            loadRewardedAd(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewardedAd(activity)
                onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                onAdFailedToShow()
            }
        }

        ad.show(activity) {
            onRewarded()
        }
    }

    companion object {
        private var instance: AdManager? = null

        fun getInstance(): AdManager {
            if (instance == null) {
                instance = AdManager()
            }
            return instance!!
        }
    }
}