package com.pixelpayout

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp

class PixelPayoutApp : Application() {
    override fun onCreate() {
        super.onCreate()

        FirebaseApp.initializeApp(this)
        Log.d("PixelPayoutApp", "Firebase initialized")

        MobileAds.initialize(this)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
