package com.example.pixelpayout.ui.main

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.pixelpayout.R

/**
 * The app's bottom navigation, drawn to the home design instead of Material's
 * BottomNavigationView: five destinations with Earn raised out of the bar on
 * an accent disc, which a menu-driven BottomNavigationView cannot express.
 *
 * The item ids are the nav graph destination ids, so callers keep addressing
 * tabs the way they did with the Material view (`selectedItemId = R.id.…`).
 * Profile has no destination yet; it is present because the bar is a five-up
 * grid in the design, and selecting it is rejected by MainActivity until a
 * screen exists behind it.
 */
class PixelBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private class Item(
        val id: Int,
        val row: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        /** The raised centre item keeps its white-on-accent icon in every state. */
        val raised: Boolean
    )

    private val items: List<Item>

    private val activeColor = ContextCompat.getColor(context, R.color.primary)
    private val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val boldFont = ResourcesCompat.getFont(context, R.font.lexend_bold)
    private val regularFont = ResourcesCompat.getFont(context, R.font.lexend_regular)

    private var listener: ((Int) -> Boolean)? = null

    /**
     * Setting this runs the listener, as BottomNavigationView did - the home
     * screen navigates by assigning to it. Selection only moves if the
     * listener accepts the tab.
     */
    var selectedItemId: Int = R.id.navigation_home
        set(value) {
            if (value == field) return
            if (items.none { it.id == value }) return
            if (listener?.invoke(value) == false) return
            field = value
            applySelection()
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_bottom_nav, this, true)

        items = listOf(
            Item(R.id.navigation_home, findViewById(R.id.navigation_home), findViewById(R.id.navIconHome), findViewById(R.id.navLabelHome), false),
            Item(R.id.navigation_play, findViewById(R.id.navigation_play), findViewById(R.id.navIconPlay), findViewById(R.id.navLabelPlay), false),
            Item(R.id.navigation_rewards, findViewById(R.id.navigation_rewards), findViewById(R.id.navIconEarn), findViewById(R.id.navLabelEarn), true),
            Item(R.id.navigation_redemption, findViewById(R.id.navigation_redemption), findViewById(R.id.navIconWallet), findViewById(R.id.navLabelWallet), false),
            Item(R.id.navigation_profile, findViewById(R.id.navigation_profile), findViewById(R.id.navIconProfile), findViewById(R.id.navLabelProfile), false)
        )

        items.forEach { item ->
            item.row.setOnClickListener { selectedItemId = item.id }
        }

        applySelection()
    }

    fun setOnItemSelectedListener(listener: (Int) -> Boolean) {
        this.listener = listener
    }

    /**
     * Shows or hides one tab.
     *
     * Exists for Earn, which is only a destination while at least one
     * offerwall is switched on in `config/offerwallWalls`. A tab leading to
     * a screen with nothing on it is worse than no tab: it reads as the
     * feature being broken rather than absent, and it is the one tab whose
     * whole promise is that there is money behind it.
     *
     * The items are weighted 0dp columns, so a hidden one is not a gap - the
     * remaining four redistribute into a four-up bar. What does stay behind
     * is the 16dp of transparent room above the painted bar that the raised
     * Earn disc overhangs into; it is empty space rather than a visible
     * hole, and reclaiming it would mean re-measuring every screen that
     * pads for the bar's height.
     *
     * If the hidden tab was the selected one the highlight falls back to
     * Home, silently. Navigating away is the caller's job - see MainActivity
     * - but leaving the bar pointing at a tab nobody can see is not.
     */
    fun setItemVisible(itemId: Int, visible: Boolean) {
        val item = items.firstOrNull { it.id == itemId } ?: return
        val target = if (visible) VISIBLE else GONE
        if (item.row.visibility == target) return

        item.row.visibility = target
        if (!visible && selectedItemId == itemId) {
            setSelectedItemIdSilently(R.id.navigation_home)
        }
    }

    /**
     * Moves the highlight without navigating - used when the destination
     * changed on its own (system back, a deep link) and the bar has to catch up.
     */
    fun setSelectedItemIdSilently(itemId: Int) {
        if (itemId == selectedItemId || items.none { it.id == itemId }) return
        val previous = listener
        listener = null
        selectedItemId = itemId
        listener = previous
    }

    private fun applySelection() {
        items.forEach { item ->
            val selected = item.id == selectedItemId
            item.row.isSelected = selected
            // The raised item reads as an action, not a tab: its label stays
            // accent and its icon stays white whether or not it is selected.
            val highlighted = selected || item.raised
            item.label.setTextColor(if (highlighted) activeColor else inactiveColor)
            item.label.typeface = if (highlighted) boldFont else regularFont
            if (!item.raised) {
                item.icon.imageTintList = ColorStateList.valueOf(
                    if (selected) activeColor else inactiveColor
                )
            }
        }
    }
}
