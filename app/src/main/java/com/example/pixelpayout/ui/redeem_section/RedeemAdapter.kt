package com.example.pixelpayout.ui.redeem_section

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.pixelpayout.data.api.RedeemOption
import com.pixelpayout.databinding.ItemRedeemBinding

class RedeemAdapter (
    private val redeemList: List<RedeemOption>,
    private val onRedeemClick: (RedeemOption) -> Unit
) : RecyclerView.Adapter<RedeemAdapter.RedeemViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RedeemViewHolder {
        val binding = ItemRedeemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RedeemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RedeemViewHolder, position: Int) {
        val redeem = redeemList[position]
        holder.bind(redeem)

    }
}

