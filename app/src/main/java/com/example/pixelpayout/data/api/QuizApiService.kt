package com.example.pixelpayout.data.api


import ApiQuestion
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Path
import java.io.IOException
import java.util.UUID
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query


// Update to:
interface QuizApiService {
    @GET("api.php")
    suspend fun getQuestions(
        @Query("amount") amount: Int
    ): Response<TriviaResponse>
}

data class TriviaResponse(
    val response_code: Int,
    val results: List<ApiQuestion>
)

object QuizApi {
    private const val BASE_URL = "https://opentdb.com/"
    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logger)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Cache-Control", "no-cache")
                .header("X-Request-ID", UUID.randomUUID().toString())
                .build()
            chain.proceed(request)
        }
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