package com.example.pixelpayout.ui.quiz

import com.example.pixelpayout.data.api.Quiz

data class QuizData(
    val version: Int,
    val categories: List<Category>
)

data class Category(
    val name: String,
    val quizzes: List<Quiz>
)
