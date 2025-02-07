package com.pixelpayout

import android.app.Application
import com.google.firebase.FirebaseApp

class PixelPayoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Firebase
        FirebaseApp.initializeApp(this)
    }
} 