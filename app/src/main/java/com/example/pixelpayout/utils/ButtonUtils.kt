package com.example.pixelpayout.utils

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.Button
import androidx.core.content.ContextCompat
import com.pixelpayout.R
import com.google.android.material.button.MaterialButton

fun MaterialButton.startLoading(loadingText: String = "") {
    this.isEnabled = false
    this.text = loadingText // Set to empty or a loading message
    this.icon = ContextCompat.getDrawable(this.context, R.drawable.progress_loader)
    this.iconSize = 85
    (this.icon as? AnimatedVectorDrawable)?.start()
}

fun MaterialButton.stopLoading(originalText: String) {
    this.isEnabled = true
    this.text = originalText
    this.icon = null
}
