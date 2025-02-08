package com.pixelpayout.data.repository

import com.pixelpayout.data.model.Quiz
import com.pixelpayout.data.model.Question

class QuizRepository {
    suspend fun getQuizzes(): List<Quiz> {
        return listOf(
            Quiz(
                title = "General Knowledge",
                difficulty = "easy",
                pointsReward = 10,
                questions = listOf(
                    Question(
                        text = "What is the capital of France?",
                        options = listOf("Paris", "London", "Berlin", "Madrid"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Science",
                difficulty = "medium",
                pointsReward = 20,
                questions = listOf(
                    Question(
                        text = "Which planet is known as the Red Planet?",
                        options = listOf("Mars", "Venus", "Jupiter", "Saturn"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Technology",
                difficulty = "hard",
                pointsReward = 30,
                questions = listOf(
                    Question(
                        text = "Who is the co-founder of Microsoft?",
                        options = listOf("Bill Gates", "Steve Jobs", "Mark Zuckerberg", "Jeff Bezos"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Sports",
                difficulty = "medium",
                pointsReward = 20,
                questions = listOf(
                    Question(
                        text = "Which country won the FIFA World Cup 2022?",
                        options = listOf("Argentina", "France", "Brazil", "Germany"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "History",
                difficulty = "hard",
                pointsReward = 30,
                questions = listOf(
                    Question(
                        text = "In which year did World War II end?",
                        options = listOf("1945", "1944", "1946", "1943"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Geography",
                difficulty = "medium",
                pointsReward = 20,
                questions = listOf(
                    Question(
                        text = "Which is the largest continent by area?",
                        options = listOf("Asia", "Africa", "North America", "Europe"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Entertainment",
                difficulty = "easy",
                pointsReward = 10,
                questions = listOf(
                    Question(
                        text = "Who played Iron Man in the Marvel Cinematic Universe?",
                        options = listOf("Robert Downey Jr.", "Chris Evans", "Chris Hemsworth", "Mark Ruffalo"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Science",
                difficulty = "hard",
                pointsReward = 30,
                questions = listOf(
                    Question(
                        text = "What is the hardest natural substance on Earth?",
                        options = listOf("Diamond", "Gold", "Iron", "Platinum"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Mathematics",
                difficulty = "medium",
                pointsReward = 20,
                questions = listOf(
                    Question(
                        text = "What is the value of Pi (π) to two decimal places?",
                        options = listOf("3.14", "3.15", "3.16", "3.13"),
                        correctAnswer = 0
                    )
                )
            ),
            Quiz(
                title = "Literature",
                difficulty = "easy",
                pointsReward = 10,
                questions = listOf(
                    Question(
                        text = "Who wrote 'Romeo and Juliet'?",
                        options = listOf("William Shakespeare", "Charles Dickens", "Jane Austen", "Mark Twain"),
                        correctAnswer = 0
                    )
                )
            )
        )
    }
} 