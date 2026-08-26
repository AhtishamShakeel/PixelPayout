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
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    
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
                        
                        // Different CSS for different games
                        val cssStyle = if (gameUrl.contains("floppybird")) {
                            """
                                body { 
                                    margin: 0; 
                                    padding: 0; 
                                    width: 100vw; 
                                    height: 100vh; 
                                    overflow: hidden; 
                                } 
                                canvas { 
                                    width: 100% !important; 
                                    height: 100% !important; 
                                }
                            """
                        } else {
                            """
                                body { 
                                    margin: 0 auto; 
                                    padding: 0; 
                                    width: 100vw; 
                                    height: 100vh; 
                                    display: flex; 
                                    justify-content: center; 
                                    align-items: center; 
                                } 
                                .container { 
                                    width: 90vmin !important; 
                                    height: 90vmin !important; 
                                    max-width: 500px; 
                                    max-height: 500px; 
                                }
                            """
                        }
                        
                        evaluateJavascript("""
                            javascript:(function() {
                                var style = document.createElement('style');
                                style.type = 'text/css';
                                style.innerHTML = `${cssStyle}`;
                                document.head.appendChild(style);
                            })()
                        """.trimIndent(), null)
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
        viewModel.pointsUpdated.observe(this) { success ->
            if (success) {
                setResult(RESULT_OK)
                finish()
            } else {
                binding.loadingIndicator.visibility = View.VISIBLE
            }
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

    private fun getGameId(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return null

        return ALLOWED_GAME_HOSTS[uri.host]
    }

    companion object {
        private val ALLOWED_GAME_HOSTS = mapOf(
            "game-ccdff.web.app" to "game_2048",
            "floppybird-bc843.web.app" to "floppy_bird"
        )
    }

} 
