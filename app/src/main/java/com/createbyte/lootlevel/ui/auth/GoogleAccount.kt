package com.createbyte.lootlevel.ui.auth

import android.app.Activity
import com.createbyte.lootlevel.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions

/** The one Google sign-in configuration, shared by sign-in and guest linking. */
object GoogleAccount {
    fun client(activity: Activity): GoogleSignInClient =
        GoogleSignIn.getClient(
            activity,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(activity.getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
        )
}
