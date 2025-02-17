package com.pixelpayout.data.model

import com.google.gson.annotations.SerializedName

data class Question(
    @SerializedName("statement") val text: String,
    val options: List<String>,
    @SerializedName("answer") val correctAnswer: Int
)