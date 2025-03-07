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
            if (forcedRefresh || cachedQuizzes == null || cachedQuizzes!!.size <= 4) {
                val response = api.getQuestions(amount = 60)
                val body = response.body()?.results ?: emptyList()

                val easyQuizzes = body.filter { it.difficulty.equals("easy", ignoreCase = true) }

                val quizzesByCategory = easyQuizzes.groupBy { it.category }

                val selectedQuizzes = quizzesByCategory.values.mapNotNull { categoryQuizzes ->
                    categoryQuizzes.randomOrNull()?.let { apiQuestion ->
                        Quiz(
                            id = apiQuestion.question.hashCode().toString(),
                            title = apiQuestion.category,
                            difficulty = apiQuestion.difficulty,
                            pointsReward = 10,
                            questions = listOf(
                                Question(
                                    text = Html.fromHtml(apiQuestion.question).toString(),
                                    options = (apiQuestion.incorrectAnswers + apiQuestion.correctAnswer).shuffled(),
                                    correctAnswer = (apiQuestion.incorrectAnswers + apiQuestion.correctAnswer).indexOf(apiQuestion.correctAnswer)
                                )
                            )
                        )
                    }
                }
                cachedQuizzes = selectedQuizzes
            }
            return@withContext cachedQuizzes!!.take(10)
        }
    }

    fun removeQuizFromCache(quizId: String){
        cachedQuizzes = cachedQuizzes?.filter { it.id != quizId }
    }

}