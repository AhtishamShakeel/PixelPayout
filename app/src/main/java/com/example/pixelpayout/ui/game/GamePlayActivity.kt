package com.example.pixelpayout.ui.game

import android.annotation.SuppressLint
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.config.AppConfig
import com.example.pixelpayout.utils.AdCadence
import com.example.pixelpayout.utils.AdHold
import com.example.pixelpayout.utils.AdManager
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.utils.showLevelUp
import com.example.pixelpayout.ui.main.MainActivity
import com.pixelpayout.R
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

    /**
     * Whether a rewarded ad played as part of finishing this run.
     *
     * Read on the way out, where it suppresses the interstitial entirely. A
     * player who took the double has already watched a full-screen ad for us
     * at this exact transition; following it with a second one would spend our
     * best impression to set up our worst, and would teach them that accepting
     * the offer costs them extra.
     */
    private var rewardedAdShown = false

    /** Stops a double-tap sending the offer twice while the ad opens. */
    private var doubleInFlight = false

    /** What the base claim paid, so the doubled total can be shown. */
    private var paidXp = 0

    /** One exit per run, however it is triggered. */
    private var leaving = false

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
        setupBackHandling()
    }

    /**
     * Sends the back gesture through [leave] once the run is over.
     *
     * Without this the gesture is a second, quieter exit that skips the
     * cadence entirely - so a player who habitually swipes back would never be
     * counted, and the interval would mean something different for them than
     * for everyone else. Before the results panel is up, back behaves
     * normally: there is a game running and nothing has been finished.
     */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.resultsPanel.visibility == View.VISIBLE) {
                    // Not while an ad is being fetched - see watchAdToDouble.
                    if (!doubleInFlight) leave()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
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
                is GamePlayViewModel.ClaimOutcome.Paid -> showResults(outcome.xpAwarded)
                is GamePlayViewModel.ClaimOutcome.Refused -> showClaimRefused(outcome.reason)
            }
        }

        viewModel.doubleOutcome.observe(this) { outcome ->
            // The offer is gone in every branch: the ad is spent, and a
            // session cannot be doubled twice whether or not the call landed.
            binding.resultsContent.apply {
                doubleXpButton.visibility = View.GONE
                resultsContinueButton.isEnabled = true
                doubleXpStatus.visibility = View.VISIBLE
                doubleXpStatus.text = when (outcome) {
                    is GamePlayViewModel.DoubleOutcome.Paid -> {
                        resultsXpText.text = getString(
                            R.string.results_xp_earned,
                            paidXp + outcome.xpAwarded
                        )
                        getString(R.string.double_xp_done, outcome.xpAwarded)
                    }
                    is GamePlayViewModel.DoubleOutcome.AlreadyPaid ->
                        getString(R.string.double_xp_already)
                    // Saying the earned XP is safe is the point of this
                    // message. It is - the base claim landed before the offer
                    // was ever shown - and nothing else on screen says so.
                    is GamePlayViewModel.DoubleOutcome.Failed ->
                        getString(R.string.double_xp_failed)
                }
            }
            doubleInFlight = false
        }
    }

    /**
     * The run is over and already paid. Now the offer.
     *
     * RESULT_OK is set here rather than on the way out, so the games list
     * refreshes its allowance even if the player leaves by the back gesture
     * rather than the button.
     */
    private fun showResults(xpAwarded: Int) {
        paidXp = xpAwarded
        setResult(RESULT_OK)

        binding.loadingIndicator.visibility = View.GONE

        binding.resultsContent.apply {
            // Set here rather than in the layout: the card is shared with the
            // quiz results dialog, and only this label differs between them.
            resultsTitle.setText(R.string.game_results_title)
            resultsXpText.text = getString(R.string.results_xp_earned, xpAwarded)
            // A run worth no XP has nothing to double - the server refuses one
            // anyway, and offering an ad in exchange for twice nothing is
            // worse than making no offer at all.
            doubleXpButton.visibility = if (viewModel.canDouble()) View.VISIBLE else View.GONE
            doubleXpButton.setOnClickListener { watchAdToDouble() }
            resultsContinueButton.setOnClickListener { leave() }
        }

        binding.resultsScrim.visibility = View.VISIBLE
        binding.resultsPanel.visibility = View.VISIBLE
    }

    /**
     * Watches a rewarded ad, then doubles.
     *
     * The claim fires from the REWARD callback rather than from dismissal:
     * both arrive on a normal completion, but the reward comes first, which
     * shrinks the window in which a killed process loses an ad the player
     * actually sat through. Deliberately the same shape as the bonus-attempt
     * purchase on the games list - they are the same bargain.
     */
    private fun watchAdToDouble() {
        if (doubleInFlight) return
        doubleInFlight = true

        binding.resultsContent.apply {
            doubleXpButton.isEnabled = false
            // Disabled too: leaving mid-ad would strand a claim against an
            // activity that is finishing.
            resultsContinueButton.isEnabled = false
            doubleXpStatus.visibility = View.VISIBLE
            // "Finding an ad" first, because showRewardedAdWhenReady waits a
            // few seconds for one rather than refusing the tap outright - the
            // label has to describe that wait before it can honestly claim to
            // be doubling anything.
            doubleXpStatus.setText(R.string.double_xp_finding)
        }

        var rewarded = false
        AdManager.getInstance().showRewardedAdWhenReady(
            activity = this,
            onRewarded = {
                if (!rewarded) {
                    rewarded = true
                    rewardedAdShown = true
                    binding.resultsContent.doubleXpStatus.setText(R.string.double_xp_claiming)
                    viewModel.claimDoubleXp()
                }
            },
            // Dismissal without a reward means the ad was closed early. The
            // offer stands - nothing was spent, so the button comes back.
            onAdClosed = { if (!rewarded) restoreOffer(null) },
            onAdFailedToShow = {
                if (!rewarded) restoreOffer(R.string.double_xp_unavailable)
            }
        )
    }

    /** Puts the offer back after an ad that never paid out. */
    private fun restoreOffer(messageRes: Int?) {
        doubleInFlight = false
        binding.resultsContent.apply {
            doubleXpButton.isEnabled = true
            resultsContinueButton.isEnabled = true
            if (messageRes == null) {
                doubleXpStatus.visibility = View.GONE
            } else {
                doubleXpStatus.visibility = View.VISIBLE
                doubleXpStatus.setText(messageRes)
            }
        }
    }

    /**
     * Leaves the game, showing an interstitial if one is due.
     *
     * Every exit from a finished run goes through here - the button and the
     * back gesture both - so the cadence sees exactly one completion per run
     * however the player leaves, and cannot be walked around by using the
     * gesture instead.
     *
     * The ad lands at the TRANSITION rather than over the results panel: the
     * player has read their XP and asked to move on, which is the moment an
     * interruption costs least. [AdHold] then puts a brief pause between that
     * tap and the ad, so the tap cannot carry through onto the ad and a
     * request has a last chance to fill. It always calls back, with an ad or
     * without one, so this cannot strand anybody on a finished screen.
     */
    private fun leave() {
        if (leaving) return
        leaving = true

        if (AdCadence.onActivityCompleted(this, rewardedAdShown)) {
            AdHold.showInterstitialThen(this) { finish() }
        } else {
            finish()
        }
    }

    /**
     * A finished run that could not be paid.
     *
     * The game is torn down rather than left running behind the message: the
     * session is spent, so there is nothing a second attempt at the same run
     * could earn, and leaving a playable board on screen would invite one.
     * RESULT_OK is deliberately not set - nothing was awarded.
     *
     * No interstitial on this path either. The player has just been told their
     * run earned nothing; an advert on top of that is the worst possible
     * moment for one, and it is not a completion the cadence should count.
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
