package com.pixelpayout.ui.game

import android.webkit.JavascriptInterface

class GameJavaScriptInterface(
    private val viewModel: GamePlayViewModel
) {
    @JavascriptInterface
    fun onGameComplete(points: Int) {
        viewModel.updateGamePoints(points)
    }
} 