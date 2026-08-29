package com.example.pixelpayout.ui.redemption

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.data.repository.UserRepository.OrderStatus
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemOrderBinding

/**
 * The Orders tab.
 *
 * The handoff draws a three-step tracker - Placed, Processing, Delivered -
 * but the server only ever stores `pending`, `approved` or `rejected`. Rather
 * than invent a fourth state nobody writes, PENDING lights the first two dots
 * and stops at Processing: the request exists and is not finished, which is
 * the whole of what is actually known.
 *
 * A declined order shows the tracker greyed and says the stars came back,
 * because that is the fact the user cares about at that point.
 */
class OrdersAdapter(
    private val onCopyId: (String) -> Unit
) : ListAdapter<UserRepository.Order, OrdersAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(order: UserRepository.Order) {
            val context = binding.root.context

            binding.orderCode.text = order.code.ifBlank { order.gameName.take(2).uppercase() }
            binding.orderAmount.text = order.packAmount.ifBlank { order.gameName }

            binding.orderMeta.text = when (val at = order.createdAtMillis) {
                null -> context.getString(R.string.order_meta, order.gameName, order.playerId)
                else -> context.getString(
                    R.string.order_meta_dated,
                    order.gameName,
                    order.playerId,
                    WalletFormat.day(context, at)
                )
            }

            val statusLabel = when (order.status) {
                OrderStatus.PENDING -> R.string.order_status_processing
                OrderStatus.DELIVERED -> R.string.order_status_delivered
                OrderStatus.REJECTED -> R.string.order_status_declined
            }
            binding.orderStatus.setText(statusLabel)

            val (statusBg, statusColor) = when (order.status) {
                OrderStatus.PENDING -> R.drawable.bg_status_pending to R.color.gold
                OrderStatus.DELIVERED -> R.drawable.bg_status_done to R.color.success
                OrderStatus.REJECTED -> R.drawable.bg_status_rejected to R.color.text_faint
            }
            binding.orderStatus.setBackgroundResource(statusBg)
            binding.orderStatus.setTextColor(context.getColor(statusColor))

            // How far along the three dots are lit. A declined order lights
            // none of them - it did not progress, it stopped.
            val reached = when (order.status) {
                OrderStatus.PENDING -> 1
                OrderStatus.DELIVERED -> 2
                OrderStatus.REJECTED -> -1
            }

            val dots = listOf(binding.trackerDot1, binding.trackerDot2, binding.trackerDot3)
            dots.forEachIndexed { index, dot ->
                dot.setBackgroundResource(
                    if (index <= reached) R.drawable.bg_tracker_dot_done
                    else R.drawable.bg_tracker_dot_todo
                )
            }

            val lineDone = context.getColor(R.color.brand_violet_deep)
            val lineTodo = context.getColor(R.color.stroke_strong)
            binding.trackerLine1.setBackgroundColor(if (reached >= 1) lineDone else lineTodo)
            binding.trackerLine2.setBackgroundColor(if (reached >= 2) lineDone else lineTodo)

            binding.orderTracker.alpha = if (order.status == OrderStatus.REJECTED) 0.4f else 1f
            binding.orderTrackerLabels.alpha = binding.orderTracker.alpha

            // The 48h promise, counted from when the request was recorded.
            val eta = if (order.status == OrderStatus.PENDING) {
                WalletFormat.payoutEta(context, order.createdAtMillis)
            } else {
                null
            }
            binding.orderEtaRow.isVisible = eta != null
            if (eta != null) binding.orderEta.text = eta

            // Short enough to read out, long enough to be unambiguous - the
            // full document id is 20 characters of base62 nobody will dictate
            // over a chat window.
            val shortId = order.id.takeLast(SUPPORT_ID_LENGTH).uppercase()
            binding.orderId.text = context.getString(R.string.order_id_label, shortId)
            binding.orderId.setOnClickListener { onCopyId(shortId) }

            binding.orderRejection.isVisible = order.status == OrderStatus.REJECTED
            if (order.status == OrderStatus.REJECTED) {
                val reason = order.rejectionReason?.trim().orEmpty()
                binding.orderRejection.text = if (reason.isEmpty()) {
                    context.getString(R.string.order_refunded_plain)
                } else {
                    context.getString(R.string.order_refunded, reason)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        /** How much of the redemption id is shown for support. */
        private const val SUPPORT_ID_LENGTH = 8

        private val DIFF = object : DiffUtil.ItemCallback<UserRepository.Order>() {
            override fun areItemsTheSame(a: UserRepository.Order, b: UserRepository.Order) =
                a.id == b.id

            override fun areContentsTheSame(a: UserRepository.Order, b: UserRepository.Order) =
                a == b
        }
    }
}
