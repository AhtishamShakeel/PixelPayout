package com.createbyte.lootlevel.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.databinding.DialogNoInternetBinding

class NoInternetDialog : DialogFragment() {
    private var _binding: DialogNoInternetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogNoInternetBinding.inflate(LayoutInflater.from(context))

        return AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "NoInternetDialog"
    }
} 