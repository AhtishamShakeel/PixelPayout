package com.pixelpayout.data.api

import retrofit2.http.GET
import retrofit2.http.Query

interface QuizApiService {
    @GET("api.php")
    suspend fun getQuizzes(
        @Query("amount") amount: Int = 10,
        @Query("type") type: String = "multiple"
    ): QuizResponse
}

data class QuizResponse(
    val response_code: Int,
    val results: List<QuizQuestion>
)

data class QuizQuestion(
    val category: String,
    val type: String,
    val difficulty: String,
    val question: String,
    val correct_answer: String,
    val incorrect_answers: List<String>
) 