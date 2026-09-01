package com.pixelpayout

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.InterstitialAdManager
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp

class PixelPayoutApp : Application() {
    override fun onCreate() {
        super.onCreate()

        FirebaseApp.initializeApp(this)
        Log.d("PixelPayoutApp", "Firebase initialized")

        // Preloaded from the init callback rather than from whichever screen
        // happens to want one first. RewardedAd.load before the SDK is up
        // fails, which used to cost the first screen a failed load plus a
        // backoff - with the pool warm at launch, the first "watch an ad"
        // control a user sees is already live.
        MobileAds.initialize(this) {
            AdManager.getInstance().loadRewardedAd(this)

            // The interstitial is warmed LATER, not in this callback.
            //
            // Both used to fire here, putting three requests (a rewarded pool
            // of two, plus this) on the wire simultaneously at cold start -
            // competing for the same connection at the one moment the app has
            // the least of it, and for an ad nobody can be shown for at least
            // two completed activities. The rewarded pool is what a player
            // can reach within seconds of the app opening, so it goes first
            // and alone.
            //
            // Still well ahead of when it is needed. AdCadence asks whether an
            // ad is cached at the moment a slot comes due and skips the slot
            // outright if not - it never makes the player wait while one
            // loads - so the cache being warm BEFORE the first slot is what
            // decides whether that slot pays. Eight seconds is far inside the
            // three completions the grace period covers.
            Handler(Looper.getMainLooper()).postDelayed({
                InterstitialAdManager.getInstance().load(this)
            }, INTERSTITIAL_WARMUP_DELAY_MS)
        }

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }

    companion object {
        /**
         * How long the interstitial waits before warming, so it does not
         * contend with the rewarded pool during cold start.
         */
        private const val INTERSTITIAL_WARMUP_DELAY_MS = 8_000L
    }
}
