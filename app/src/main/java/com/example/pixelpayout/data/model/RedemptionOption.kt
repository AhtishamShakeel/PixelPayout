package com.example.pixelpayout.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RedemptionOption(
    val id: String,
    val title: String,
    val description: String,
    val pointsCost: Int,
    val type: RedemptionType,
    val imageUrl: String? = null,
    /** Level required to redeem this option; 1 means no gate. */
    val minLevel: Int = 1
) : Parcelable

enum class RedemptionType {
    EASYPAISA,
    GAME_CURRENCY
}