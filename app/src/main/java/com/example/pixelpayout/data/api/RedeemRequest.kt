package com.example.pixelpayout.data.api

data class RedeemRequest (
    val redemptionId: String = "",
    val userId: String = "",
    val rewardId: String = "",
    val rewardSnapshot: RewardSnapshot = RewardSnapshot(),
    val userInput: String = "",
    val status: String = "pending",
    val timestamp: Long = System.currentTimeMillis(),
    val processedAt: Long? = null,
    val adminNotes: String = "",
    val version: Int = 1
)

data class RewardSnapshot(
    val title: String = "",
    val type: String = "",
    val requiredStars: Int = 0,
    val imageUrl: String = "",
    val inputLabel: String = "",
    val inputExample: String = "",
)