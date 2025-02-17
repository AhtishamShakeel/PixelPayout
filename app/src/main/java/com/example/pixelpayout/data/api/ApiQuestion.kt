import com.google.gson.annotations.SerializedName

data class ApiQuestion(
    @SerializedName("category") val category: String,
    @SerializedName("question") val question: String,
    @SerializedName("correct_answer") val correctAnswer: String,
    @SerializedName("incorrect_answers") val incorrectAnswers: List<String>,
    @SerializedName("difficulty") val difficulty: String
)