package com.example.pixelpayout.utils

import android.content.Context
import android.widget.Toast
import com.example.pixelpayout.data.model.LevelUpEvent
import com.pixelpayout.R

/**
 * Announces a level-up the same way regardless of which activity earned it,
 * so a milestone bonus never goes unmentioned just because it came from a
 * game rather than a quiz.
 *
 * [LevelUpEvent.milestonePoints] is now what was LOCKED, not what was paid -
 * level bonuses are released by a rewarded ad on the Level rewards screen -
 * so the wording says the stars are waiting and where to collect them. It
 * used to read "Bonus: 8 stars", which after the change would have been a
 * receipt for a payment that had not happened.
 */
fun Context.showLevelUp(event: LevelUpEvent) {
    val message = if (event.milestonePoints > 0) {
        getString(R.string.level_up_with_bonus, event.level, event.milestonePoints)
    } else {
        getString(R.string.level_up, event.level)
    }
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
