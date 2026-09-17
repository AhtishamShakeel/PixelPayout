package com.createbyte.lootlevel.ui.auth

import android.app.Activity
import com.createbyte.lootlevel.R
import com.createbyte.lootlevel.ui.main.MainActivity
import com.createbyte.lootlevel.utils.showAppDialog
import com.google.firebase.auth.FirebaseAuth

/**
 * The line guests stop at: anything with value that leaves the app.
 *
 * Asked from the Auth user rather than the user document, because linking
 * flips it immediately while the document's `isGuest` only catches up a
 * moment later. This is the courtesy in front of the rule - the server
 * refuses the same things from the token (requireLinkedAccount), so a
 * modified app gets nothing by skipping it.
 */
object GuestGate {

    enum class Feature(val messageRes: Int) {
        REDEEM(R.string.guest_locked_redeem),
        REFERRAL(R.string.guest_locked_referral),
        TOURNAMENT(R.string.guest_locked_tournament),
        OFFERS(R.string.guest_locked_offers)
    }

    fun isGuest(): Boolean = FirebaseAuth.getInstance().currentUser?.isAnonymous == true

    /**
     * True when [feature] may go ahead. For a guest it shows the "log in with
     * Google" prompt instead and returns false.
     */
    fun allows(activity: Activity?, feature: Feature): Boolean {
        if (!isGuest()) return true
        val host = activity as? MainActivity ?: return false
        if (host.isFinishing) return false

        host.showAppDialog(
            title = host.getString(R.string.guest_locked_title),
            message = host.getString(feature.messageRes),
            icon = R.drawable.ic_link,
            accent = R.color.brand_violet_light,
            positiveText = host.getString(R.string.guest_link_action),
            negativeText = host.getString(R.string.guest_link_later),
            onPositive = { host.linkGoogle() }
        )
        return false
    }
}
