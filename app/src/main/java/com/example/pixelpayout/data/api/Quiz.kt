package com.example.pixelpayout.data.api

import com.example.pixelpayout.data.api.Question
import java.io.Serializable

data class Quiz(
    val id: String = "",
    val title: String = "",
    val difficulty: String = "",
    val pointsReward: Int = 0,
    val questions: List<Question> = emptyList(), // ✅ Ensure a default empty list
    // Quiz ids are only unique within a category, so the category is required
    // to identify a quiz server-side when claiming a reward.
    val category: String = "",
    // Index of `questions[0]` within the original, full question list. The
    // server grades against its own answer key using this index.
    val questionIndex: Int = -1
) : Serializable
