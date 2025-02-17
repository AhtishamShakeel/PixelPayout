package com.example.pixelpayout.data.api

import com.example.pixelpayout.data.model.ApiQuestion
import com.example.pixelpayout.data.model.ApiQuiz
import com.example.pixelpayout.data.model.Category
import com.example.pixelpayout.data.model.Topic
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path


interface QuizApiService {
    @GET("categories/")
    suspend fun getCategories(): List<Category>

    @GET("topics/{category_id}/")
    suspend fun getTopics(@Path("category_id") categoryId: Int): List<Topic>

    @GET("quizzes/{topic_id}/")
    suspend fun getQuizzes(@Path("topic_id") topicId: Int): List<ApiQuiz>

    @GET("questions/{quiz_id}/{difficulty}/")
    suspend fun getQuestions(
        @Path("quiz_id") quizId: Int,
        @Path("difficulty") difficulty: String
    ): List<ApiQuestion>
}

object QuizApi {
    private const val BASE_URL = "https://quizverse.pythonanywhere.com/"
    val service: QuizApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuizApiService::class.java)
    }
}