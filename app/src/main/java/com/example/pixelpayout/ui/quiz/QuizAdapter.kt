package com.pixelpayout.ui.quiz

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixelpayout.databinding.ItemQuizBinding

class QuizAdapter(
    private val categories: List<QuizListViewModel.QuizCategory>,
    private val onCategoryClick: (QuizListViewModel.QuizCategory) -> Unit
) : ListAdapter<QuizListViewModel.QuizCategory, QuizAdapter.CategoryViewHolder>(CategoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemQuizBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position])

        val layoutParams = holder.binding.root.layoutParams
        layoutParams.height = if (position == 0) dpToPx(holder.binding.root.context, 190) else dpToPx(holder.binding.root.context, 210)
        holder.binding.root.layoutParams = layoutParams

        // Ensure the layout gets redrawn
        holder.binding.root.post {
            holder.binding.root.requestLayout()
        }
    }






    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(val binding: ItemQuizBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(category: QuizListViewModel.QuizCategory) {
            binding.titleText.text = category.name
            binding.floatingImage.setImageResource(category.imageResId)
            binding.root.setOnClickListener { onCategoryClick(category) }
        }
    }

    class CategoryDiffCallback : DiffUtil.ItemCallback<QuizListViewModel.QuizCategory>() {
        override fun areItemsTheSame(oldItem: QuizListViewModel.QuizCategory, newItem: QuizListViewModel.QuizCategory): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: QuizListViewModel.QuizCategory, newItem: QuizListViewModel.QuizCategory): Boolean {
            return oldItem == newItem
        }
    }

    fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

}
