package com.pixelpayout.ui.game

import android.annotation.SuppressLint
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
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true

                // Add JavaScript interface for game-app communication
                addJavascriptInterface(
                    GameJavaScriptInterface(viewModel),
                    "AndroidInterface"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        loadingIndicator.visibility = View.GONE
                    }
                }

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
        private const val GAME_URL = "NO_GAME" // Change this to your game URL when ready
    }
} 