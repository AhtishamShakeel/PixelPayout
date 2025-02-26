package com.pixelpayout.ui.quiz

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.pixelpayout.R
import com.pixelpayout.databinding.DialogQuizResultsBinding
import com.pixelpayout.ui.main.MainActivity

class QuizResultsDialog : DialogFragment() {
    private var _binding: DialogQuizResultsBinding? = null
    private val binding get() = _binding!!
    private var onDismissCallback: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            _binding = DialogQuizResultsBinding.inflate(layoutInflater)

            binding.apply {
                pointsEarnedText.text = getString(
                    R.string.points_earned,
                    arguments?.getInt(ARG_POINTS) ?: 0
                )

                doneButton.setOnClickListener {
                    onDismissCallback?.invoke()
                    dismiss()
                }
            }

            builder.setView(binding.root)
            builder.create().apply {
                setCanceledOnTouchOutside(false)
                setCancelable(false)
            }
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        private const val ARG_POINTS = "points"

        fun show(
            fragmentManager: FragmentManager,
            points: Int,
            onDismiss: () -> Unit
        ) {
            val dialog = QuizResultsDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POINTS, points)
                }
                onDismissCallback = onDismiss
            }
            dialog.show(fragmentManager, "quiz_results")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 