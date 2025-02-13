package com.pixelpayout

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.android.gms.ads.MobileAds
import androidx.appcompat.app.AppCompatDelegate

class PixelPayoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Initialize Mobile Ads SDK
        MobileAds.initialize(this)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
} 