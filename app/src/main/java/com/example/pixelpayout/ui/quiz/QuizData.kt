package com.pixelpayout.ui.quiz

import com.pixelpayout.data.model.Quiz

data class QuizData(
    val version: Int,
    val categories: List<Category>
)

data class Category(
    val name: String,
    val quizzes: List<Quiz>
)
