package com.example.pixelpayout.data.api

data class RedeemOption(
    val title: String = "",
    val requiredStars: Int = 0,
    val imageUrl: String = "",
    val active: Boolean = true,
    val type: String = "",
    val tag: String = ""
)