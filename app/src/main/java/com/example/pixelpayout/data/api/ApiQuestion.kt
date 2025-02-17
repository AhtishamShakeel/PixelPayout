package com.pixelpayout.data.model

import com.google.gson.annotations.SerializedName

data class ApiQuestion(
    @SerializedName("statement") val statement: String,
    @SerializedName("option_a") val optionA: String?,
    @SerializedName("option_b") val optionB: String?,
    @SerializedName("option_c") val optionC: String?,
    @SerializedName("option_d") val optionD: String?,
    @SerializedName("option_e") val optionE: String?,
    @SerializedName("answer") val answer: String
)