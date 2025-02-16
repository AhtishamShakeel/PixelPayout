package com.pixelpayout.ui.quiz

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixelpayout.R
import com.pixelpayout.data.model.Quiz
import com.pixelpayout.databinding.ItemQuizBinding

class QuizAdapter(
    private val onQuizSelected: () -> Unit
) : ListAdapter<QuizListItem, QuizAdapter.QuizViewHolder>(QuizDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuizViewHolder {
        val binding = ItemQuizBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
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
            itemView.setOnClickListener { onQuizSelected() }
        }

        fun bind(quiz: QuizListItem) {
            binding.apply {
                quizTitle.text = quiz.title
                quizDescription.text = quiz.description
                quizReward.text = quiz.pointsReward
            }
        }
    }

    private class QuizDiffCallback : DiffUtil.ItemCallback<QuizListItem>() {
        override fun areItemsTheSame(oldItem: QuizListItem, newItem: QuizListItem) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: QuizListItem, newItem: QuizListItem) =
            oldItem == newItem
    }
} 