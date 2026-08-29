package com.example.pixelpayout.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.repository.UserRepository
import com.example.pixelpayout.ui.redemption.WalletFormat
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemInviteeBinding

/**
 * The referral progress list.
 *
 * Three states per row, and they are genuinely different things:
 *
 *   IN PROGRESS - has not reached the unlock XP yet. Nothing is owed.
 *   QUALIFIED   - has reached it, but the payout has not been recorded. This
 *                 is a real window: the referrer is paid inside the same
 *                 transaction that awards the referee's XP, so it closes in
 *                 seconds - but a row that jumped straight to "paid" would be
 *                 claiming money had moved before it had.
 *   PAID        - `referralRewardClaimed` is set, so the award really landed.
 *
 * Progress is XP toward the threshold, not level: that is the condition the
 * payout actually tests, so this bar fills at the same rate the reward
 * arrives.
 */
class InviteeAdapter :
    ListAdapter<UserRepository.Invitee, InviteeAdapter.ViewHolder>(DIFF) {

    private var reward: Int = 0

    fun updateReward(points: Int) {
        if (points == reward) return
        reward = points
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(private val binding: ItemInviteeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(invitee: UserRepository.Invitee) {
            val context = binding.root.context

            binding.inviteeName.text = invitee.name
            binding.inviteeInitials.text = initialsOf(invitee.name)

            binding.inviteeJoined.text = invitee.joinedAtMillis
                ?.let { context.getString(R.string.profile_invitee_joined, WalletFormat.day(context, it)) }
                .orEmpty()
            binding.inviteeJoined.visibility =
                if (binding.inviteeJoined.text.isBlank()) android.view.View.GONE
                else android.view.View.VISIBLE

            val target = invitee.xpTarget.coerceAtLeast(1)
            binding.inviteeProgress.progress = (invitee.xp * 100 / target).coerceIn(0, 100)
            binding.inviteeProgressLabel.text = context.getString(
                R.string.profile_invitee_progress,
                WalletFormat.number(invitee.xp),
                WalletFormat.number(invitee.xpTarget)
            )

            val statusRes = when {
                invitee.paid -> R.string.profile_invitee_status_paid
                invitee.qualified -> R.string.profile_invitee_status_qualified
                else -> R.string.profile_invitee_status_progress
            }
            binding.inviteeStatus.setText(statusRes)

            val done = invitee.paid || invitee.qualified
            binding.inviteeStatus.setBackgroundResource(
                if (done) R.drawable.bg_status_qualified else R.drawable.bg_status_rejected
            )
            binding.inviteeStatus.setTextColor(
                context.getColor(if (done) R.color.brand_violet_light else R.color.text_faint)
            )
            binding.inviteeInitials.setBackgroundResource(
                if (done) R.drawable.bg_invitee_avatar_done else R.drawable.bg_invitee_avatar
            )
            binding.inviteeInitials.setTextColor(
                context.getColor(if (done) R.color.brand_violet_light else R.color.text_faint)
            )

            binding.inviteeNote.text = when {
                invitee.paid -> context.getString(R.string.profile_invitee_note_paid)
                invitee.qualified -> context.getString(R.string.profile_invitee_note_qualified)
                else -> context.getString(
                    R.string.profile_invitee_note_progress,
                    WalletFormat.number((invitee.xpTarget - invitee.xp).coerceAtLeast(0))
                )
            }

            binding.inviteeReward.text = context.getString(R.string.profile_invitee_reward, reward)
            binding.inviteeRewardIcon.setImageResource(
                if (invitee.paid) R.drawable.ic_check else R.drawable.ic_lock
            )

            val rewardColor = context.getColor(
                if (invitee.paid) R.color.brand_violet_light else R.color.text_ghost
            )
            binding.inviteeReward.setTextColor(rewardColor)
            binding.inviteeRewardIcon.imageTintList =
                android.content.res.ColorStateList.valueOf(rewardColor)
        }

        /**
         * Initials from an ALREADY-MASKED name, so this is working with
         * something like "Bil***ed" - the stars are skipped rather than shown
         * as a letter.
         */
        private fun initialsOf(masked: String): String {
            val letters = masked.filter { it.isLetterOrDigit() }
            return when {
                letters.isEmpty() -> "?"
                letters.length == 1 -> letters.take(1).uppercase()
                else -> "${letters.first()}${letters.last()}".uppercase()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemInviteeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<UserRepository.Invitee>() {
            // No uid in the payload - deliberately - so identity is the
            // masked name plus when they joined.
            override fun areItemsTheSame(
                a: UserRepository.Invitee,
                b: UserRepository.Invitee
            ) = a.name == b.name && a.joinedAtMillis == b.joinedAtMillis

            override fun areContentsTheSame(
                a: UserRepository.Invitee,
                b: UserRepository.Invitee
            ) = a == b
        }
    }
}
