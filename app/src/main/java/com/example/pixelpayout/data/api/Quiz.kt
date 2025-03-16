package com.example.pixelpayout.data.api

import com.example.pixelpayout.data.api.Question
import java.io.Serializable

data class Quiz(
    val id: String = "",
    val title: String = "",
    val difficulty: String = "",
    val pointsReward: Int = 0,
    val questions: List<Question> = emptyList() // ✅ Ensure a default empty list
) : Serializable
