package com.example.pixelpayout.utils

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class SpacingItemDecoration(private val space: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State )
    {
        val position = parent.getChildAdapterPosition(view)
        val spanCount = 2
        val column = position % spanCount

        if (column == 0){
            outRect.right = space/ 2
        }
        else{
            outRect.left = space / 2
        }

    }
}