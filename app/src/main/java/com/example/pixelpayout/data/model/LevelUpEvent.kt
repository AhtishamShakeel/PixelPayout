package com.example.pixelpayout.data.model

/**
 * A level-up worth showing the user, plus any one-time milestone Points bonus
 * it unlocked. Shared by every activity that can trigger an award, so the
 * level-up moment reads the same wherever XP was earned.
 */
data class LevelUpEvent(
    val level: Int,
    val milestonePoints: Int
)
