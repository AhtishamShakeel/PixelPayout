package com.pixelpayout.ui.game

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.pixelpayout.databinding.ActivityGamePlayBinding

class GamePlayActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGamePlayBinding
    private val viewModel: GamePlayViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (GAME_URL.startsWith("http")) {
            setupWebView()
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

    private fun setupWebView() {
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
                    // Force initial scale to fit screen
                    setSupportZoom(false)
                    builtInZoomControls = false
                    cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                }

                // Add JavaScript interface for game-app communication
                addJavascriptInterface(
                    GameJavaScriptInterface(viewModel),
                    "AndroidInterface"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        loadingIndicator.visibility = View.GONE
                        // Inject CSS to force portrait layout
                        evaluateJavascript("""
                            javascript:(function() {
                                var style = document.createElement('style');
                                style.type = 'text/css';
                                style.innerHTML = 'body { max-width: 100vw; overflow-x: hidden; }';
                                document.head.appendChild(style);
                            })()
                        """.trimIndent(), null)
                    }

                    // Handle potential SSL certificate issues
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed() // Proceed with SSL certificate issues
                    }
                }

                // Request focus for keyboard input
                requestFocus()

                // Load the game URL
                loadUrl(GAME_URL)
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

    companion object {
        private const val GAME_URL = "https://game-ccdff.web.app/" // Updated to your 2048 game URL
    }
} 