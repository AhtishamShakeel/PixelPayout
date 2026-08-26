package com.example.pixelpayout.ui.game

import android.webkit.JavascriptInterface

class GameJavaScriptInterface(
    private val viewModel: GamePlayViewModel,
    private val gameId: String
) {
    @JavascriptInterface
    fun onGameComplete(points: Int) {
        viewModel.claimGameReward(gameId)
    }
}
