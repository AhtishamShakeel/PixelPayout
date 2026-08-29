package com.example.pixelpayout.ui.redemption

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.pixelpayout.data.model.RedemptionGame
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemRedemptionGameBinding

/**
 * The Wallet grid: one tile per game.
 *
 * The tile deliberately says nothing about whether the user can afford
 * anything - a game is a doorway, not a purchase, and the packs behind it
 * span a wide enough range that "you cannot afford this" would be wrong about
 * most of them. Affordability is answered in the sheet, per pack.
 *
 * The one thing a tile does gate on is level: a game the account cannot reach
 * yet is dimmed, because opening it would only lead to a sheet that refuses
 * every pack for the same reason.
 */
class RedemptionAdapter(
    private val onOpen: (RedemptionGame) -> Unit
) : ListAdapter<RedemptionGame, RedemptionAdapter.ViewHolder>(DIFF) {

    private var currentLevel: Int = 1

    fun updateLevel(level: Int) {
        if (level == currentLevel) return
        currentLevel = level
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(
        private val binding: ItemRedemptionGameBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(game: RedemptionGame) {
            val context = binding.root.context

            binding.gameName.text = game.name
            binding.gameCode.text = game.code

            val from = game.fromPointsCost
            binding.gameFrom.text = if (from != null) {
                context.getString(R.string.wallet_game_from, WalletFormat.number(from))
            } else {
                ""
            }

            // The dashed code well stays behind the artwork rather than being
            // replaced by it: if the image fails to load there is still a
            // labelled tile instead of a hole.
            binding.gameImage.isVisible = !game.imageUrl.isNullOrBlank()
            if (!game.imageUrl.isNullOrBlank()) {
                binding.gameImage.load(game.imageUrl) { crossfade(true) }
            }

            val locked = currentLevel < game.minLevel
            binding.gameCard.alpha = if (locked) 0.55f else 1f
            binding.gameCard.isClickable = !locked
            binding.gameCard.setOnClickListener(
                if (locked) null else View.OnClickListener { onOpen(game) }
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemRedemptionGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RedemptionGame>() {
            override fun areItemsTheSame(a: RedemptionGame, b: RedemptionGame) = a.id == b.id
            override fun areContentsTheSame(a: RedemptionGame, b: RedemptionGame) = a == b
        }
    }
}
