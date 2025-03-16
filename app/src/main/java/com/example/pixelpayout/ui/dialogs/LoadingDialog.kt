package com.example.pixelpayout.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.pixelpayout.R
import com.pixelpayout.databinding.DialogLoadingBinding

class LoadingDialog : DialogFragment() {
    private var _binding: DialogLoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogLoadingBinding.inflate(LayoutInflater.from(context))

        return AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LoadingDialog"
    }
} 