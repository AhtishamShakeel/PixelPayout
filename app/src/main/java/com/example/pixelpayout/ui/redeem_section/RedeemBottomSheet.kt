package com.example.pixelpayout.ui.redeem_section

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.bumptech.glide.Glide
import com.example.pixelpayout.data.api.RedeemOption
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pixelpayout.databinding.BottomSheetRedeemBinding

class RedeemBottomSheet(
    private val redeemOption: RedeemOption,
    private val onSubmit: (String) -> Unit
    ): BottomSheetDialogFragment() {

        private lateinit var binding: BottomSheetRedeemBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = BottomSheetRedeemBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(android.R.color.transparent)
        }
        binding.rewardTitle.text = redeemOption.title
        binding.rewardStars.text = "${redeemOption.requiredStars} Stars"
        Glide.with(this).load(redeemOption.imageUrl).into(binding.rewardImage)

        binding.submitButton.setOnClickListener {
            val input = binding.userInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter ${redeemOption.inputLabel}", Toast.LENGTH_SHORT).show()

            } else {
                dismiss()
                onSubmit(input)
            }
        }
    }
        
}