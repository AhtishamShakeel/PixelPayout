package com.example.pixelpayout.utils

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.Button
import androidx.core.content.ContextCompat
import com.pixelpayout.R
import com.google.android.material.button.MaterialButton

/** The loader size the auth screens have always used, in raw pixels. */
private const val DEFAULT_LOADER_SIZE_PX = 85

/**
 * @param iconSizePx defaults to the full-width-button size the auth screens
 *   rely on. Inline pills - the streak and goal claim buttons - pass their own,
 *   because 85px is taller than the whole control.
 */
fun MaterialButton.startLoading(
    loadingText: String = "",
    iconSizePx: Int = DEFAULT_LOADER_SIZE_PX
) {
    this.isEnabled = false
    this.text = loadingText // Set to empty or a loading message
    this.icon = ContextCompat.getDrawable(this.context, R.drawable.progress_loader)
    this.iconSize = iconSizePx
    (this.icon as? AnimatedVectorDrawable)?.start()
}

fun MaterialButton.stopLoading(originalText: String) {
    this.isEnabled = true
    this.text = originalText
    this.icon = null
}
