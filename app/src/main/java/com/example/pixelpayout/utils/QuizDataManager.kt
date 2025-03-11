package com.pixelpayout.utils

import android.content.Context
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
        Thread {
            try {
                val request = Request.Builder().url(GITHUB_URL).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string()

                if (!json.isNullOrEmpty()) {
                    val newVersion = Gson().fromJson(json, QuizData::class.java).version
                    val cachedVersion = getCachedVersion(context)

                    if (newVersion > cachedVersion) {
                        saveJsonToCache(context, json)
                        saveVersionToCache(context, newVersion)
                        onComplete(true)  // ✅ New update found
                    } else {
                        onComplete(false) // ❌ No update
                    }
                } else {
                    onComplete(false)
                }
            } catch (e: Exception) {
                onComplete(false)
            }
        }.start()
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
