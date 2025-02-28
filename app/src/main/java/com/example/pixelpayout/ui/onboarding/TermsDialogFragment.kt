package com.example.pixelpayout.ui.onboarding

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pixelpayout.R

class TermsDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val type = arguments?.getString(ARG_TYPE) ?: "terms"

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (type == "terms") R.string.terms_and_conditions_title
                else R.string.privacy_policy_title
            )
            .setMessage(
                if (type == "terms") R.string.terms_and_conditions_text
                else R.string.privacy_policy_text
            )
            .setPositiveButton(R.string.ok) { _, _ -> dismiss() }
            .create()
    }

    companion object {
        private const val ARG_TYPE = "type"

        fun newInstance(type: String): TermsDialogFragment {
            return TermsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                }
            }
        }
    }
}