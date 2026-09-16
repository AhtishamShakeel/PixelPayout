package com.createbyte.lootlevel.ui.main

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import coil.load
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.data.model.RedemptionGame

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
    val gap = (10 * resources.displayMetrics.density).toInt()

    // Two to a row. An odd last game gets an empty spacer beside it, so every
    // tile keeps the same width instead of the last one stretching.
    games.chunked(2).forEachIndexed { rowIndex, pair ->
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rowIndex > 0) topMargin = gap }
        }

        pair.forEachIndexed { index, game ->
            val tile = layoutInflater.inflate(R.layout.item_game_choice, row, false)
            (tile.layoutParams as LinearLayout.LayoutParams).apply {
                if (index > 0) marginStart = gap
            }
            bindTile(tile, game, selected = game.id == selectedId)
            tile.setOnClickListener {
                mainViewModel.setPreferredGame(game.id)
                dialog.dismiss()
            }
            row.addView(tile)
        }
        if (pair.size == 1) {
            row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f).apply {
                marginStart = gap
            })
        }
        options.addView(row)
    }

    dialog.show()
    return dialog
}

/**
 * One tile: the artwork the catalogue sends, or the code in its dashed well.
 *
 * The currency's own art (`currencyImageUrl`) when a game has it, otherwise
 * the game artwork (`imageUrl`) the Wallet grid already shows - so a game
 * with either uploaded gets a picture here too.
 */
private fun bindTile(tile: View, game: RedemptionGame, selected: Boolean) {
    tile.setBackgroundResource(
        if (selected) R.drawable.bg_game_choice_selected else R.drawable.bg_game_tile
    )
    tile.findViewById<View>(R.id.gameChoiceCheck).isVisible = selected
    tile.findViewById<TextView>(R.id.gameChoiceName).text = game.displayName
    tile.contentDescription = game.displayName

    val code = tile.findViewById<TextView>(R.id.gameChoiceCode)
    val image = tile.findViewById<ImageView>(R.id.gameChoiceImage)
    code.text = game.code

    val art = game.currencyImageUrl?.takeIf(String::isNotBlank)
        ?: game.imageUrl?.takeIf(String::isNotBlank)
    image.isVisible = art != null
    code.isInvisible = art != null
    if (art != null) {
        image.load(art) {
            crossfade(true)
            listener(onError = { _, _ ->
                image.isVisible = false
                code.isInvisible = false
            })
        }
    }
}
