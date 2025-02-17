package com.pixelpayout.data.repository

import android.text.Html
import com.example.pixelpayout.data.api.Quiz
import com.example.pixelpayout.data.api.QuizApi
import com.example.pixelpayout.data.api.TriviaResponse
import com.pixelpayout.data.model.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuizRepository {
    private val api = QuizApi.service
    private var cachedQuizzes: List<Quiz>? = null

    suspend fun getQuizzes(): List<Quiz> {
        return withContext(Dispatchers.IO) {
            cachedQuizzes ?: run {
                val response = api.getQuestions(amount = 10)
                val body = response.body()?.results ?: emptyList()

                body.mapNotNull { apiQuestion ->
                    try {
                        val allOptions = (apiQuestion.incorrectAnswers + apiQuestion.correctAnswer).shuffled()
                        Quiz(
                            title = "${apiQuestion.category} (${apiQuestion.difficulty})",
                            difficulty = apiQuestion.difficulty,
                            pointsReward = when (apiQuestion.difficulty.lowercase()) {
                                "easy" -> 10
                                "medium" -> 15
                                "hard" -> 20
                                else -> 10
                            },
                            questions = listOf(
                                Question(
                                    text = Html.fromHtml(apiQuestion.question).toString(),
                                    options = allOptions,
                                    correctAnswer = allOptions.indexOf(apiQuestion.correctAnswer)
                                )
                            )
                        )
                    } catch (e: Exception) {
                        null
                    }
                }.take(3).also { cachedQuizzes = it }
            }
        }
    }
}