package com.example.pixelpayout.ui.game

import android.app.Activity
import android.webkit.JavascriptInterface

class GameJavaScriptInterface(
    private val activity: Activity,
    private val viewModel: GamePlayViewModel
) {
    @JavascriptInterface
    fun onGameComplete(points: Int) {
        viewModel.updateGamePoints(points)

        // Immediately close the activity on the main thread
        activity.runOnUiThread {
            activity.finish()
        }
    }
}