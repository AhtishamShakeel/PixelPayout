package com.example.pixelpayout.data.model

import com.google.gson.annotations.SerializedName

// ApiModels.kt
data class Category(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class Topic(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("category_id") val categoryId: Int
)

data class ApiQuiz(
    @SerializedName("id") val id: Int,
    @SerializedName("title") val title: String,
    @SerializedName("difficulty") val difficulty: String,
    @SerializedName("topic_id") val topicId: Int
)

data class ApiQuestion(
    @SerializedName("text") val text: String,
    @SerializedName("options") val options: List<String>,
    @SerializedName("correct_answer_index") val correctAnswerIndex: Int
)