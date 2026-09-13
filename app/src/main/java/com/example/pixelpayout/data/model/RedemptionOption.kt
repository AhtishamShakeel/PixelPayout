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
    /**
     * The game's own name ("PUBG Mobile").
     *
     * NOT DRAWN ANYWHERE IN THE APP. It is carried so an order can be
     * actioned by hand and so the admin tool knows which game to top up;
     * every surface a user sees shows [displayName] instead. See the note on
     * [currencyName].
     */
    val name: String,
    /** Two or three characters for the tile when there is no artwork. */
    val code: String,
    /**
     * What the player receives, named the way the game names it: "UC",
     * "Diamonds", "CP", "Coins".
     *
     * THIS IS WHAT THE APP SHOWS. Printing "PUBG Mobile" or "Free Fire" next
     * to artwork of a character from those games is using someone else's
     * trade mark to sell something; naming the currency is a plain
     * description of what is being sold. The store listing is the risk being
     * managed, and avoiding it costs nothing.
     *
     * Blank on a catalogue written before this field existed, which is why
     * [displayName] falls back rather than rendering an empty tile.
     */
    val currencyName: String = "",
    /** One line under the name in the sheet header. */
    val subtitle: String,
    val packs: List<RedemptionPack>,
    val imageUrl: String? = null,
    /**
     * Artwork of the currency itself - a UC coin, a diamond - rather than of
     * the game. Firestore field `currencyImageUrl`.
     *
     * Shown on the "which do you play" chooser, where the currency is the
     * whole label (see [currencyName] for why it is never the game). Null
     * until art is uploaded; the chooser falls back to [code].
     */
    val currencyImageUrl: String? = null,
    /**
     * The star-pile illustration on the Wallet balance card, and the gift on
     * the first-redeem card.
     *
     * ON THE CATALOGUE RATHER THAN IN A CONFIG DOCUMENT OF THEIR OWN, because
     * this screen already listens to the catalogue and a second document
     * would be a second read on every open for two pictures. They belong to
     * the screen, not to the game whose document happens to carry them - so
     * set them on ONE document and leave them blank on the rest. The first
     * game in sort order that defines one wins, which is deterministic
     * because the list arrives sorted.
     *
     * Null is the normal state until somebody uploads art. Both cards are
     * designed to read correctly without it; see RedemptionFragment.
     */
    val walletHeroUrl: String? = null,
    val firstRedeemArtUrl: String? = null,
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

    /**
     * The packs a user can actually buy at list price.
     *
     * READ THIS RATHER THAN [packs] anywhere a pack is being offered for
     * ordinary purchase. `packs` is the raw catalogue and includes the
     * first-redeem taster, which is not for sale - showing it would offer a
     * price the server refuses, and counting it would advertise a floor the
     * user cannot actually reach.
     *
     * The offer flow deliberately does NOT use this: it wants the tasters,
     * and picks them out with [RedemptionPack.isFirstRedeemOffer].
     */
    val purchasablePacks: List<RedemptionPack>
        get() = packs.filterNot { it.firstRedeemOnly }

    /** The cheapest buyable pack, for the "from N stars" line on the tile. */
    val fromPointsCost: Int?
        get() = purchasablePacks.minOfOrNull { it.pointsCost }

    /**
     * What to put in front of the user for this game.
     *
     * Falls back through code to name so a document written before
     * [currencyName] existed still renders something - but the fallback is a
     * migration cushion, not a design: set currencyName on every game.
     */
    val displayName: String
        get() = currencyName.ifBlank { code.ifBlank { name } }

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
    val firstRedeemCost: Int? = null,
    /**
     * This pack exists ONLY as the first-redeem offer: it is never sold at
     * list price and never appears in a game's pack list.
     *
     * It is what lets the ordinary minimum sit above the taster. The offer
     * used to point at the cheapest ordinary pack, so raising the floor meant
     * deleting the pack the offer was built on. Now the taster is its own
     * pack, reachable only through the offer.
     *
     * Everything that lists packs for ordinary purchase has to filter on
     * this - see [purchasablePacks], which is the one place that should be
     * read from rather than `packs` directly.
     */
    val firstRedeemOnly: Boolean = false
) : Parcelable {

    val isFirstRedeemOffer: Boolean get() = firstRedeemCost != null
}
