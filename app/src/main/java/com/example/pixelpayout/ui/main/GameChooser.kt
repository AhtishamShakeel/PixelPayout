package com.example.pixelpayout.ui.main

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import coil.load
import com.pixelpayout.R

/**
 * The currency chooser - "which do you play".
 *
 * Shared by Home and Wallet, which both measure their Stars card against the
 * one game chosen here. The choice itself lives in MainViewModel, so picking
 * on either screen moves both cards.
 *
 * [required] is the first-run case: there is no choice yet, so the dialog
 * cannot be dismissed without making one - a card with no game to measure
 * would fall back to quoting every currency at once, which is the confusion
 * this exists to end. From a switch chip it is cancellable, because a choice
 * already stands.
 *
 * Returns the dialog so the caller can dismiss it with its view, or null when
 * there is no catalogue to choose from yet.
 */
fun Fragment.showGameChooser(mainViewModel: MainViewModel, required: Boolean): Dialog? {
    val games = mainViewModel.redemptionGames.value.orEmpty()
    if (games.isEmpty()) return null

    val view = layoutInflater.inflate(R.layout.dialog_game_choice, null)
    val dialog = Dialog(requireContext(), R.style.CustomDialogTheme).apply {
        setContentView(view)
        setCancelable(!required)
        setCanceledOnTouchOutside(!required)
    }

    val options = view.findViewById<ViewGroup>(R.id.gameChoiceOptions)
    val selectedId = mainViewModel.preferredGame.value?.id
    games.forEach { game ->
        val row = layoutInflater.inflate(R.layout.item_game_choice, options, false)
        row.setBackgroundResource(
            if (game.id == selectedId) R.drawable.bg_chip_server_selected
            else R.drawable.bg_chip_server
        )
        row.findViewById<TextView>(R.id.gameChoiceName).text = game.displayName

        val code = row.findViewById<TextView>(R.id.gameChoiceCode)
        code.text = game.code
        game.currencyImageUrl?.let { url ->
            row.findViewById<ImageView>(R.id.gameChoiceImage).load(url) {
                crossfade(true)
                listener(onSuccess = { _, _ -> code.visibility = View.INVISIBLE })
            }
        }

        row.setOnClickListener {
            mainViewModel.setPreferredGame(game.id)
            dialog.dismiss()
        }
        options.addView(row)
    }

    dialog.show()
    return dialog
}
