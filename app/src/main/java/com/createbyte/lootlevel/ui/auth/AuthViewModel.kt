package com.createbyte.lootlevel.ui.auth

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.utils.UserPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Signs in with Google or as a guest, then has the server create the user
 * document (completeSignup, idempotent, so a returning Google user is fine).
 */
class AuthViewModel : ViewModel() {

    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data object Success : State()
        data class Error(val messageRes: Int) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private var busy = false

    fun signInWithGoogle(idToken: String, displayName: String, androidId: String, context: Context) {
        run(context, displayName.ifBlank { "User" }) {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            completeSignup(displayName, androidId)
        }
    }

    /**
     * A new anonymous account. If the server cannot create its document the
     * account is deleted again, rather than left as a signed-in uid with no
     * user document behind it.
     */
    fun signInAsGuest(androidId: String, context: Context) {
        run(context, GUEST_NAME) {
            auth.signInAnonymously().await()
            try {
                completeSignup("", androidId)
            } catch (e: Exception) {
                auth.currentUser?.takeIf { it.isAnonymous }?.delete()
                throw e
            }
        }
    }

    private fun run(context: Context, username: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        _state.value = State.Loading

        val preferences = UserPreferences(context.applicationContext)
        viewModelScope.launch {
            try {
                withTimeout(TIMEOUT_MS) { block() }
                preferences.setUsername(username)
                preferences.setHasSeenReferralPopup(false)
                _state.value = State.Success
            } catch (e: Exception) {
                Log.e(TAG, "Sign-in failed: ${e.message}")
                if (auth.currentUser?.isAnonymous == false) auth.signOut()
                _state.value = State.Error(R.string.auth_failed)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun completeSignup(displayName: String, androidId: String) {
        functions.getHttpsCallable("completeSignup")
            .call(
                hashMapOf(
                    "displayName" to displayName,
                    "androidId" to androidId.ifEmpty { "UNKNOWN_ANDROID_ID" }
                )
            )
            .await()
    }

    private companion object {
        const val TAG = "AuthViewModel"
        const val TIMEOUT_MS = 20_000L
        const val GUEST_NAME = "Guest"
    }
}
