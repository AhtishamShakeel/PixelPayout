package com.pixelpayout.ui.quiz

data class QuizCategory(
    val name: String,
    val imageResId: Int, // 🟢 Drawable resource ID for category image
    val apiUrl: String
)
