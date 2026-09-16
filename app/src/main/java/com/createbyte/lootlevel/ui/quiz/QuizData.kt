package com.createbyte.lootlevel.ui.quiz

import com.createbyte.lootlevel.data.api.Quiz

data class QuizData(
    val version: Int,
    val categories: List<Category>
)

data class Category(
    val name: String,
    val quizzes: List<Quiz>
)
