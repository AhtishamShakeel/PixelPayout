package com.example.pixelpayout.data.api


import com.pixelpayout.data.model.ApiQuestion
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path


interface QuizApiService {
    @GET("questions/{quiz_id}/{difficulty}")
    suspend fun getQuestions(
        @Path("quiz_id") quizId: Int,
        @Path("difficulty") difficulty: String
    ): List<ApiQuestion>
}

object QuizApi {
    private const val BASE_URL = "https://quizverse.pythonanywhere.com/"
    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logger)
        .build()

    val service: QuizApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(QuizApiService::class.java)
    }
}