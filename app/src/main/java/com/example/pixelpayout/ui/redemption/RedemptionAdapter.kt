package com.example.pixelpayout.ui.redemption

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.model.RedemptionOption
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemRedemptionOptionBinding

/**
 * Shows what the user can spend their stars on. Affordability and level gates
 * are reflected here only to set expectations - the server re-checks both when
 * the redemption is actually attempted, so a stale list can't be exploited.
 */
class RedemptionAdapter(
    private val onRedeem: (RedemptionOption) -> Unit
) : ListAdapter<RedemptionOption, RedemptionAdapter.ViewHolder>(DIFF) {

    private var currentPoints: Int = 0
    private var currentLevel: Int = 1

    fun updateUserState(points: Int, level: Int) {
        currentPoints = points
        currentLevel = level
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemRedemptionOptionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(option: RedemptionOption) {
            binding.titleText.text = option.title
            binding.descriptionText.text = option.description
            binding.pointsCostText.text = binding.root.context.getString(
                R.string.points_cost,
                option.pointsCost
            )

            val levelLocked = currentLevel < option.minLevel
            val affordable = currentPoints >= option.pointsCost

            binding.redeemButton.isEnabled = !levelLocked && affordable
            binding.redeemButton.text = when {
                levelLocked -> binding.root.context.getString(R.string.locked_until_level, option.minLevel)
                else -> binding.root.context.getString(R.string.redeem)
            }

            binding.redeemButton.setOnClickListener { onRedeem(option) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemRedemptionOptionBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RedemptionOption>() {
            override fun areItemsTheSame(a: RedemptionOption, b: RedemptionOption) = a.id == b.id
            override fun areContentsTheSame(a: RedemptionOption, b: RedemptionOption) = a == b
        }
    }
}
