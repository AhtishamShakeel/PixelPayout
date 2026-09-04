package com.example.pixelpayout.ui.rewards

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.model.OfferwallEntry
import com.pixelpayout.databinding.ItemOfferwallBinding

/**
 * The offerwall list.
 *
 * A plain adapter over an immutable list rather than ListAdapter/DiffUtil:
 * the catalogue is two or three rows read once per visit to this screen and
 * it never changes while the screen is open, so diffing would be machinery
 * with nothing to do.
 */
class OfferwallAdapter(
    private val walls: List<OfferwallEntry>,
    private val onClick: (OfferwallEntry) -> Unit,
) : RecyclerView.Adapter<OfferwallAdapter.WallViewHolder>() {

    inner class WallViewHolder(
        private val binding: ItemOfferwallBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(wall: OfferwallEntry) {
            binding.offerwallName.text = wall.name
            binding.offerwallSubtitle.text = wall.subtitle
            // Collapsed rather than left blank: subtitle is optional in the
            // catalogue, and an empty line leaves the row taller than its
            // neighbours for no reason.
            binding.offerwallSubtitle.isVisible = wall.subtitle.isNotEmpty()
            binding.root.setOnClickListener { onClick(wall) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = WallViewHolder(
        ItemOfferwallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: WallViewHolder, position: Int) =
        holder.bind(walls[position])

    override fun getItemCount() = walls.size
}
