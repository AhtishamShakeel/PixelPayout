package com.createbyte.lootlevel.ui.home

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.createbyte.lootlevel.R

/** White amount and smaller lilac currency, as in the reference. */
fun TextView.setRewardAmount(value: String) {
    val result = SpannableString(value)
    val start = Regex("[\\p{L}]").find(value)?.range?.first
    if (start != null) {
        result.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.home_lilac)),
            start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        result.setSpan(RelativeSizeSpan(.82f), start, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    text = result
}
