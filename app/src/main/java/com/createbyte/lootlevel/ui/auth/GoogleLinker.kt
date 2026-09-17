package com.createbyte.lootlevel.ui.auth

import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.ui.dialogs.LoadingDialog
import com.createbyte.lootlevel.ui.main.MainActivity
import com.createbyte.lootlevel.utils.UserPreferences
import com.createbyte.lootlevel.utils.showAppDialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Turns a guest into a Google account, keeping everything they earned.
 *
 * LINKING, NOT SIGNING IN. linkWithCredential attaches Google to the guest's
 * existing Firebase user, so the uid - and with it every star, level, streak
 * and order - stays exactly where it is. Nothing is copied or migrated. The
 * server is then told (completeGoogleLink) so the user document stops calling
 * the account a guest; the ID token is refreshed first, because that call is
 * refused while the token still says "anonymous".
 *
 * WHEN THE GOOGLE ACCOUNT IS ALREADY A LOOTLEVEL ACCOUNT the link fails with a
 * collision. Merging two accounts is not offered - one account per device is
 * the rule - so the player chooses: log in to that account, which deletes the
 * guest and its progress, or stay a guest.
 *
 * Must be constructed while the activity is being created, because it
 * registers an activity-result launcher.
 */
class GoogleLinker(
    private val activity: AppCompatActivity,
    private val userRepository: () -> UserRepository,
    private val guestProgress: () -> Pair<Int, Int>
) {
    private val auth = FirebaseAuth.getInstance()
    private var loading: LoadingDialog? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val idToken = try {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java).idToken
        } catch (e: Exception) {
            // Chooser dismissed: the player is still a guest, nothing to say.
            Log.w(TAG, "Google account not picked: ${e.message}")
            null
        } ?: return@registerForActivityResult
        link(GoogleAuthProvider.getCredential(idToken, null))
    }

    fun start() {
        val client = GoogleAccount.client(activity)
        // Out of the Google client first, so the chooser always appears.
        client.signOut().addOnCompleteListener { launcher.launch(client.signInIntent) }
    }

    private fun link(credential: AuthCredential) {
        val user = auth.currentUser
        if (user == null || !user.isAnonymous) return

        showLoading()
        user.linkWithCredential(credential)
            .addOnSuccessListener {
                activity.lifecycleScope.launch {
                    try {
                        user.getIdToken(true).await()
                        val data = FirebaseFunctions.getInstance()
                            .getHttpsCallable("completeGoogleLink").call().await()
                            .data as? Map<*, *>
                        (data?.get("displayName") as? String)?.takeIf { it.isNotBlank() }?.let {
                            UserPreferences(activity.applicationContext).setUsername(it)
                        }
                        toast(R.string.guest_link_success)
                    } catch (e: Exception) {
                        // Linked in Auth regardless - redeem and the rest work
                        // from the token. Only the display flag lags, and the
                        // next attempt from Profile repairs it.
                        Log.e(TAG, "completeGoogleLink failed: ${e.message}")
                        toast(R.string.guest_link_success)
                    } finally {
                        hideLoading()
                    }
                }
            }
            .addOnFailureListener { e ->
                hideLoading()
                if (e is FirebaseAuthUserCollisionException) {
                    offerExistingAccount(e.updatedCredential ?: credential)
                } else {
                    Log.e(TAG, "Link failed: ${e.message}")
                    toast(R.string.guest_link_failed)
                }
            }
    }

    private fun offerExistingAccount(credential: AuthCredential) {
        val (level, stars) = guestProgress()
        activity.showAppDialog(
            title = activity.getString(R.string.guest_account_exists_title),
            message = activity.getString(R.string.guest_account_exists_message, level, stars),
            icon = R.drawable.ic_guest,
            accent = R.color.guest_warning,
            positiveText = activity.getString(R.string.guest_account_exists_switch),
            negativeText = activity.getString(R.string.cancel),
            onPositive = { switchToExisting(credential) }
        )
    }

    /**
     * Deletes the guest (server-side, with its data), then signs in to the
     * Google account and restarts the app on it.
     */
    private fun switchToExisting(credential: AuthCredential) {
        showLoading()
        activity.lifecycleScope.launch {
            val deleted = userRepository().deleteAccount()
            if (deleted !is UserRepository.DeleteAccountResult.Deleted) {
                hideLoading()
                toast(R.string.guest_link_failed)
                return@launch
            }
            try {
                auth.signOut()
                auth.signInWithCredential(credential).await()
                // Idempotent: returns the existing document untouched.
                FirebaseFunctions.getInstance().getHttpsCallable("completeSignup")
                    .call(hashMapOf("displayName" to "")).await()
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (e: Exception) {
                // The guest is already gone; the sign-in screen is the only
                // honest place left to go.
                Log.e(TAG, "Switching account failed: ${e.message}")
                auth.signOut()
                activity.startActivity(
                    Intent(activity, Auth::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } finally {
                hideLoading()
            }
        }
    }

    private fun showLoading() {
        if (loading != null || activity.supportFragmentManager.isStateSaved) return
        loading = LoadingDialog().also { it.show(activity.supportFragmentManager, TAG) }
    }

    private fun hideLoading() {
        loading?.takeIf { it.isAdded }?.dismissAllowingStateLoss()
        loading = null
    }

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "GoogleLinker"
    }
}
