package com.example.pixelpayout.utils

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.pixelpayout.R

/**
 * The app's own message dialog, for anywhere that needs to say something.
 *
 * WHY THIS EXISTS rather than AlertDialog: the platform builder paints itself
 * from colorSurface with the platform's typography and radius, so it arrives
 * on top of these screens as a pale grey card in the wrong font with
 * SHOUTING BUTTON LABELS - visibly a different app. dialog_ad_claim already
 * made that argument and built its own card, but it is specifically the
 * watch-an-ad confirmation; everything else that needed a dialog fell back to
 * the platform one. This is that card, generalised, so the next caller does
 * not have to choose between building a layout and looking foreign.
 *
 * Deliberately small. A title, an optional body, one or two buttons, and an
 * accent that colours the glyph beside the title - which is the palette's
 * rule for accents (see the note in colors.xml: accent belongs in the glyph
 * and the figure, not under them). Anything that needs more than this is not
 * a message and should be its own layout.
 *
 * @param icon a glyph for the header. Falls back to a plain dot, so a caller
 *   with nothing apt still gets the accent rather than a bare title.
 * @param accent colours the glyph or dot. The one colour on the card.
 * @param negativeText null for a single-button dialog, which is the common
 *   case - most of these are announcements rather than questions.
 * @param dismissOnPositive false when the action opens something of its own
 *   and wants to close the dialog itself.
 */
fun Context.showAppDialog(
    title: CharSequence,
    message: CharSequence? = null,
    @DrawableRes icon: Int? = null,
    @ColorRes accent: Int = R.color.brand_violet_light,
    positiveText: CharSequence? = null,
    negativeText: CharSequence? = null,
    cancelable: Boolean = true,
    dismissOnPositive: Boolean = true,
    onPositive: (() -> Unit)? = null,
    onNegative: (() -> Unit)? = null
): Dialog {
    val view = View.inflate(this, R.layout.dialog_app_message, null)
    val dialog = Dialog(this, R.style.CustomDialogTheme).apply {
        setContentView(view)
        setCancelable(cancelable)
    }

    val accentColor = ContextCompat.getColor(this, accent)

    val iconView = view.findViewById<ImageView>(R.id.appDialogIcon)
    val dotView = view.findViewById<View>(R.id.appDialogDot)
    if (icon != null) {
        iconView.isVisible = true
        iconView.setImageResource(icon)
        iconView.setColorFilter(accentColor)
    } else {
        dotView.isVisible = true
        dotView.backgroundTintList = ColorStateList.valueOf(accentColor)
    }

    view.findViewById<TextView>(R.id.appDialogTitle).text = title

    val body = view.findViewById<TextView>(R.id.appDialogMessage)
    body.isVisible = !message.isNullOrBlank()
    body.text = message

    val positive = view.findViewById<MaterialButton>(R.id.appDialogPositive)
    positive.text = positiveText ?: getString(R.string.dialog_ok)
    positive.setOnClickListener {
        if (dismissOnPositive) dialog.dismiss()
        onPositive?.invoke()
    }

    val negative = view.findViewById<MaterialButton>(R.id.appDialogNegative)
    negative.isVisible = negativeText != null
    if (negativeText != null) {
        negative.text = negativeText
        negative.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }
    }

    dialog.show()
    return dialog
}

/** String-resource overload, so callers are not forced through getString. */
fun Context.showAppDialog(
    @StringRes title: Int,
    @StringRes message: Int? = null,
    @DrawableRes icon: Int? = null,
    @ColorRes accent: Int = R.color.brand_violet_light,
    @StringRes positiveText: Int? = null,
    @StringRes negativeText: Int? = null,
    cancelable: Boolean = true,
    dismissOnPositive: Boolean = true,
    onPositive: (() -> Unit)? = null,
    onNegative: (() -> Unit)? = null
): Dialog = showAppDialog(
    title = getString(title),
    message = message?.let(::getString),
    icon = icon,
    accent = accent,
    positiveText = positiveText?.let(::getString),
    negativeText = negativeText?.let(::getString),
    cancelable = cancelable,
    dismissOnPositive = dismissOnPositive,
    onPositive = onPositive,
    onNegative = onNegative
)
