package com.example.pixelpayout.ui.quiz

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.pixelpayout.R
import com.pixelpayout.databinding.DialogQuizResultsBinding

/**
 * The end of a quiz attempt, and where its double-XP offer is made.
 *
 * The card's contents are view_reward_results, the same layout the game
 * results panel includes, so the two screens are the same design by
 * construction rather than by anyone remembering to keep them matched. Only
 * the title differs, and it is set below.
 *
 * The ad itself is NOT run from here. It is driven by QuizActivity, which
 * already owns the view model and outlives this fragment - a rewarded ad
 * needs an Activity to show over, and a DialogFragment that both showed ads
 * and claimed against a view model would have two owners for one flow. This
 * renders the offer and reports taps; the activity decides what happens.
 */
class QuizResultsDialog : DialogFragment() {
    private var _binding: DialogQuizResultsBinding? = null
    private val binding get() = _binding!!
    private var onDismissCallback: (() -> Unit)? = null
    private var onWatchAdCallback: (() -> Unit)? = null
    private var offerDouble = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val builder = AlertDialog.Builder(it)
            _binding = DialogQuizResultsBinding.inflate(layoutInflater)

            binding.resultsContent.apply {
                resultsTitle.setText(R.string.quiz_results_title)
                resultsXpText.text = getString(
                    R.string.results_xp_earned,
                    arguments?.getInt(ARG_POINTS) ?: 0
                )

                doubleXpButton.visibility = if (offerDouble) View.VISIBLE else View.GONE
                doubleXpButton.setOnClickListener { onWatchAdCallback?.invoke() }

                resultsContinueButton.setOnClickListener {
                    onDismissCallback?.invoke()
                    dismiss()
                }
            }

            builder.setView(binding.root)
            builder.create().apply {
                setCanceledOnTouchOutside(false)
                setCancelable(false)
                // The grey corners: AlertDialog paints its own themed
                // background behind whatever setView is given, and the card
                // on top of it is rounded - so the platform's square, lighter
                // panel shows through at all four corners. The card carries
                // the whole surface, so the window underneath it should paint
                // nothing at all.
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    /**
     * Puts the offer into its "an ad is happening" state.
     *
     * Continue is disabled alongside it: dismissing mid-ad would strand a
     * claim against a dialog that is going away.
     */
    fun showAdInProgress(messageRes: Int) {
        val content = _binding?.resultsContent ?: return
        content.doubleXpButton.isEnabled = false
        content.resultsContinueButton.isEnabled = false
        content.doubleXpStatus.visibility = View.VISIBLE
        content.doubleXpStatus.setText(messageRes)
    }

    /** Puts the offer back after an ad that never paid out. */
    fun restoreOffer(messageRes: Int?) {
        val content = _binding?.resultsContent ?: return
        content.doubleXpButton.isEnabled = true
        content.resultsContinueButton.isEnabled = true
        if (messageRes == null) {
            content.doubleXpStatus.visibility = View.GONE
        } else {
            content.doubleXpStatus.visibility = View.VISIBLE
            content.doubleXpStatus.setText(messageRes)
        }
    }

    /**
     * Retires the offer and says how it went.
     *
     * The button goes in every outcome: the ad is spent, and one attempt
     * cannot be doubled twice whether or not the call landed.
     */
    fun settleOffer(message: String, newTotal: Int?) {
        val content = _binding?.resultsContent ?: return
        content.doubleXpButton.visibility = View.GONE
        content.resultsContinueButton.isEnabled = true
        content.doubleXpStatus.visibility = View.VISIBLE
        content.doubleXpStatus.text = message
        if (newTotal != null) {
            content.resultsXpText.text = getString(R.string.results_xp_earned, newTotal)
        }
    }

    companion object {
        private const val ARG_POINTS = "points"

        fun show(
            fragmentManager: FragmentManager,
            points: Int,
            canDouble: Boolean,
            onWatchAd: () -> Unit,
            onDismiss: () -> Unit
        ): QuizResultsDialog {
            val dialog = QuizResultsDialog().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POINTS, points)
                }
                offerDouble = canDouble
                onWatchAdCallback = onWatchAd
                onDismissCallback = onDismiss
            }
            dialog.show(fragmentManager, "quiz_results")
            return dialog
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
