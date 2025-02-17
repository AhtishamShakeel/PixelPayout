package com.pixelpayout.data.repository


import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.data.model.Question
import com.example.pixelpayout.data.api.QuizApi

class QuizRepository {
    private val api = QuizApi.service

    suspend fun getQuizzes(): List<Quiz> {
        return try {
            val quizId = 71
            val difficulty = "easy"

            // Fetch API questions
            val apiQuestions = api.getQuestions(quizId, difficulty)

            // Convert to app's Question format
            val questions = apiQuestions.map { apiQuestion ->
                val options = listOfNotNull(
                    apiQuestion.optionA,
                    apiQuestion.optionB,
                    apiQuestion.optionC,
                    apiQuestion.optionD,
                    apiQuestion.optionE
                ).filter { !it.isNullOrEmpty() }

                val correctAnswerIndex = options.indexOfFirst { it == apiQuestion.answer }

                Question(
                    text = apiQuestion.statement,
                    options = options,
                    correctAnswer = correctAnswerIndex.coerceAtLeast(0)
                )
            }

            // Create Quiz object
            listOf(
                Quiz(
                    title = "Mathematics Quiz",
                    difficulty = difficulty,
                    pointsReward = when (difficulty) {
                        "easy" -> 10
                        "medium" -> 20
                        "hard" -> 30
                        else -> 10
                    },
                    questions = questions
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}