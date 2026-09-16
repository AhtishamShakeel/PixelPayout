package com.createbyte.lootlevel.ui.help

import android.content.Context
import android.content.res.ColorStateList
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.createbyte.lootlevel.data.repository.SupportTicketStore
import com.createbyte.lootlevel.ui.main.MainActivity
import com.createbyte.lootlevel.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Labels, colours and small helpers shared by the support screens. */
internal object SupportUi {

    /** Order matches the server's SUPPORT_CATEGORIES. */
    val categories: List<Pair<String, Int>> = listOf(
        "redemption" to R.string.support_cat_redemption,
        "missing_reward" to R.string.support_cat_missing_reward,
        "account" to R.string.support_cat_account,
        "referral" to R.string.support_cat_referral,
        "bug" to R.string.support_cat_bug,
        "other" to R.string.support_cat_other
    )

    fun categoryLabel(context: Context, id: String): String =
        categories.firstOrNull { it.first == id }?.let { context.getString(it.second) }
            ?: context.getString(R.string.support_cat_other)

    /** Fills a status pill: text, text colour and a matching tint. */
    fun bindStatus(pill: TextView, status: String) {
        val context = pill.context
        val (label, color) = when (status) {
            SupportTicketStore.STATUS_ANSWERED -> R.string.support_status_answered to R.color.gold
            SupportTicketStore.STATUS_RESOLVED -> R.string.support_status_resolved to R.color.text_faint
            else -> R.string.support_status_open to R.color.brand_violet_light
        }
        val c = ContextCompat.getColor(context, color)
        pill.setText(label)
        pill.setTextColor(c)
        pill.backgroundTintList = ColorStateList.valueOf((c and 0x00FFFFFF) or 0x24000000)
    }

    fun relativeTime(context: Context, millis: Long): String {
        val elapsed = (System.currentTimeMillis() - millis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val days = TimeUnit.MILLISECONDS.toDays(elapsed)
        return when {
            minutes < 1 -> context.getString(R.string.time_just_now)
            minutes < 60 -> context.getString(R.string.time_minutes_ago, minutes)
            hours < 24 -> context.getString(R.string.time_hours_ago, hours)
            else -> context.getString(R.string.time_days_ago, days)
        }
    }

    fun messageTime(millis: Long?): String =
        millis?.let { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(it)) }.orEmpty()

    /**
     * Full-height writing screens: the bottom bar goes, and the window resizes
     * for the keyboard so the reply box stays visible. Undone on the way out.
     */
    fun enterComposeMode(fragment: Fragment) {
        (fragment.activity as? MainActivity)?.binding?.bottomNav?.isVisible = false
        fragment.activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun exitComposeMode(fragment: Fragment) {
        (fragment.activity as? MainActivity)?.binding?.bottomNav?.isVisible = true
        fragment.activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
    }
}
