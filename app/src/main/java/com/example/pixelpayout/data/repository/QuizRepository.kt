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
    var cachedQuizzes:List<Quiz>? = null


    suspend fun getQuizzes(forcedRefresh: Boolean = false): List<Quiz> {
        return withContext(Dispatchers.IO) {

            if (forcedRefresh || cachedQuizzes == null || cachedQuizzes!!.size <= 5) {
                val response = api.getQuestions(amount = 20)
                val body = response.body()?.results ?: emptyList()

                val quizzes = body.mapNotNull { apiQuestion ->
                    try {
                        val allOptions =
                            (apiQuestion.incorrectAnswers + apiQuestion.correctAnswer).shuffled()
                        Quiz(
                            id = apiQuestion.question.hashCode().toString(),
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
                }
                cachedQuizzes = quizzes
            }
            return@withContext cachedQuizzes!!.take(10)
        }
    }

    fun removeQuizFromCache(quizId: String){
        cachedQuizzes = cachedQuizzes?.filter { it.id != quizId }
    }

}