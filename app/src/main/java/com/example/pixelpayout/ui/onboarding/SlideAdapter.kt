package com.example.pixelpayout.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pixelpayout.R

class SlideAdapter(private val slides: List<SlideItem>) :
    RecyclerView.Adapter<SlideAdapter.SlideViewHolder>() {

    class SlideViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.slideTitle)
        val descriptionText: TextView = view.findViewById(R.id.slideDescription)
        val imageView: ImageView = view.findViewById(R.id.slideImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_slide, parent, false)
        return SlideViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        val slide = slides[position]
        holder.titleText.text = slide.title
        holder.descriptionText.text = slide.description

        // Handle null imageResId
        if (slide.imageResId != null) {
            holder.imageView.setImageResource(slide.imageResId)
            holder.imageView.visibility = View.VISIBLE
        } else {
            holder.imageView.visibility = View.GONE
        }
    }

    override fun getItemCount() = slides.size
}