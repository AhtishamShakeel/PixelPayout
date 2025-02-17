package com.pixelpayout.ui.quiz

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixelpayout.R
import com.example.pixelpayout.data.api.Quiz
import com.pixelpayout.databinding.ItemQuizBinding

class QuizAdapter(private val onQuizClick: (Quiz) -> Unit) :
    ListAdapter<Quiz, QuizAdapter.QuizViewHolder>(QuizDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuizViewHolder {
        val binding = ItemQuizBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return QuizViewHolder(binding)
    }

    override fun onBindViewHolder(holder: QuizViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class QuizViewHolder(
        private val binding: ItemQuizBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onQuizClick(getItem(position))
                }
            }
        }

        fun bind(quiz: Quiz) {
            binding.apply {
                titleText.text = quiz.title
                difficultyChip.text = quiz.difficulty
                pointsText.text = root.context.getString(R.string.points_value, quiz.pointsReward)

                // Set chip color based on difficulty
                val colorRes = when (quiz.difficulty.lowercase()) {
                    "easy" -> R.color.difficulty_easy
                    "medium" -> R.color.difficulty_medium
                    "hard" -> R.color.difficulty_hard
                    else -> R.color.difficulty_easy
                }
                difficultyChip.setChipBackgroundColorResource(colorRes)
            }
        }
    }

    private class QuizDiffCallback : DiffUtil.ItemCallback<Quiz>() {
        override fun areItemsTheSame(oldItem: Quiz, newItem: Quiz): Boolean {
            return oldItem.title == newItem.title
        }

        override fun areContentsTheSame(oldItem: Quiz, newItem: Quiz): Boolean {
            return oldItem == newItem
        }
    }
} 