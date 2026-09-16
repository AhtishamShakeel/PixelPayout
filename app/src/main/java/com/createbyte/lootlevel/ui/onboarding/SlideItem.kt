package com.createbyte.lootlevel.ui.onboarding

data class SlideItem(
    val title: String,
    val description: String,
    val imageResId: Int? = null // Made optional with default value null
)