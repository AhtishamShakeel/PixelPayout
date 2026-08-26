package com.example.pixelpayout.utils

import android.content.Context
import android.widget.Toast
import com.example.pixelpayout.data.model.LevelUpEvent
import com.pixelpayout.R

/**
 * Announces a level-up the same way regardless of which activity earned it,
 * so a milestone bonus never goes unmentioned just because it came from a
 * game rather than a quiz.
 */
fun Context.showLevelUp(event: LevelUpEvent) {
    val message = if (event.milestonePoints > 0) {
        getString(R.string.level_up_with_bonus, event.level, event.milestonePoints)
    } else {
        getString(R.string.level_up, event.level)
    }
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
