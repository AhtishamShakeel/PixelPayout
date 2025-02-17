package com.example.pixelpayout.data.api

import com.pixelpayout.data.model.Question

data class Quiz(
    val title: String,
    val difficulty: String,
    val pointsReward: Int,
    val questions: List<Question>
)
