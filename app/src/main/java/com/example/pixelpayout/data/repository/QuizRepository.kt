package com.pixelpayout.data.repository

import com.pixelpayout.data.api.RetrofitClient
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.model.Question

class QuizRepository {
    private val api = RetrofitClient.quizApi

    suspend fun getQuizzes(): List<Quiz> {
        val response = api.getQuizzes()
        return response.results.map { apiQuestion ->
            Quiz(
                title = apiQuestion.category,
                difficulty = apiQuestion.difficulty,
                pointsReward = when(apiQuestion.difficulty.lowercase()) {
                    "easy" -> 100
                    "medium" -> 200
                    "hard" -> 300
                    else -> 100
                },
                questions = listOf(
                    Question(
                        text = apiQuestion.question,
                        options = listOf(apiQuestion.correct_answer) + apiQuestion.incorrect_answers,
                        correctAnswer = 0 // correct answer is always first in our model
                    )
                )
            )
        }
    }
} 