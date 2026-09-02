package com.example.pixelpayout.ui.rewards

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pixelpayout.R
import com.pixelpayout.databinding.ItemLevelRungBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * The rungs of the level ladder.
 *
 * WHY THERE IS AN ADAPTER NOW, against the note that used to sit in
 * fragment_level_rewards saying there should not be: the argument there was
 * that thirty rungs is not enough content to justify the machinery. That is
 * true of the machinery and false of the cost. Every level from 2 up pays
 * stars, so the ladder is not "around ten" rungs, it is twenty-nine - and
 * inflated into a LinearLayout, all twenty-nine existed at once even though
 * about four fit on the screen. That inflation ran on the main thread
 * between the tap and the first frame, which is what the screen's delay
 * was.
 *
 * A RecyclerView inflates what is visible plus a couple spare and recycles
 * the rest, so the cost of opening no longer scales with the length of the
 * curve.
 *
 * [ListAdapter] rather than a plain adapter because the screen redraws from
 * four independent sources (see LevelRewardsFragment): submitting the same
 * ladder again has to be free, and DiffUtil makes it so without the fragment
 * having to remember what it last drew.
 */
class LevelRungAdapter : ListAdapter<LevelLadder.Rung, LevelRungAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemLevelRungBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), isLast = position == itemCount - 1)
    }

    class ViewHolder(
        private val binding: ItemLevelRungBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val inflater = LayoutInflater.from(binding.root.context)

        fun bind(rung: LevelLadder.Rung, isLast: Boolean) {
            val unclaimed = rung.state == LevelLadder.State.UNCLAIMED
            // Unclaimed is a reached level throughout - the climb is done and
            // the perks are real - so everywhere the rung asks "have I got
            // here", the answer stays yes. Only the tag distinguishes them.
            val reached = rung.state == LevelLadder.State.REACHED || unclaimed
            val next = rung.state == LevelLadder.State.NEXT

            binding.rungBadge.apply {
                text = rung.level.toString()
                setBackgroundResource(
                    when {
                        reached -> R.drawable.bg_chip_violet
                        next -> R.drawable.bg_rung_badge_next
                        else -> R.drawable.bg_rung_badge_locked
                    }
                )
                setTextColor(
                    color(if (reached || next) R.color.brand_violet_light else R.color.text_ghost)
                )
            }

            // The rail stops being violet where the climb stops, so the
            // boundary between what is done and what is ahead is visible
            // without reading a single tag. The last rung has nothing below
            // it to connect to.
            binding.rungRail.apply {
                visibility = if (isLast) View.INVISIBLE else View.VISIBLE
                setBackgroundColor(
                    color(if (reached) R.color.violet_tint_35 else R.color.stroke_strong)
                )
            }

            binding.rungCard.setBackgroundResource(
                when {
                    // The rung with something to collect gets the same
                    // highlight the next level does: those are the only two
                    // rows on the screen that are about to change.
                    unclaimed || next -> R.drawable.bg_first_redeem_card
                    reached -> R.drawable.bg_summary_card
                    else -> R.drawable.bg_rung_locked
                }
            )

            binding.rungTitle.apply {
                text = string(R.string.level_card_title, rung.level)
                setTextColor(color(if (reached || next) R.color.white else R.color.text_dim))
            }

            binding.rungThreshold.text =
                string(R.string.level_rewards_threshold, format(rung.xpRequired))

            binding.rungTag.apply {
                when {
                    // Two different sentences, because the queue is drained
                    // lowest-first: one rung can be collected now and the
                    // others are behind it. Saying "claim" on all of them
                    // would promise a choice the server does not offer.
                    unclaimed && rung.isNextClaim -> {
                        setText(R.string.level_rewards_tag_claim)
                        setBackgroundResource(R.drawable.bg_tag_next)
                        setTextColor(color(R.color.stars_accent))
                    }

                    unclaimed -> {
                        setText(R.string.level_rewards_tag_queued)
                        setBackgroundResource(R.drawable.bg_tag_locked)
                        setTextColor(color(R.color.text_dim))
                    }

                    reached -> {
                        setText(R.string.level_rewards_tag_reached)
                        setBackgroundResource(R.drawable.bg_status_done)
                        setTextColor(color(R.color.success))
                    }

                    next -> {
                        setText(R.string.level_rewards_tag_next)
                        setBackgroundResource(R.drawable.bg_tag_next)
                        setTextColor(color(R.color.brand_violet_light))
                    }

                    else -> {
                        setText(R.string.level_rewards_tag_locked)
                        setBackgroundResource(R.drawable.bg_tag_locked)
                        setTextColor(color(R.color.text_ghost))
                    }
                }
            }

            bindPerks(rung.perks, reached || next)
        }

        /**
         * Perk rows are reused rather than rebuilt.
         *
         * A recycled holder usually arrives with the right number of rows
         * already in it - most rungs pay stars and nothing else - so trimming
         * or topping up the list costs no inflation at all in the common
         * case. Rebuilding here would have put the inflation back, one rung
         * at a time, which is the thing this class exists to stop.
         */
        private fun bindPerks(perks: List<LevelLadder.Perk>, unlocked: Boolean) {
            val container = binding.rungPerks

            while (container.childCount > perks.size) {
                container.removeViewAt(container.childCount - 1)
            }
            while (container.childCount < perks.size) {
                container.addView(
                    inflater.inflate(R.layout.item_level_perk, container, false)
                )
            }

            perks.forEachIndexed { index, perk ->
                val row = container.getChildAt(index)
                row.findViewById<ImageView>(R.id.perkIcon).apply {
                    // A locked rung shows the padlock rather than the perk's
                    // own icon: the line is describing something not
                    // available yet, and a gift box that is not yours reads
                    // as one that is.
                    setImageResource(if (unlocked) perk.icon else R.drawable.ic_lock)
                    imageTintList = ContextCompat.getColorStateList(
                        context,
                        if (unlocked) R.color.brand_violet_light else R.color.text_ghost
                    )
                }
                row.findViewById<TextView>(R.id.perkText).apply {
                    text = perk.text
                    setTextColor(color(if (unlocked) R.color.text_soft else R.color.text_faint))
                }
            }
        }

        private fun color(id: Int) = ContextCompat.getColor(binding.root.context, id)

        private fun string(id: Int, vararg args: Any) =
            binding.root.context.getString(id, *args)

        /** Thousands separators - 15,000 XP is unreadable without them. */
        private fun format(value: Int): String =
            NumberFormat.getIntegerInstance(Locale.US).format(value)
    }

    private companion object {

        /**
         * A rung is identified by its level, and its contents are the whole
         * data class - which is safe to compare because every field of Rung
         * and of Perk is a value.
         */
        val DIFF = object : DiffUtil.ItemCallback<LevelLadder.Rung>() {
            override fun areItemsTheSame(old: LevelLadder.Rung, new: LevelLadder.Rung) =
                old.level == new.level

            override fun areContentsTheSame(old: LevelLadder.Rung, new: LevelLadder.Rung) =
                old == new
        }
    }
}
