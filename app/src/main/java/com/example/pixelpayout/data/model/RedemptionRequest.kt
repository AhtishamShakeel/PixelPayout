package com.pixelpayout.data.model

import com.google.firebase.Timestamp

data class RedemptionRequest(
    val userId: String,
    val optionId: String,
    val pointsCost: Int,
    val status: RedemptionStatus = RedemptionStatus.PENDING,
    val paymentDetails: Map<String, String>,
    val createdAt: Timestamp = Timestamp.now(),
    val processedAt: Timestamp? = null
)

enum class RedemptionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}