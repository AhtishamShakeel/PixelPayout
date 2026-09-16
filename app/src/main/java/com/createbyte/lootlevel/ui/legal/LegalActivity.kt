package com.createbyte.lootlevel.ui.legal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.ActivityLegalBinding

/**
 * Shows the Terms of Service, the Privacy Policy, the account-deletion page or
 * the open-source licenses.
 *
 * The documents are the HTML files in public/legal - the exact files Firebase Hosting
 * serves for Google Play - bundled into the APK as assets by app/build.gradle.
 * One source for both, so the in-app text can never drift from the published
 * one. The "LootLevelApp" user-agent marker tells the page to drop its own
 * website header, since this screen draws the title and back button natively.
 *
 * Only bundled pages open in here. mailto: and web links go to the system,
 * so no remote content ever loads inside this WebView.
 */
class LegalActivity : AppCompatActivity() {

    enum class Doc(val file: String, val title: Int) {
        TERMS("terms.html", R.string.legal_terms_title),
        PRIVACY("privacy.html", R.string.legal_privacy_title),
        DELETE_ACCOUNT("delete-account.html", R.string.legal_delete_title),
        LICENSES("licenses.html", R.string.legal_licenses_title)
    }

    private lateinit var binding: ActivityLegalBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLegalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = ContextCompat.getColor(this, R.color.background_dark)

        val doc = intent.getStringExtra(EXTRA_DOC)
            ?.let { name -> Doc.values().firstOrNull { it.name == name } }
            ?: Doc.TERMS
        binding.legalTitle.setText(doc.title)
        binding.legalBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.legalWeb.apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
            // Needed only for legal-config.js, which fills in the operator's
            // name and contact. Every page is a local asset - see the client.
            settings.javaScriptEnabled = true
            settings.allowContentAccess = false
            settings.userAgentString = settings.userAgentString + " LootLevelApp"
            webViewClient = LegalClient()
            if (savedInstanceState == null) loadUrl(assetUrl(doc.file))
            else restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.legalWeb.canGoBack()) binding.legalWeb.goBack() else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.legalWeb.saveState(outState)
    }

    private inner class LegalClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val url = uri.toString()

            if (url.startsWith(ASSET_ROOT)) {
                // A link between the documents: stays in here, and the title
                // follows the page it lands on.
                val file = uri.lastPathSegment.orEmpty()
                Doc.values().firstOrNull { it.file == file }?.let { binding.legalTitle.setText(it.title) }
                return false
            }

            // Email, web links, anything else: hand to the system.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // No mail or browser app. Nothing sensible to open.
            }
            return true
        }
    }

    companion object {
        private const val EXTRA_DOC = "doc"
        private const val ASSET_ROOT = "file:///android_asset/"

        private fun assetUrl(file: String): String = ASSET_ROOT + file

        fun open(context: Context, doc: Doc) {
            context.startActivity(
                Intent(context, LegalActivity::class.java).putExtra(EXTRA_DOC, doc.name)
            )
        }
    }
}
