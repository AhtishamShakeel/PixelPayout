package com.createbyte.lootlevel.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.ActivityAuthBinding
import com.createbyte.lootlevel.databinding.SheetGuestConfirmBinding
import com.createbyte.lootlevel.ui.legal.LegalActivity
import com.createbyte.lootlevel.ui.main.MainActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Sign-in: Google, or play as a guest.
 *
 * A guest is a Firebase anonymous account - a real uid with a real user
 * document, so everything that does not leave the app works for it. Redeeming,
 * referral codes, the tournament and offerwalls ask the player to link Google
 * first (see GuestAccount), and the server enforces the same line.
 */
class Auth : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private val viewModel: AuthViewModel by viewModels()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(com.google.android.gms.common.api.ApiException::class.java)
            val idToken = account.idToken ?: error("No ID token")
            viewModel.signInWithGoogle(idToken, account.displayName.orEmpty(), androidId(), this)
        } catch (e: Exception) {
            // Backing out of the account chooser lands here too; nothing to say.
            Log.w(TAG, "Google sign-in did not complete: ${e.message}")
            hideLoading()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        googleSignInClient = GoogleAccount.client(this)
        // Signed out of the Google client first, so the account chooser always
        // appears instead of silently reusing the last account.
        googleSignInClient.signOut()

        // Decoration: where a brand-new account starts, most of the way round.
        binding.authLevelRing.setProgressCompat(74, false)

        binding.btnGoogle.setOnClickListener { signInWithGoogle() }
        binding.btnGuest.setOnClickListener { showGuestSheet() }
        setupTermsText()

        viewModel.state.observe(this) { state ->
            when (state) {
                AuthViewModel.State.Idle -> hideLoading()
                AuthViewModel.State.Loading -> showLoading()
                AuthViewModel.State.Success -> navigateToMain()
                is AuthViewModel.State.Error -> {
                    hideLoading()
                    Toast.makeText(this, state.messageRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun signInWithGoogle() {
        showLoading()
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    /** "Play as a guest?" - the risks, with Google offered once more. */
    private fun showGuestSheet() {
        val sheet = BottomSheetDialog(this, R.style.Theme_LootLevel_BottomSheet)
        val sheetBinding = SheetGuestConfirmBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.guestUseGoogle.setOnClickListener {
            sheet.dismiss()
            signInWithGoogle()
        }
        sheetBinding.guestContinue.setOnClickListener {
            sheet.dismiss()
            viewModel.signInAsGuest(androidId(), this)
        }
        // Opened fully. A bottom sheet starts at its peek height, which cut
        // off "Continue as guest" below the fold on shorter phones.
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun setupTermsText() {
        val full = getString(R.string.auth_terms)
        val spannable = SpannableString(full)
        fun link(word: String, doc: LegalActivity.Doc) {
            val start = full.indexOf(word)
            if (start < 0) return
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = LegalActivity.open(this@Auth, doc)
            }, start, start + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        link(getString(R.string.auth_terms_link), LegalActivity.Doc.TERMS)
        link(getString(R.string.auth_privacy_link), LegalActivity.Doc.PRIVACY)
        binding.authTerms.text = spannable
        binding.authTerms.movementMethod = LinkMovementMethod.getInstance()
    }

    @SuppressLint("HardwareIds")
    private fun androidId(): String = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }

    private fun showLoading() {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.lottieLoading.visibility = View.VISIBLE
        binding.lottieLoading.playAnimation()
    }

    private fun hideLoading() {
        binding.lottieLoading.cancelAnimation()
        binding.lottieLoading.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
    }

    private companion object {
        const val TAG = "Auth"
    }
}
