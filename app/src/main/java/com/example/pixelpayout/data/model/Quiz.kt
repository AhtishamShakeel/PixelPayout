package com.pixelpayout.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Quiz(
    val title: String,
    val difficulty: String,
    val pointsReward: Int,
    val questions: List<Question>
) : Parcelable

@Parcelize
data class Question(
    val text: String,
    val options: List<String>,
    val correctAnswer: Int
) : Parcelable 