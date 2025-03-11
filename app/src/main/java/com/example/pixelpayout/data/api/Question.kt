package com.pixelpayout.data.model

import java.io.Serializable

data class Question(
    val text: String = "",  // ✅ Default value to prevent null issues
    val options: List<String> = emptyList(),
    val correctAnswer: Int = 0
) : Serializable
