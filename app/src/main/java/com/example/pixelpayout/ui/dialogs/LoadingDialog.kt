package com.example.pixelpayout.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.pixelpayout.R
import com.pixelpayout.databinding.DialogLoadingBinding

class LoadingDialog(private val onRetry: (() -> Unit)? = null) : DialogFragment() {

    private var _binding: DialogLoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogLoadingBinding.inflate(LayoutInflater.from(context))

        return AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initially hide the retry button
        binding.retryButton.visibility = View.GONE

    }

    /**
     * Show retry UI when an error occurs.
     */
    fun showRetry() {
        Log.d("QuizDebug", "showRetry() called, making retry button visible") // Debugging
        binding.loadingText.text = "Failed to Load"
        binding.loadingSubText.text = "Please check your internet and try again."
        binding.loadingProgressBar.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        binding.retryButton.setOnClickListener {
            Log.d("QuizDebug", "Retry button clicked")
            setLoadingState() // Hide retry and show loading UI
            onRetry?.invoke() // Call retry function in MainActivity
        }
    }

    /**
     * Reset UI to loading state.
     */
    fun setLoadingState() {
        binding.loadingText.text = "Loading Data"
        binding.loadingSubText.text = "Please wait while we fetch your data"
        binding.loadingProgressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "LoadingDialog"
    }
}
