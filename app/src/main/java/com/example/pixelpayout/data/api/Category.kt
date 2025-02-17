package com.example.pixelpayout.data.api
data class QuizCategory(
    val id: Int,
    val name: String,
    val difficulty: String = "easy"
)

// Predefined categories (using Open Trivia's category IDs)
val defaultCategories = listOf(
    QuizCategory(9, "General Knowledge"),
    QuizCategory(18, "Science: Computers"),
    QuizCategory(23, "History")
)