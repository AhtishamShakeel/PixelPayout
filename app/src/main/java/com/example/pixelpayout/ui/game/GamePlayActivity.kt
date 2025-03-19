package com.example.pixelpayout.ui.game

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.pixelpayout.utils.AndroidConnectivityCheck
import com.example.pixelpayout.ui.main.MainActivity
import com.pixelpayout.databinding.ActivityGamePlayBinding
import kotlinx.coroutines.launch

class GamePlayActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGamePlayBinding
    private val viewModel: GamePlayViewModel by viewModels()
    private lateinit var connectivityCheck: AndroidConnectivityCheck

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityCheck = AndroidConnectivityCheck(this)
        setupConnectivityCheck()

        val gameUrl = intent.getStringExtra("GAME_URL") ?: ""


        if (gameUrl.startsWith("http")) {
            setupWebView(gameUrl)
        } else {
            showPlaceholder()
        }
        observeViewModel()
    }

    private fun showPlaceholder() {
        binding.apply {
            gameWebView.visibility = View.GONE
            loadingIndicator.visibility = View.GONE
            placeholderText.visibility = View.VISIBLE
        }
    }

    private fun setupWebView(gameUrl: String) {
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
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }

                addJavascriptInterface(
                    GameJavaScriptInterface(viewModel),
                    "AndroidInterface"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        loadingIndicator.visibility = View.GONE
                        evaluateJavascript("""
                        javascript:(function() {
                            var style = document.createElement('style');
                            style.type = 'text/css';
                            style.innerHTML = 'body { max-width: 100vw; overflow-x: hidden; }';
                            document.head.appendChild(style);
                        })()
                    """.trimIndent(), null)
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed()
                    }
                }

                requestFocus()
                loadUrl(gameUrl) // Load the correct game URL
            }
        }
    }

    private fun observeViewModel() {
        viewModel.pointsUpdated.observe(this) { success ->
            if (success) {
                // Set result to notify MainActivity that points were updated
                setResult(RESULT_OK)
                finish()
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

    companion object {
        private const val GAME_URL_1 = "https://game-ccdff.web.app/" // Updated to your 2048 game URL
        private const val GAME_URL_2 = "https://floppybird-bc843.web.app/"
    }
} 