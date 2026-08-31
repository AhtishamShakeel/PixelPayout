package com.example.pixelpayout.ui.redemption

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.repository.UserRepository
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemActivityBinding
import kotlin.math.abs

/**
 * The Activity list: the reward ledger, earning and spending in one column.
 *
 * Both directions are shown deliberately. A history that listed only earning
 * would not add up to the balance above it, and the first thing a user checks
 * a history for is where their points went.
 */
class ActivityAdapter :
    ListAdapter<UserRepository.LedgerEntry, ActivityAdapter.ViewHolder>(DIFF) {

    class ViewHolder(private val binding: ItemActivityBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: UserRepository.LedgerEntry) {
            val context = binding.root.context

            binding.activityTitle.text = WalletFormat.label(context, entry)
            binding.activityMeta.text = WalletFormat.day(context, entry.atMillis)
            binding.activityIcon.setImageResource(WalletFormat.icon(entry))

            val magnitude = WalletFormat.number(abs(entry.points))
            binding.activityDelta.text = if (entry.points < 0) {
                context.getString(R.string.activity_delta_minus, magnitude)
            } else {
                context.getString(R.string.activity_delta_plus, magnitude)
            }
            // The figure is Stars either way, so a gain is gold. A SPEND
            // stays neutral rather than going gold with a minus in front:
            // this list is mostly spends, and a column of gold minus-signs
            // would make the screen look like it was mostly paying out.
            binding.activityDelta.setTextColor(
                context.getColor(
                    if (entry.points < 0) R.color.text_faint else R.color.stars_accent
                )
            )

            // A reversed entry is struck through rather than dropped: it is
            // part of the account's history and its refund is listed too, so
            // hiding it would leave the refund looking unexplained.
            binding.activityTitle.paintFlags = if (entry.reversed) {
                binding.activityTitle.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.activityTitle.paintFlags and
                    android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemActivityBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UserRepository.LedgerEntry>() {
            override fun areItemsTheSame(
                a: UserRepository.LedgerEntry,
                b: UserRepository.LedgerEntry
            ) = a.id == b.id

            override fun areContentsTheSame(
                a: UserRepository.LedgerEntry,
                b: UserRepository.LedgerEntry
            ) = a == b
        }
    }
}
