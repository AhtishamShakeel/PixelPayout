package com.example.pixelpayout.utils

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Even gutters for a fixed-span [GridLayoutManager].
 *
 * Not [SpacingItemDecoration]: that one reads a StaggeredGridLayoutManager
 * LayoutParams and would throw the moment it met a plain grid.
 *
 * The columns are kept equal by giving each item a share of the gutter
 * proportional to where it sits in the row, rather than a flat half-gap on
 * each side - a flat gap makes the outer columns wider than the inner ones as
 * soon as there are more than two.
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacingPx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val column = position % spanCount

        outRect.left = column * spacingPx / spanCount
        outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
        if (position >= spanCount) outRect.top = spacingPx
    }
}
