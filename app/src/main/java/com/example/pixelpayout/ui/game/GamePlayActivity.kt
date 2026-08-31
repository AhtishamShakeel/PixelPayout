package com.example.pixelpayout.ui.game

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.config.AppConfig
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.utils.showLevelUp
import com.example.pixelpayout.ui.main.MainActivity
import com.pixelpayout.databinding.ActivityGamePlayBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.launch

class GamePlayActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGamePlayBinding
    private val viewModel: GamePlayViewModel by viewModels()
    private lateinit var connectivityCheck: AndroidConnectivityCheck
    private lateinit var adView: AdView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityCheck = AndroidConnectivityCheck(this)
        setupConnectivityCheck()
        setupBannerAd()

        val gameUrl = intent.getStringExtra("GAME_URL") ?: ""

        val gameId = getGameId(gameUrl)

        if (gameId != null) {
            viewModel.startSession(gameId)
            setupWebView(gameUrl, gameId)
        } else {
            showPlaceholder()
        }
        observeViewModel()
    }

    private fun setupBannerAd() {
        adView = AdView(this)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = AppConfig.ADMOB_GAME_BANNER_AD_UNIT_ID
        binding.adContainer.addView(adView)
        
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)
    }

    private fun showPlaceholder() {
        binding.apply {
            gameWebView.visibility = View.GONE
            loadingIndicator.visibility = View.GONE
            placeholderText.visibility = View.VISIBLE
        }
    }

    private fun setupWebView(gameUrl: String, gameId: String) {
        binding.apply {
            gameWebView.visibility = View.VISIBLE
            placeholderText.visibility = View.GONE

            gameWebView.apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    // LOAD_DEFAULT, not LOAD_CACHE_ELSE_NETWORK: the latter
                    // serves a cached copy however stale it is, which would
                    // pin players to whatever build of a game they first
                    // loaded and quietly defeat the no-cache headers on
                    // /games/**/*.{html,js}. The games are ours to iterate on
                    // now, so cache freshness has to be the host's call.
                    cacheMode = WebSettings.LOAD_DEFAULT
                    
                    // Enable hardware acceleration
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }

                // Pass Activity instance along with ViewModel
                addJavascriptInterface(GameJavaScriptInterface(viewModel, gameId), "AndroidInterface")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val requestedUrl = request?.url?.toString() ?: return true
                        return if (isAllowedGameUrl(requestedUrl)) {
                            false
                        } else {
                            showPlaceholder()
                            true
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        loadingIndicator.visibility = View.GONE

                        // No CSS is injected here any more. The two externally
                        // hosted games this used to reshape are gone; the games
                        // we host ourselves size themselves to the viewport, and
                        // the old fallback rule (a centred 90vmin .container)
                        // would letterbox them.
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        showPlaceholder()
                    }
                }

                requestFocus()
                loadUrl(gameUrl)
            }
        }
    }

    override fun onPause() {
        adView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adView.resume()
    }

    override fun onDestroy() {
        adView.destroy()
        super.onDestroy()
    }

    private fun observeViewModel() {
        // Emitted before pointsUpdated, so the toast is queued before this
        // activity finishes below (a Toast outlives the activity that showed it).
        viewModel.levelUp.observe(this) { event ->
            event?.let { showLevelUp(it) }
        }

        viewModel.claimOutcome.observe(this) { outcome ->
            when (outcome) {
                is GamePlayViewModel.ClaimOutcome.Paid -> {
                    setResult(RESULT_OK)
                    finish()
                }
                is GamePlayViewModel.ClaimOutcome.Refused -> showClaimRefused(outcome.reason)
            }
        }
    }

    /**
     * A finished run that could not be paid.
     *
     * The game is torn down rather than left running behind the message: the
     * session is spent, so there is nothing a second attempt at the same run
     * could earn, and leaving a playable board on screen would invite one.
     * RESULT_OK is deliberately not set - nothing was awarded.
     */
    private fun showClaimRefused(reasonRes: Int) {
        binding.apply {
            loadingIndicator.visibility = View.GONE
            gameWebView.visibility = View.GONE
            placeholderText.visibility = View.GONE
            claimErrorText.setText(reasonRes)
            claimErrorPanel.visibility = View.VISIBLE
            claimErrorAction.setOnClickListener { finish() }
        }
    }

    private fun setupConnectivityCheck() {
        lifecycleScope.launch {
            connectivityCheck.isConnected.collect { isConnected ->
                if (!isConnected && !isFinishing) {
                    MainActivity.handleInternetDisconnection(this@GamePlayActivity)
                }
            }
        }
    }

    private fun isAllowedGameUrl(url: String): Boolean {
        return getGameId(url) != null
    }

    /**
     * The gameId a URL is allowed to claim against, or null if it is not one
     * of our games. Both games are served from the app's own Firebase Hosting
     * site now, so - unlike the one-project-per-game setup this replaces - the
     * host no longer identifies the game and the slug under /games/ does.
     */
    private fun getGameId(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return null
        if (uri.host !in ALLOWED_GAME_HOSTS) return null

        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != GAMES_PATH_PREFIX) return null

        return GAME_IDS_BY_SLUG[segments[1]]
    }

    companion object {
        /** The site the games are deployed to by `firebase deploy --only hosting`. */
        private const val GAMES_HOST = "pixelpayout-check.web.app"

        /** Firebase provisions both domains for a site; accept either. */
        private val ALLOWED_GAME_HOSTS = setOf(
            GAMES_HOST,
            "pixelpayout-check.firebaseapp.com"
        )

        private const val GAMES_PATH_PREFIX = "games"

        const val SLUG_FLAPPY = "flappy"
        const val SLUG_TOWER = "tower"

        /**
         * Slug -> the gameId the economy knows it by. `flappy` keeps the
         * `floppy_bird` id its predecessor used: the score scale is the same
         * (one point per pipe), so every existing session, XP divisor and
         * plausibility rule carries over untouched.
         */
        private val GAME_IDS_BY_SLUG = mapOf(
            SLUG_FLAPPY to "floppy_bird",
            SLUG_TOWER to "tower_game"
        )

        /** Single place the game URLs are built, so callers can't drift. */
        fun gameUrl(slug: String): String =
            "https://$GAMES_HOST/$GAMES_PATH_PREFIX/$slug/"
    }

} 
