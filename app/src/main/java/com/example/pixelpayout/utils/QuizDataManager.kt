package com.example.pixelpayout.utils

import android.content.Context
import android.util.Log
import com.example.pixelpayout.ui.quiz.QuizData
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileWriter

object QuizDataManager {
    private const val FIREBASE_URL = "https://quizzes-b446b.web.app/quizzes.json"
    private const val CACHE_FILE_NAME = "quizzes.json"
    private const val CACHE_PREFS = "quiz_cache"
    private const val VERSION_KEY = "version"
    private const val LAST_UPDATE_CHECK_KEY = "last_update_check"
    private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private val client = OkHttpClient()

    fun fetchQuizzesFromFirebase(
        context: Context,
        forceRefresh: Boolean = false,
        onComplete: (Boolean) -> Unit
    ) {
        val hasCache = hasCachedQuizzes(context)

        if (!forceRefresh && hasCache && !shouldCheckForUpdates(context)) {
            Log.d("QuizDebug", "Skipping quiz update check; cache is fresh")
            onComplete(false)
            return
        }

        Log.d("QuizDebug", "Checking Firebase for quiz updates")

        Thread {
            try {
                if (hasCache) {
                    saveLastUpdateCheckTime(context)
                }

                val request = Request.Builder().url(FIREBASE_URL).build()
                val response = client.newCall(request).execute()
                val json = response.body?.string()

                if (!json.isNullOrEmpty()) {
                    val quizData = Gson().fromJson(json, QuizData::class.java)
                    val newVersion = quizData.version
                    val currentVersion = getCachedVersion(context)

                    Log.d("QuizDebug", "Current version: $currentVersion, New version: $newVersion")

                    if (newVersion > currentVersion) {
                        saveJsonToCache(context, json)
                        saveVersionToCache(context, newVersion)
                        Log.d("QuizDebug", "Updated to new version: $newVersion")
                        onComplete(true)
                    } else {
                        Log.d("QuizDebug", "Using cached version: $currentVersion")
                        onComplete(false)
                    }

                    saveLastUpdateCheckTime(context)
                } else {
                    Log.d("QuizDebug", "Firebase response was empty")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e("QuizDebug", "Error fetching quizzes: ${e.message}")
                onComplete(false)
            }
        }.start()
    }

    fun loadCachedQuizzes(context: Context): String? {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }

    private fun saveJsonToCache(context: Context, json: String) {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        FileWriter(file).use { it.write(json) }
    }

    private fun hasCachedQuizzes(context: Context): Boolean {
        val file = File(context.filesDir, CACHE_FILE_NAME)
        return file.exists() && file.length() > 0
    }

    private fun getCachedVersion(context: Context): Int {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(VERSION_KEY, 0)
    }

    private fun saveVersionToCache(context: Context, version: Int) {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(VERSION_KEY, version).apply()
    }

    private fun shouldCheckForUpdates(context: Context): Boolean {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(LAST_UPDATE_CHECK_KEY, 0L)
        return System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS
    }

    private fun saveLastUpdateCheckTime(context: Context) {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(LAST_UPDATE_CHECK_KEY, System.currentTimeMillis()).apply()
    }
}
