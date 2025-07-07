package com.example.pixelpayout.data.api

data class RedeemOption(
    val title: String = "",
    val requiredStars: Int = 0,
    val imageUrl: String = "",
    val active: Boolean = true,
    val type: String = "",
    val rewardId: String = "",
    val tag: String = "",
    val inputLabel: String = "Game Id",
    val inputExample: String = "613738123"
)