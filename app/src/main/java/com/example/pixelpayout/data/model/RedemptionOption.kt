package com.example.pixelpayout.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One game in the redemption catalogue, with its denominations nested.
 *
 * The catalogue used to be one document per purchasable item, which meant
 * four near-identical documents to add one game and nothing telling the app
 * they belonged together. One document per game is what lets the Wallet grid
 * show games and the sheet show that game's packs - and it makes adding a
 * game a single document in the console.
 *
 * Everything here is read from Firestore. Nothing about a price lives in the
 * app, and the server re-reads all of it when the redemption is attempted.
 */
@Parcelize
data class RedemptionGame(
    val id: String,
    val name: String,
    /** Two or three characters for the tile when there is no artwork. */
    val code: String,
    /** One line under the name in the sheet header. */
    val subtitle: String,
    val packs: List<RedemptionPack>,
    val imageUrl: String? = null,
    /** Level required for this game at all; 1 means no gate. */
    val minLevel: Int = 1,
    val sortOrder: Int = 0,
    /** Label and help text for the player ID field, per game. */
    val idLabel: String,
    val idHint: String,
    val idMinLength: Int = DEFAULT_ID_MIN_LENGTH,
    /**
     * Whether this game also needs the in-game display name. A per-game flag
     * rather than a hardcoded rule, so a game that only needs a UID is a
     * field edit rather than a release.
     */
    val requiresUsername: Boolean = false,
    val usernameLabel: String,
    /** Region choices. Empty means the game has no server concept. */
    val servers: List<String> = emptyList()
) : Parcelable {

    /** The cheapest live pack, for the "from N pts" line on the tile. */
    val fromPointsCost: Int?
        get() = packs.minOfOrNull { it.pointsCost }

    companion object {
        const val DEFAULT_ID_MIN_LENGTH = 4
    }
}

/** One denomination of one game's currency. */
@Parcelize
data class RedemptionPack(
    val id: String,
    /** What the player receives, as text: "325 UC", "310 Diamonds". */
    val amount: String,
    val pointsCost: Int,
    /** Supporting line under the amount ("Best value per point"). */
    val note: String = "",
    /** Small badge ("Popular"). Display only. */
    val tag: String? = null,
    val sortOrder: Int = 0,
    /**
     * The discounted price when this pack is bought with the once-per-account
     * first redeem, or null if this pack is not part of that offer.
     */
    val firstRedeemCost: Int? = null
) : Parcelable {

    val isFirstRedeemOffer: Boolean get() = firstRedeemCost != null
}
