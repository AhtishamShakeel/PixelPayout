package com.pixelpayout.ui.quiz

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.pixelpayout.R
import com.pixelpayout.databinding.DialogQuizResultsBinding
import com.pixelpayout.ui.main.MainActivity

class QuizResultsDialog : DialogFragment() {
    private var _binding: DialogQuizResultsBinding? = null
    private val binding get() = _binding!!

    private var pointsEarned: Int = 0
    private var onDismissCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_MaterialComponents_Dialog_MinWidth)
        pointsEarned = arguments?.getInt(ARG_POINTS_EARNED) ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQuizResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.pointsEarnedText.text = getString(R.string.points_earned, pointsEarned)
        
        binding.doneButton.setOnClickListener {
            dismiss()
            onDismissCallback?.invoke()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        _binding = null
    }

    companion object {
        private const val ARG_POINTS_EARNED = "points_earned"


        fun newInstance(pointsEarned: Int, onDismiss: () -> Unit): QuizResultsDialog {
            return QuizResultsDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POINTS_EARNED, pointsEarned)

                }
                onDismissCallback = onDismiss
            }
        }
    }
} 