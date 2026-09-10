package com.example.pixelpayout.data.model

/**
 * One offerwall in the catalogue.
 *
 * Read from `config/offerwallWalls`, which is client-readable on purpose: a
 * wall's URL is handed to the user's own WebView, so it was never a secret.
 * The postback SECRETS live in `serverConfig/offerwall`, which no client can
 * read - see the note on OFFERWALL_SECRETS_COLLECTION.
 *
 * THE POINT OF THIS SHAPE IS THAT ADDING A NETWORK IS A DOCUMENT, NOT A
 * RELEASE. Every wall in the accessible tier - RevU, ayeT-Studios, Torox,
 * OfferToro, CPX, BitLabs - is a hosted web page you open with the user's id
 * in the query string. So the only thing that differs between them is a URL
 * template and a name, and both are data. When a network finally approves
 * you, the wall appears for every existing install without an update.
 */
data class OfferwallEntry(
    val id: String,
    val name: String,
    val subtitle: String,
    /**
     * The wall's URL with `{uid}` where the user's id belongs.
     *
     * A template rather than a base URL plus an appended parameter, because
     * networks disagree about the parameter's name and its position - some
     * want it in the path, most in the query, a few want it twice. A
     * template covers all of those without a per-network branch.
     */
    val urlTemplate: String,
    /**
     * How this wall opens.
     *
     * [TYPE_WEB] is everything sane - a hosted page in a WebView, needing no
     * code. [TYPE_TAPJOY] exists because Tapjoy is an SDK rather than a URL,
     * so it cannot be described by a link. Keeping it as a TYPE rather than
     * a separate screen means the two kinds share one list, one ordering and
     * one enable switch, and the fragment branches in exactly one place.
     */
    val type: String,
    /** Hidden below this level. 1 or absent means no gate. */
    val minLevel: Int,
    val sortOrder: Int,
) {
    /**
     * The URL to actually open.
     *
     * The uid is URL-encoded even though a Firebase uid is already
     * alphanumeric: the encoding is what makes this safe if the template
     * ever grows a second substitution carrying something less well-behaved,
     * and it costs nothing today.
     */
    fun urlFor(uid: String): String =
        urlTemplate.replace(PLACEHOLDER_UID, android.net.Uri.encode(uid))

    companion object {
        private const val PLACEHOLDER_UID = "{uid}"

        const val TYPE_WEB = "web"
        const val TYPE_TAPJOY = "tapjoy"

        /**
         * Parses one entry, or null if it could not be trusted.
         *
         * Strict, and for the same reason parseLevelRewards is: this
         * document is hand-edited in a console, and a half-typed wall must
         * not reach a user as a tile that opens a broken page. Silently
         * dropping the bad entry leaves the rest of the catalogue working,
         * which is the better failure - one missing tile beats a list that
         * refuses to draw.
         *
         * HTTPS IS REQUIRED, not merely preferred. The WebView that opens
         * this loads whatever it is handed, and a wall is a page the user
         * types into.
         */
        fun from(id: String, raw: Map<*, *>): OfferwallEntry? {
            if (raw["enabled"] != true) return null

            val type = (raw["type"] as? String)?.trim()?.lowercase() ?: TYPE_WEB

            // An SDK wall has no URL to validate; a web wall is nothing
            // without one. Checking per type rather than unconditionally is
            // what lets both live in the same document.
            val url = if (type == TYPE_TAPJOY) {
                ""
            } else {
                val raw_url = raw["urlTemplate"] as? String ?: return null
                if (!raw_url.startsWith("https://")) return null
                if (!raw_url.contains(PLACEHOLDER_UID)) return null
                raw_url
            }

            val name = (raw["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return null

            return OfferwallEntry(
                id = id,
                name = name,
                subtitle = (raw["subtitle"] as? String)?.trim().orEmpty(),
                urlTemplate = url,
                type = type,
                minLevel = (raw["minLevel"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
                sortOrder = (raw["sortOrder"] as? Number)?.toInt() ?: 0,
            )
        }

        /**
         * Every enabled wall in the document, ordered for display and NOT
         * filtered by level.
         *
         * The level filter is [visibleTo] rather than a parameter here
         * because two callers need the two halves separately: the Earn list
         * wants the walls this user may open, while the bottom bar wants to
         * know whether any exist at all. Parsing once and filtering twice
         * keeps a single definition of "enabled" - if the two drifted, the
         * tab could advertise a screen that renders empty.
         */
        fun parseAll(raw: Map<*, *>?): List<OfferwallEntry> {
            val walls = raw?.get("walls") as? Map<*, *> ?: return emptyList()
            return walls.entries
                .mapNotNull { (key, value) ->
                    val id = key as? String ?: return@mapNotNull null
                    val map = value as? Map<*, *> ?: return@mapNotNull null
                    from(id, map)
                }
                .sortedWith(compareBy({ it.sortOrder }, { it.name }))
        }

        /**
         * The subset [userLevel] may actually open.
         *
         * Level-gated entries are dropped rather than shown locked. A locked
         * offerwall teaches nothing - the user cannot influence it from this
         * screen and there is no progress bar to watch - so it would be a
         * row of dead weight on the one screen that has to look like it
         * pays.
         */
        fun visibleTo(walls: List<OfferwallEntry>, userLevel: Int): List<OfferwallEntry> =
            walls.filter { userLevel >= it.minLevel }
    }
}
