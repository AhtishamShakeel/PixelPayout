package com.example.pixelpayout.ui.redemption

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.model.RedemptionPack
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemPackBinding

/**
 * The denominations of one game, inside the redeem sheet.
 *
 * An unaffordable pack is dimmed and shows the shortfall in place of its
 * note, rather than being hidden: the ladder above the user's balance is what
 * tells them what earning more is actually worth, and a list that silently
 * ends at their balance would hide it.
 *
 * [discounted] switches the whole list to first-redeem prices. It is the same
 * pack list at a different price, so it is a flag rather than a second
 * adapter - and the price shown is always the price the server will charge.
 */
class PackAdapter(
    private val discounted: Boolean,
    private val onPick: (RedemptionPack) -> Unit
) : ListAdapter<RedemptionPack, PackAdapter.ViewHolder>(DIFF) {

    private var balance: Int = 0
    private var selectedId: String? = null

    fun updateBalance(points: Int) {
        if (points == balance) return
        balance = points
        notifyItemRangeChanged(0, itemCount)
    }

    /** The price this pack is actually being sold at in this sheet. */
    private fun priceOf(pack: RedemptionPack): Int =
        if (discounted) pack.firstRedeemCost ?: pack.pointsCost else pack.pointsCost

    inner class ViewHolder(private val binding: ItemPackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pack: RedemptionPack) {
            val context = binding.root.context
            val price = priceOf(pack)
            val shortBy = price - balance
            val affordable = shortBy <= 0

            binding.packAmount.text = pack.amount
            binding.packCost.text = WalletFormat.number(price)

            binding.packNote.text = when {
                !affordable ->
                    context.getString(R.string.sheet_pack_short, WalletFormat.number(shortBy))
                discounted -> context.getString(R.string.sheet_discount_applied)
                else -> pack.note
            }
            binding.packNote.isVisible = binding.packNote.text.isNotBlank()

            binding.packTag.isVisible = !pack.tag.isNullOrBlank()
            binding.packTag.text = pack.tag

            binding.packRow.setBackgroundResource(
                if (pack.id == selectedId) R.drawable.bg_pack_row_selected
                else R.drawable.bg_pack_row
            )

            binding.packRow.alpha = if (affordable) 1f else 0.45f
            binding.packRow.isClickable = affordable
            binding.packRow.setOnClickListener(
                if (affordable) {
                    View.OnClickListener {
                        val previous = selectedId
                        selectedId = pack.id
                        // Repaint only the two rows whose selection changed.
                        currentList.indexOfFirst { it.id == previous }
                            .takeIf { it >= 0 }?.let(::notifyItemChanged)
                        notifyItemChanged(bindingAdapterPosition)
                        onPick(pack)
                    }
                } else {
                    null
                }
            )

            val costColor = if (affordable) R.color.gold else R.color.text_ghost
            binding.packCost.setTextColor(context.getColor(costColor))
            binding.packStar.imageTintList =
                android.content.res.ColorStateList.valueOf(context.getColor(costColor))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RedemptionPack>() {
            override fun areItemsTheSame(a: RedemptionPack, b: RedemptionPack) = a.id == b.id
            override fun areContentsTheSame(a: RedemptionPack, b: RedemptionPack) = a == b
        }
    }
}
