package com.pixelpayout.data.network

import com.pixelpayout.data.model.*
import retrofit2.http.GET
import retrofit2.http.Path

interface QuizApiService {
    @GET("/")
    suspend fun getCategories(): List<Category>

    @GET("topic/{categoryId}/")
    suspend fun getTopics(@Path("categoryId") categoryId: Int): List<Topic>

    @GET("quiz/{topicId}/")
    suspend fun getQuizzes(@Path("topicId") topicId: Int): List<ApiQuiz>

    @GET("questions/{quizId}/{difficulty}")
    suspend fun getQuestions(
        @Path("quizId") quizId: Int,
        @Path("difficulty") difficulty: String
    ): List<ApiQuestion>
}