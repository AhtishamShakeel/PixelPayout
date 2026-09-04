package com.example.pixelpayout.ui.rewards

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import android.net.http.SslError
import com.pixelpayout.R
import com.pixelpayout.databinding.ActivityOfferwallBinding

/**
 * Hosts one offerwall's hosted web page.
 *
 * Every network in the accessible tier serves its wall as a web page rather
 * than an SDK, so this one activity is the whole client integration for all
 * of them. It takes a URL and shows it; which URL comes from
 * `config/offerwallWalls`, which is why adding a network needs no release.
 *
 * NO JAVASCRIPT INTERFACE, deliberately. GamePlayActivity has one because a
 * hosted game has to report a score; a wall has nothing to tell the client.
 * Completions arrive at `offerwallCallback` from the network's own servers,
 * signed, and are credited there. That separation is the reason a user
 * cannot pay themselves by editing a page: the surface that would let them
 * simply does not exist here.
 */
class OfferwallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfferwallBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfferwallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // A wall reached with no URL is a programming error, not a user
        // error, and there is nothing to show - so leave rather than
        // presenting an empty white page the user has to work out.
        if (url.isNullOrBlank()) {
            Log.e(TAG, "Opened with no URL")
            finish()
            return
        }

        binding.offerwallTitle.text = title
        binding.offerwallClose.setOnClickListener { finish() }

        setupWebView()
        binding.offerwallWeb.loadUrl(url)

        // Back walks the wall's own history first. Offer pages are several
        // levels deep - category, offer, terms - and closing the whole wall
        // on the first back press would lose the user's place every time.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.offerwallWeb.canGoBack()) {
                    binding.offerwallWeb.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() = with(binding.offerwallWeb.settings) {
        // Every offerwall is a JavaScript application; without this the page
        // renders as an empty shell.
        javaScriptEnabled = true
        domStorageEnabled = true
        // Walls track a session across their own pages, and several will not
        // credit a completion without it.
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        // Some walls embed video creatives; left on AUTOPLAY-required
        // defaults they simply never start.
        mediaPlaybackRequiresUserGesture = false
        // The wall is a third-party page rendered inside our app. Nothing it
        // loads has any business touching the filesystem.
        allowFileAccess = false
        allowContentAccess = false
        cacheMode = WebSettings.LOAD_DEFAULT

        // THIRD-PARTY COOKIES, ON.
        //
        // Not a detail. An offerwall is a page from network A that frames and
        // redirects through advertiser B, and the session that ties a click
        // to a completion lives in a cookie set on the other domain. Android
        // WebView blocks those by default, and the resulting failure is the
        // worst kind: the wall renders perfectly, offers open normally, and
        // completions silently never attribute - so users do the work and
        // are never paid, and nothing anywhere reports an error.
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(binding.offerwallWeb, true)

        binding.offerwallWeb.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.offerwallProgress.progress = newProgress
                binding.offerwallProgress.isVisible = newProgress < 100
            }
        }

        binding.offerwallWeb.webViewClient = object : WebViewClient() {
            /**
             * Offer tiles overwhelmingly point OUT of the wall - to Play, to
             * a survey host, to an advertiser's site - and a WebView refuses
             * every scheme it does not know. Without this, tapping an offer
             * does nothing at all and the wall reads as broken, which is the
             * single most common way a web offerwall is mis-integrated.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url ?: return false
                val scheme = target.scheme?.lowercase()

                if (scheme == "http" || scheme == "https") {
                    // Play links carry the install attribution the whole
                    // completion depends on, so they must reach the Play app
                    // rather than render as a web page inside the wall.
                    return if (target.host?.endsWith("play.google.com") == true) {
                        openExternally(target)
                    } else {
                        false
                    }
                }

                // market://, intent://, tel:, mailto: and every store deep
                // link a network might use.
                return openExternally(target)
            }

            /**
             * Cancel, never proceed.
             *
             * GamePlayActivity currently calls proceed() here, which is a
             * known bug on our own hosted games. On a third-party wall the
             * same choice would be far worse: this page carries the user's
             * id and the network's session, and honouring a bad certificate
             * would hand both to whoever presented it.
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                Log.e(TAG, "SSL error on offerwall, cancelling: ${error?.primaryError}")
                handler?.cancel()
            }
        }
    }

    /**
     * Hands a URI to the system, and reports honestly if nothing can open it.
     *
     * Returns true either way: the WebView must not fall back to loading a
     * market:// or intent:// URL itself, which renders as an error page
     * inside the wall and looks like the wall broke.
     */
    private fun openExternally(uri: Uri): Boolean {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Nothing can open $uri: ${e.message}")
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        binding.offerwallWeb.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.offerwallWeb.onResume()
    }

    override fun onDestroy() {
        // Detached before destroy: a WebView left in the hierarchy is a
        // well-known activity leak, and this one is opened repeatedly.
        (binding.offerwallWeb.parent as? android.view.ViewGroup)
            ?.removeView(binding.offerwallWeb)
        binding.offerwallWeb.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Offerwall"
        const val EXTRA_URL = "offerwall_url"
        const val EXTRA_TITLE = "offerwall_title"

        fun intent(context: android.content.Context, url: String, title: String) =
            Intent(context, OfferwallActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
    }
}
