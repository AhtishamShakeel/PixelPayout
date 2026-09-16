package com.createbyte.lootlevel.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.createbyte.lootlevel.data.repository.UserRepository
import com.createbyte.lootlevel.utils.setStarText
import com.createbyte.lootlevel.ui.redemption.WalletFormat
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.ItemInviteeBinding

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
 *   PAID        - `referralLevelRewardPaid` is set, so the award landed.
 *
 * Progress is the invitee's LEVEL toward the unlock level, because that is
 * the condition the payout actually tests - so this bar fills at the same
 * rate the reward arrives.
 *
 * The SECOND milestone is a tick on the row rather than a state of its own.
 * It is not a later stage of the same journey - an invitee can place a
 * full-price order without ever reaching the unlock level, and the two pay
 * independently - so a single four-state track would have to lie about one
 * of them.
 */
class InviteeAdapter :
    ListAdapter<UserRepository.Invitee, InviteeAdapter.ViewHolder>(DIFF) {

    private var levelReward: Int = 0
    private var redeemReward: Int = 0

    /** Both milestone amounts, published by the server. */
    fun updateRewards(levelPoints: Int, redeemPoints: Int) {
        if (levelPoints == levelReward && redeemPoints == redeemReward) return
        levelReward = levelPoints
        redeemReward = redeemPoints
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

            val target = invitee.levelTarget.coerceAtLeast(1)
            binding.inviteeProgress.progress = (invitee.level * 100 / target).coerceIn(0, 100)
            binding.inviteeProgressLabel.text = context.getString(
                R.string.profile_invitee_progress,
                invitee.level,
                invitee.levelTarget
            )

            val statusRes = when {
                invitee.levelPaid -> R.string.profile_invitee_status_paid
                invitee.qualified -> R.string.profile_invitee_status_qualified
                else -> R.string.profile_invitee_status_progress
            }
            binding.inviteeStatus.setText(statusRes)

            val done = invitee.levelPaid || invitee.qualified
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

            // The note tracks whichever milestone is still outstanding, so a
            // row never goes quiet while money is still to come: an invitee
            // who has been paid for their level still has the larger of the
            // two ahead of them, and that is the one worth naming.
            binding.inviteeNote.text = when {
                invitee.redeemPaid -> context.getString(R.string.profile_invitee_note_redeemed)
                invitee.levelPaid -> context.getString(
                    R.string.profile_invitee_note_awaiting_redeem,
                    redeemReward
                )
                invitee.qualified -> context.getString(R.string.profile_invitee_note_qualified)
                else -> context.getString(
                    R.string.profile_invitee_note_progress,
                    (invitee.levelTarget - invitee.level).coerceAtLeast(0)
                )
            }

            binding.inviteeRewardIcon.setImageResource(
                if (invitee.levelPaid) R.drawable.ic_check else R.drawable.ic_lock
            )

            // Paid rows read violet and unpaid ones stay ghosted, and the
            // star takes that colour with them rather than the usual gold:
            // here the colour is what says whether the reward has landed, so
            // a gold star on an unpaid row would contradict the lock beside
            // it.
            val rewardColorRes =
                if (invitee.levelPaid) R.color.brand_violet_light else R.color.text_ghost
            val rewardColor = context.getColor(rewardColorRes)
            binding.inviteeReward.setTextColor(rewardColor)
            // What this invitee HAS earned once both milestones are counted,
            // rather than one milestone's price: a row that still said 50
            // after a 150 order would understate the account by three times.
            val earned =
                (if (invitee.levelPaid) levelReward else 0) +
                    (if (invitee.redeemPaid) redeemReward else 0)
            binding.inviteeReward.setStarText(
                context.getString(
                    R.string.profile_invitee_reward,
                    if (earned > 0) earned else levelReward
                ),
                starColor = rewardColorRes
            )
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
