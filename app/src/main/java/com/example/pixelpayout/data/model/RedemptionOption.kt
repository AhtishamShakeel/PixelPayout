package com.pixelpayout.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RedemptionOption(
    val id: String,
    val title: String,
    val description: String,
    val pointsCost: Int,
    val type: RedemptionType,
    val imageUrl: String? = null
) : Parcelable

enum class RedemptionType {
    EASYPAISA,
    GAME_CURRENCY
}