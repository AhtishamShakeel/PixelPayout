package com.pixelpayout.data.repository

import com.example.pixelpayout.data.api.QuizApi
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.model.Question

// QuizRepository.kt
class QuizRepository {
    private val api = QuizApi.service

    suspend fun getQuizzes(): List<Quiz> {
        return try {
            val categories = api.getCategories()
            val allQuizzes = mutableListOf<Quiz>()

            categories.forEach { category ->
                val topics = api.getTopics(category.id)
                topics.forEach { topic ->
                    val apiQuizzes = api.getQuizzes(topic.id)
                    apiQuizzes.forEach { apiQuiz ->
                        // Fetch questions for each difficulty level
                        listOf("easy", "medium", "hard").forEach { difficulty ->
                            val questions = api.getQuestions(apiQuiz.id, difficulty)
                            allQuizzes.add(
                                Quiz(
                                    title = "${apiQuiz.title} ($difficulty)",
                                    difficulty = difficulty,
                                    pointsReward = when (difficulty) {
                                        "easy" -> 10
                                        "medium" -> 20
                                        "hard" -> 30
                                        else -> 10
                                    },
                                    questions = questions.map { apiQuestion ->
                                        Question(
                                            text = apiQuestion.text,
                                            options = apiQuestion.options,
                                            correctAnswer = apiQuestion.correctAnswerIndex
                                        )
                                    }
                                )
                            )
                        }
                    }
                }
            }
            allQuizzes
        } catch (e: Exception) {
            emptyList() // Handle error appropriately
        }
    }
}