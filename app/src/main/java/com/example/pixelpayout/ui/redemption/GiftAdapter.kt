package com.example.pixelpayout.ui.redemption

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.model.RedemptionGame
import com.example.pixelpayout.data.model.RedemptionPack
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemGiftPackBinding

/** One discounted pack, together with the game it belongs to. */
data class GiftOffer(val game: RedemptionGame, val pack: RedemptionPack) {
    val key: String get() = "${game.id}/${pack.id}"
}

/**
 * The first-redeem picker: every discounted pack, across every game, in one
 * grid.
 *
 * This exists because the offer is one choice spanning the whole catalogue -
 * "30 UC, or 20 diamonds, or 20 coins" - not a discount you find after
 * picking a game. Routing it through the per-game pack list hid it behind a
 * decision the user has not made yet and made the offer look like a sale on
 * one game.
 *
 * Each cell carries its own price. `firstRedeemCost` is a per-pack field, so
 * the offers are only all the same price by convention - and a cell that does
 * not say what it costs sends a user who cannot afford it three steps down
 * the flow before the server refuses them.
 */
class GiftAdapter(
    private val onPick: (GiftOffer) -> Unit
) : ListAdapter<GiftOffer, GiftAdapter.ViewHolder>(DIFF) {

    private var selectedKey: String? = null
    private var balance: Int = 0

    fun updateBalance(points: Int) {
        if (points == balance) return
        balance = points
        notifyItemRangeChanged(0, itemCount)
    }

    /** The discounted price, falling back to list price if none is set. */
    private fun priceOf(offer: GiftOffer): Int =
        offer.pack.firstRedeemCost ?: offer.pack.pointsCost

    inner class ViewHolder(private val binding: ItemGiftPackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(offer: GiftOffer) {
            val context = binding.root.context
            val price = priceOf(offer)
            val shortBy = price - balance
            val affordable = shortBy <= 0

            binding.giftCode.text = offer.game.code
            binding.giftAmount.text = offer.pack.amount
            binding.giftGame.text = offer.game.name
            binding.giftCost.text = WalletFormat.number(price)

            binding.giftShort.isVisible = !affordable
            if (!affordable) {
                binding.giftShort.text = context.getString(
                    R.string.sheet_pack_short, WalletFormat.number(shortBy)
                )
            }

            val costColor = context.getColor(
                if (affordable) R.color.gold else R.color.text_ghost
            )
            binding.giftCost.setTextColor(costColor)
            binding.giftStar.imageTintList = ColorStateList.valueOf(costColor)

            binding.giftCard.setBackgroundResource(
                if (offer.key == selectedKey) R.drawable.bg_pack_row_selected
                else R.drawable.bg_pack_row
            )

            // An unaffordable offer stays on screen but cannot be chosen: the
            // grid is also how a user learns what the offer covers, so hiding
            // it would answer a question by removing it.
            binding.giftCard.alpha = if (affordable) 1f else 0.45f
            binding.giftCard.isClickable = affordable
            binding.giftCard.setOnClickListener(
                if (!affordable) {
                    null
                } else {
                    View.OnClickListener {
                        val previous = selectedKey
                        selectedKey = offer.key
                        currentList.indexOfFirst { it.key == previous }
                            .takeIf { it >= 0 }?.let(::notifyItemChanged)
                        notifyItemChanged(bindingAdapterPosition)
                        onPick(offer)
                    }
                }
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemGiftPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GiftOffer>() {
            override fun areItemsTheSame(a: GiftOffer, b: GiftOffer) = a.key == b.key
            override fun areContentsTheSame(a: GiftOffer, b: GiftOffer) = a == b
        }
    }
}
