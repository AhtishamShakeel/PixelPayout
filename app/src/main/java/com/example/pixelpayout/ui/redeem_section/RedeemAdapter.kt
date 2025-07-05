package com.example.pixelpayout.ui.redeem_section

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.api.RedeemOption
import com.pixelpayout.databinding.ItemRedeemBinding

class
RedeemAdapter (
    private val redeemList: List<RedeemOption>,
    private val onRedeemClick: (RedeemOption) -> Unit
) : RecyclerView.Adapter<RedeemAdapter.RedeemViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RedeemViewHolder {
        val binding = ItemRedeemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RedeemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RedeemViewHolder, position: Int) {
        val redeem = redeemList[position]
        holder.bind(redeem)

        holder.binding.titleTextRedeem.text = redeem.title
        holder.binding.starsTextRedeem.text = redeem.requiredStars.toString()

        val layoutParams = holder.binding.root.layoutParams
        layoutParams.height = if (position == 0) dpToPx(holder.binding.root.context, 200) else dpToPx(holder.binding.root.context, 225)
        holder.binding.root.layoutParams = layoutParams

        holder.binding.root.post {
            holder.binding.root.requestLayout()
        }
    }

    override fun getItemCount(): Int = redeemList.size

    inner class RedeemViewHolder(val binding: ItemRedeemBinding) :
            RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onRedeemClick(redeemList[position])
                }
            }
        }

        fun bind(redeem: RedeemOption) {
            binding.titleTextRedeem.text = redeem.title
        }
    }


    fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }



}

