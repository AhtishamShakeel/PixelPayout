package com.pixelpayout.data.model

import com.google.gson.annotations.SerializedName

data class Category(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String
)

data class Topic(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("category") val categoryId: Int
)

data class ApiQuiz(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("topic") val topicId: Int
)

data class ApiQuestion(
    @SerializedName("id") val id: Int,
    @SerializedName("question") val questionText: String,
    @SerializedName("correct_answer") val correctAnswer: String,
    @SerializedName("incorrect_answers") val incorrectAnswers: List<String>,
    @SerializedName("difficulty") val difficulty: String
)

enum class QuestionDifficulty {
    EASY, MEDIUM, HARD
}

// This will be our final question model that we'll use in the app
data class QuizQuestion(
    val id: Int,
    val questionText: String,
    val options: List<String>,
    val correctAnswerIndex: Int,
    val difficulty: QuestionDifficulty,
    val categoryName: String,
    val pointsReward: Int = when(difficulty) {
        QuestionDifficulty.EASY -> 10
        QuestionDifficulty.MEDIUM -> 20
        QuestionDifficulty.HARD -> 30
    }
)