package com.pixelpayout.data.model

import java.io.Serializable

data class Quiz(
    val id: String = "",
    val title: String = "",
    val difficulty: String = "",
    val pointsReward: Int = 0,
    val questions: List<Question> = emptyList() // ✅ Ensure a default empty list
) : Serializable
