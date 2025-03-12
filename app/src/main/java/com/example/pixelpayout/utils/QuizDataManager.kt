package com.pixelpayout.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
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

        Thread {
            try {
                val request = Request.Builder().url(GITHUB_URL).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string()

                if (!json.isNullOrEmpty()) {
                    // Parse the JSON to get the version
                    val quizData = Gson().fromJson(json, QuizData::class.java)
                    val newVersion = quizData.version
                    val currentVersion = getCachedVersion(context)

                    Log.d("QuizDebug", "Current version: $currentVersion, New version: $newVersion")

                    if (newVersion > currentVersion) {
                        // New version available, update cache
                        saveJsonToCache(context, json)
                        saveVersionToCache(context, newVersion)
                        Log.d("QuizDebug", "Updated to new version: $newVersion")
                        onComplete(true)
                    } else {
                        Log.d("QuizDebug", "Using cached version: $currentVersion")
                        onComplete(false)
                    }
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
        return file.exists() && file.length() > 0 && getCachedVersion(context) > 0
    }

    // ✅ Load JSON from local storage
    fun loadCachedQuizzes(context: Context): String? {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }

    // ✅ Save JSON to local storage
    private fun saveJsonToCache(context: Context, json: String) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        FileWriter(file).use { it.write(json) }
    }

    // ✅ Get cached version number
    private fun getCachedVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt("version", 0)
    }

    // ✅ Save new version number
    private fun saveVersionToCache(context: Context, version: Int) {
        val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("version", version).apply()
    }

}
