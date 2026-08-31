package com.example.pixelpayout.ui.rewards

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * One fixed row, for use as a ConcatAdapter header or footer.
 *
 * The Level rewards screen is a RecyclerView end to end - that is the only
 * arrangement in which the rungs actually recycle - so the chrome above and
 * below them has to be list rows too.
 *
 * It draws through [bind] rather than being handed a pre-inflated view to
 * hold: a header this tall scrolls off screen, and a holder that RecyclerView
 * has recycled must be redrawable from the owner's current state. Handing the
 * adapter one shared View instead would work only for as long as nothing
 * detached it, which is a much sharper edge than it looks.
 *
 * [redraw] is how the owner says the state changed. It rebinds; it never
 * re-inflates, because a recycled row comes back out of the pool.
 */
class SingleRowAdapter<B : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup) -> B,
    private val bind: (B) -> Unit
) : RecyclerView.Adapter<SingleRowAdapter.Holder<B>>() {

    class Holder<B : ViewBinding>(val binding: B) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder<B> =
        Holder(inflate(LayoutInflater.from(parent.context), parent))

    override fun onBindViewHolder(holder: Holder<B>, position: Int) = bind(holder.binding)

    override fun getItemCount() = 1

    /** Redraw from whatever the owner's state now says. */
    fun redraw() = notifyItemChanged(0)
}
