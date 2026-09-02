package com.example.pixelpayout.data.model

/**
 * A level-up worth showing the user, plus any one-time milestone Points bonus
 * it EARNED. Shared by every activity that can trigger an award, so the
 * level-up moment reads the same wherever XP was earned.
 *
 * [milestonePoints] is locked, not paid: reaching the level promises the
 * stars and a rewarded ad on the Level rewards screen releases them. The
 * announcement wording says so - see showLevelUp.
 */
data class LevelUpEvent(
    val level: Int,
    val milestonePoints: Int
)
