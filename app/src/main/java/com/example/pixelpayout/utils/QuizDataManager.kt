package com.pixelpayout.utils

import android.content.Context
import android.util.Log
import com.example.pixelpayout.data.api.TriviaResponse
import com.google.gson.Gson
import com.pixelpayout.data.model.Question
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.ui.quiz.QuizData
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileWriter

object QuizDataManager {
    private const val GITHUB_URL = "https://raw.githubusercontent.com/AhtishamShakeel/quizzes/refs/heads/main/quizzes.json"
    private const val CACHE_FILE_NAME = "quizzes.json"
    private const val VERSION_PREFS = "quiz_version"

    private val client = OkHttpClient()

    // ✅ Fetch JSON from GitHub
    fun fetchQuizzesFromGitHub(context: Context, onComplete: (Boolean) -> Unit) {
        Log.d("QuizDebug", "Fetching quizzes from GitHub...")

        if (isCachedVersionAvailable(context)) { // ✅ Check if cache exists before downloading
            Log.d("QuizDebug", "Using cached quizzes instead of GitHub fetch.")
            onComplete(false)
            return
        }

        val startTime = System.currentTimeMillis()

        Thread {
            try {
                val request = Request.Builder().url(GITHUB_URL).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string()

                val endTime = System.currentTimeMillis()
                Log.d("QuizDebug", "GitHub fetch time: ${endTime - startTime}ms")

                if (!json.isNullOrEmpty()) {
                    saveJsonToCache(context, json) // ✅ Save to cache
                    Log.d("QuizDebug", "Quizzes saved to cache.")
                    onComplete(true)
                } else {
                    Log.d("QuizDebug", "GitHub response was empty!")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e("QuizDebug", "Error fetching quizzes: ${e.message}")
                onComplete(false)
            }
        }.start()
    }

    // ✅ Check if cached version exists
    private fun isCachedVersionAvailable(context: Context): Boolean {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return file.exists() && file.length() > 0
    }


    // ✅ Load JSON from local storage
    fun loadCachedQuizzes(context: Context): String? {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    // ✅ Save JSON to local storage
    private fun saveJsonToCache(context: Context, json: String) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        FileWriter(file).use { it.write(json) }
    }

    // ✅ Get cached version number
    private fun getCachedVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt("version", 0) // Default: 0
    }

    // ✅ Save new version number
    private fun saveVersionToCache(context: Context, version: Int) {
        val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("version", version).apply()
    }

    fun getSingleQuizByCategory(apiUrl: String): Quiz? {
        return try {
            val request = Request.Builder().url(apiUrl).build()
            val response = client.newCall(request).execute()
            val json = response.body?.string()

            if (!json.isNullOrEmpty()) {
                val body = Gson().fromJson(json, TriviaResponse::class.java)
                val apiQuestion = body.results.firstOrNull() ?: return null

                val allOptions = apiQuestion.incorrectAnswers + apiQuestion.correctAnswer
                val shuffledOptions = allOptions.shuffled()
                val correctAnswerIndex = shuffledOptions.indexOf(apiQuestion.correctAnswer)

                Quiz(
                    id = apiQuestion.question.hashCode().toString(),
                    title = apiQuestion.category,
                    difficulty = apiQuestion.difficulty,
                    pointsReward = 10,
                    questions = listOf(
                        Question(
                            text = apiQuestion.question,
                            options = shuffledOptions,
                            correctAnswer = correctAnswerIndex
                        )
                    )
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

}
