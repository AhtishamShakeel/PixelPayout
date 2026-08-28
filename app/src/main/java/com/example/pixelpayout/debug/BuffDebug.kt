package com.example.pixelpayout.debug

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * Debug-only trigger for the Points buff, so the boost card can be exercised
 * before any real source grants one.
 *
 * This is not a back door. grantPointsBuff is admin-only server-side and stays
 * that way - its own docstring says it exists "for support and for testing the
 * mechanism before those sources exist", which is exactly this. Nothing here
 * weakens that check: a non-admin account gets permission-denied and is told
 * so. The whole file is reachable only from a button gated on
 * BuildConfig.DEBUG.
 *
 * To remove: delete this file, the debugBoostButton block in fragment_home.xml
 * and its wiring in HomeFragment. The buildConfig flag can stay.
 */
object BuffDebug {

    /** 2x is the mid value; the server caps at MAX_BUFF_MULTIPLIER (3). */
    private const val MULTIPLIER = 2.0

    /** Long enough to watch the countdown, short enough not to linger. */
    private const val DURATION_MINUTES = 10L

    /**
     * Grants the signed-in user a buff, returning a line fit to show in a
     * toast. Never throws - every failure comes back as text, because the
     * only caller is a debug button and a crash there tells us less than a
     * message does.
     */
    suspend fun grantSelfBuff(): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return "Not signed in"
        val functions = FirebaseFunctions.getInstance()

        // bootstrapAdmin is idempotent and refuses anyone not on the server's
        // ADMIN_BOOTSTRAP_EMAILS list, so calling it unconditionally is safe.
        try {
            functions.getHttpsCallable("bootstrapAdmin").call().await()
            // A custom claim only reaches the client on the next token
            // refresh, and grantPointsBuff reads admin off the token. Without
            // forcing one here the first grant after bootstrap always fails.
            user.getIdToken(true).await()
        } catch (e: Exception) {
            // Already admin, or not eligible. Either way the grant below is
            // what decides, and it reports the real reason.
        }

        return try {
            val result = functions.getHttpsCallable("grantPointsBuff").call(
                mapOf(
                    "uid" to user.uid,
                    "multiplier" to MULTIPLIER,
                    "durationMs" to DURATION_MINUTES * 60_000L
                )
            ).await()

            val data = result.data as? Map<*, *>
            if (data?.get("applied") == true) {
                "Boost on: ${MULTIPLIER}x for $DURATION_MINUTES min"
            } else {
                // resolveBuffGrant ignores a weaker grant while a stronger
                // buff runs, and never stacks multiplicatively.
                "A stronger boost is already running"
            }
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "Admin only. Add this account's email to " +
                        "ADMIN_BOOTSTRAP_EMAILS and verify it."
                else -> "Grant failed: ${e.message}"
            }
        } catch (e: Exception) {
            "Grant failed: ${e.message}"
        }
    }
}
