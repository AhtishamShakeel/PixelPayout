/**
 * Redemption: the first path that SPENDS Points rather than earning them.
 *
 * Four invariants this module exists to protect:
 *   1. The price is never taken from the client. The game and the pack are
 *      read from Firestore server-side and the stored pointsCost is charged.
 *   2. Spending Points can never touch XP or level. Progression is not for
 *      sale and is not lost by cashing out.
 *   3. A game player ID belongs to exactly one account. See the note on
 *      linking below - this is the anti-farming rule.
 *   4. The discounted first redeem is available once, ever, per account.
 *
 * Creating a redemption records a PENDING request and debits the balance;
 * actually paying out is a separate, human/business process.
 *
 * SHAPE: one document per GAME, with its denominations nested as `packs`.
 * The catalogue used to be one document per purchasable item, which meant
 * four near-identical documents to add one game and no way for the app to
 * know they belonged together. Adding a game is now a single document, and
 * adding a denomination to an existing game is a single nested field.
 */

export const REDEMPTION_OPTIONS_COLLECTION = "redemptionOptions";

/**
 * Top-level rather than a subcollection under each user, so every pending
 * payout can be listed in one place - both in the Firebase console and in the
 * admin tool. Each document carries the owner's uid instead of relying on its
 * path for ownership.
 */
export const REDEMPTIONS_COLLECTION = "redemptions";

/**
 * What is known about a game player ID: `{gameId}__{playerId}`.
 *
 * IT NO LONGER BLOCKS A REDEMPTION. This used to be a hard claim - the first
 * account to redeem into a player ID owned it, and any other account was
 * refused outright. That rule was too blunt in the case it hit most often:
 * one person with two sign-ins, or a phone handed to a sibling, topping up
 * the same game account. Being told "this UID belongs to someone else" about
 * your own game account is indistinguishable from a broken app.
 *
 * What it carries now is `firstRedeemUsed`, which is narrower and is aimed at
 * the thing actually worth defending: the discounted first pack. Ordinary
 * redeems are open to any account, because they are paid for at full price
 * and there is nothing to farm.
 *
 * A deterministic document id rather than a query, because the check and the
 * mark have to happen inside the redemption transaction - two devices
 * claiming the discount on the same fresh ID at the same moment must not both
 * win, and only a read of a known document id can be part of a transaction.
 *
 * Server-only in firestore.rules. A client that could read this could
 * enumerate which player IDs have spent their discount, which is exactly the
 * reconnaissance the rule exists to prevent.
 */
export const PLAYER_LINKS_COLLECTION = "playerLinks";

/**
 * The user's own copy of what they last entered per game, for prefilling the
 * form. Separate from PLAYER_LINKS because this one IS readable by its owner
 * and carries no information about anybody else.
 */
export const GAME_PROFILES_SUBCOLLECTION = "gameProfiles";

/** Config doc holding the tunables that should not need a redeploy. */
export const REDEMPTION_CONFIG_DOC = "redemption";

/** Shortest player ID any game will accept, when the game says nothing. */
export const DEFAULT_ID_MIN_LENGTH = 4;

export type RedemptionStatus = "pending" | "approved" | "rejected";

/** One denomination of one game's currency. */
export interface RedemptionPack {
  /** What the player receives, as text: "325 UC", "310 Diamonds". */
  amount: string;
  pointsCost: number;
  /** Supporting line under the amount ("Best value per point"). */
  note?: string;
  /** Small badge ("Popular"). Display only. */
  tag?: string;
  /**
   * A pack is live unless this is explicitly false.
   *
   * The opposite default to [RedemptionGame.enabled], deliberately: a pack
   * only exists because someone typed it into a game document that itself had
   * to be explicitly enabled, so the "half-written document goes on sale"
   * risk is already covered one level up. Requiring `enabled: true` on every
   * nested pack would be four extra fields per game for no extra safety, and
   * a malformed pointsCost is still rejected outright below.
   */
  enabled?: boolean;
  sortOrder?: number;
  /**
   * Presence marks this pack as eligible for the once-per-account discounted
   * first redeem, and carries the discounted price.
   *
   * A field on the pack rather than a separate offers collection: the set of
   * packs on offer is then visible exactly where the packs are, and putting a
   * game into the offer is one field rather than a second document that can
   * drift out of step with the first.
   */
  firstRedeemCost?: number;
  /**
   * This pack exists ONLY as the first-redeem offer. It is not sold at list
   * price and does not appear in the game's pack list.
   *
   * WHY IT IS NEEDED: the offer used to point at the cheapest ordinary pack -
   * 30 UC - which tied two unrelated decisions together. Raising the ordinary
   * minimum to 60 UC meant deleting the 30 UC pack, and deleting it took the
   * offer with it; keeping the offer meant keeping a 30 UC pack on sale. This
   * separates them: the taster is its own pack, reachable only through the
   * offer, and the ordinary floor moves independently of it.
   *
   * `pointsCost` is still required and still has to be a sane figure - it is
   * what the pack WOULD cost, and validateRedemption uses it as the ceiling
   * the discount cannot exceed - but it is never actually charged, because
   * buying this pack without the discount is refused.
   *
   * A pack marked this way with no `firstRedeemCost` is unreachable: it is
   * excluded from the ordinary list by the flag and from the offer by the
   * missing price. That is a console mistake rather than a state to handle.
   */
  firstRedeemOnly?: boolean;
}

/** One game, and everything the redeem flow needs to ask for. */
export interface RedemptionGame {
  /**
   * The game's own name ("PUBG Mobile").
   *
   * INTERNAL AND ADMIN-FACING ONLY. It is written onto every order so a
   * payout can be actioned by hand, and it is what the admin tool lists - but
   * nothing in the app draws it any more. The tiles and the sheet show
   * [currencyName] instead; see the note there.
   */
  name: string;
  /** Two or three characters for the tile ("UC", "FF", "ML"). */
  code?: string;
  /**
   * What the player actually receives, named as the game names it: "UC",
   * "Diamonds", "CP", "Coins".
   *
   * THIS IS WHAT THE APP SHOWS, not [name]. Printing "PUBG Mobile" or "Free
   * Fire" beside artwork of a character from those games is us using someone
   * else's trade mark to sell something; naming the currency is a plain
   * description of the goods. The store listing is the risk being managed
   * here, and it is cheap to avoid.
   *
   * A separate field rather than reusing [code] because the two answer
   * different questions - code is a two-letter tag for a cramped tile, this
   * is the word a player would use. Absent falls back to code, then to name,
   * so a catalogue written before this field existed still renders.
   */
  currencyName?: string;
  /** One line under the name in the sheet header. */
  subtitle?: string;
  enabled: boolean;
  packs?: Record<string, RedemptionPack>;
  /** Level gate for the whole game; 1 or absent means no gate. */
  minLevel?: number;
  sortOrder?: number;
  imageUrl?: string;
  /**
   * Artwork for the Wallet screen itself - the star pile on the balance card
   * and the gift on the first-redeem card.
   *
   * Carried on the catalogue rather than in a config document of their own so
   * the screen still costs no reads beyond the one it already makes. They
   * belong to the screen, not to the game whose document holds them: set them
   * on ONE document and leave them off the rest. The app takes the first game
   * in sort order that defines one.
   */
  walletHeroUrl?: string;
  firstRedeemArtUrl?: string;
  /** Label and help text for the player ID field. */
  idLabel?: string;
  idHint?: string;
  idMinLength?: number;
  /** Whether this game also needs the in-game display name. */
  requiresUsername?: boolean;
  usernameLabel?: string;
  /** Region choices. Empty or absent means the game has no server concept. */
  servers?: string[];
}

export type RedemptionRejection =
  | "unknown_option"
  | "option_disabled"
  | "unknown_pack"
  | "pack_disabled"
  | "invalid_option"
  | "level_too_low"
  | "player_id_required"
  | "username_required"
  | "server_required"
  | "pack_first_redeem_only"
  | "first_redeem_used"
  | "first_redeem_uid_used"
  | "first_redeem_unavailable"
  | "insufficient_points";

export interface RedemptionValidation {
  ok: boolean;
  rejection?: RedemptionRejection;
  /** The authoritative cost, read from the pack - never from the client. */
  pointsCost?: number;
  /** Echoed back so the caller can denormalise it onto the order. */
  packAmount?: string;
  /** The server actually used, after defaulting. */
  server?: string;
  /** True when this consumed the once-per-account discount. */
  usedFirstRedeem?: boolean;
}

/** Light sanity check on a game player ID. Deliberately permissive. */
export function isPlausiblePlayerId(raw: string, minLength: number): boolean {
  const trimmed = raw.trim();
  // Player IDs are numeric in most games but not all, so this checks shape
  // rather than digits: no spaces, nothing exotic, a sane length.
  return (
    trimmed.length >= minLength &&
    trimmed.length <= 32 &&
    /^[A-Za-z0-9_()-]+$/.test(trimmed)
  );
}

function normalisedPacks(
  game: RedemptionGame
): Array<{ id: string; pack: RedemptionPack }> {
  const packs = game.packs;
  if (!packs || typeof packs !== "object") return [];
  return Object.keys(packs)
    .filter((id) => packs[id] && typeof packs[id] === "object")
    .map((id) => ({id, pack: packs[id]}));
}

/** Whether a pack is currently purchasable at all. */
export function isPackLive(pack: RedemptionPack): boolean {
  if (pack.enabled === false) return false;
  const cost = Number(pack.pointsCost);
  return Number.isFinite(cost) && cost > 0 && Number.isInteger(cost);
}

/**
 * The packs eligible for the discounted first redeem, across every game.
 * Used to build the offer sheet, and to reject a first-redeem attempt on a
 * pack that is not part of the offer.
 */
export function firstRedeemPacks(
  games: Array<{ id: string; game: RedemptionGame }>
): Array<{ gameId: string; packId: string; pack: RedemptionPack }> {
  const out: Array<{ gameId: string; packId: string; pack: RedemptionPack }> = [];
  for (const {id: gameId, game} of games) {
    if (game.enabled !== true) continue;
    for (const {id: packId, pack} of normalisedPacks(game)) {
      if (!isPackLive(pack)) continue;
      const discounted = Number(pack.firstRedeemCost);
      if (!Number.isFinite(discounted) || discounted < 0) continue;
      out.push({gameId, packId, pack});
    }
  }
  return out;
}

export function validateRedemption(input: {
  game: RedemptionGame | null;
  packId: string;
  userPoints: number;
  userLevel: number;
  playerId?: string;
  username?: string;
  server?: string;
  /** Caller asked to spend the once-per-account discount on this order. */
  useFirstRedeem?: boolean;
  /** This ACCOUNT has already spent its discount. */
  hasUsedFirstRedeem?: boolean;
  /**
   * This PLAYER ID has already had a discounted first pack delivered into it,
   * by any account. Read inside the transaction by the caller, off the
   * playerLinks document.
   *
   * The two are different questions and both have to be asked. The account
   * flag stops one sign-in taking the offer twice; this stops one person
   * taking it once per sign-in, which is the same offer farmed through a
   * second Gmail into the same game account.
   */
  firstRedeemUidUsed?: boolean;
  callerUid: string;
}): RedemptionValidation {
  const {game} = input;

  if (!game) return {ok: false, rejection: "unknown_option"};
  if (game.enabled !== true) return {ok: false, rejection: "option_disabled"};

  const packs = game.packs;
  const pack = packs && typeof packs === "object" ? packs[input.packId] : undefined;
  if (!pack || typeof pack !== "object") {
    return {ok: false, rejection: "unknown_pack"};
  }
  if (pack.enabled === false) return {ok: false, rejection: "pack_disabled"};

  const listPrice = Number(pack.pointsCost);
  if (!Number.isFinite(listPrice) || listPrice <= 0 || !Number.isInteger(listPrice)) {
    return {ok: false, rejection: "invalid_option"};
  }

  // Level gate before anything the user typed, so someone who cannot buy this
  // yet is told that rather than being sent to fix an ID that was fine.
  const minLevel = Number(game.minLevel ?? 1);
  if (Number.isFinite(minLevel) && input.userLevel < minLevel) {
    return {ok: false, rejection: "level_too_low"};
  }

  const playerId = (input.playerId || "").trim();
  const idMinLength = Number(game.idMinLength ?? DEFAULT_ID_MIN_LENGTH);
  if (!isPlausiblePlayerId(playerId, Number.isFinite(idMinLength) ? idMinLength : DEFAULT_ID_MIN_LENGTH)) {
    return {ok: false, rejection: "player_id_required"};
  }

  if (game.requiresUsername === true) {
    const username = (input.username || "").trim();
    if (username.length < 2 || username.length > 60) {
      return {ok: false, rejection: "username_required"};
    }
  }

  const servers = Array.isArray(game.servers) ? game.servers.filter(Boolean) : [];
  let server = "";
  if (servers.length > 0) {
    const asked = (input.server || "").trim();
    // An unrecognised server is treated as none given rather than silently
    // corrected: the order is delivered by hand, and a wrong region is a
    // payout into the wrong account.
    if (!asked || !servers.includes(asked)) {
      return {ok: false, rejection: "server_required"};
    }
    server = asked;
  }

  // NO UID OWNERSHIP CHECK HERE ANY MORE. A player ID redeemed into by one
  // account used to be closed to every other account, forever. It caught real
  // farming, but it also caught the ordinary case - one person, two sign-ins,
  // one game account - and told them their own UID belonged to a stranger.
  // Full-price redeems are now open: they are paid for, so there is nothing
  // to farm. The UID rule that survives is the one just below, and it guards
  // only the thing that is actually given away.

  // An offer-only pack is not for sale at list price. Checked here rather
  // than trusted to the client not to offer it: the pack id travels in the
  // request, so anyone can name the cheap taster on an ordinary redeem and
  // would otherwise be sold it at whatever pointsCost happens to say.
  if (pack.firstRedeemOnly === true && input.useFirstRedeem !== true) {
    return {ok: false, rejection: "pack_first_redeem_only"};
  }

  // The discount decides the price, so it is resolved before the balance.
  let pointsCost = listPrice;
  let usedFirstRedeem = false;

  if (input.useFirstRedeem === true) {
    const discounted = Number(pack.firstRedeemCost);
    if (!Number.isFinite(discounted) || discounted < 0 || !Number.isInteger(discounted)) {
      return {ok: false, rejection: "first_redeem_unavailable"};
    }
    if (input.hasUsedFirstRedeem === true) {
      return {ok: false, rejection: "first_redeem_used"};
    }
    // ONE DISCOUNTED PACK PER GAME ACCOUNT, not per sign-in. Checked after
    // the account's own flag so a user who simply already used their offer is
    // told that plainly, rather than being told something about a UID.
    //
    // NO LEVEL GATE. The offer used to unlock at level 10, which meant the
    // one thing designed to prove the payout works was withheld until long
    // after a new user had decided whether to trust it.
    if (input.firstRedeemUidUsed === true) {
      return {ok: false, rejection: "first_redeem_uid_used"};
    }
    // The discount can only ever lower the price. A firstRedeemCost typed
    // above the list price is a mistake in the console, not an upcharge.
    pointsCost = Math.min(discounted, listPrice);
    usedFirstRedeem = true;
  }

  if (input.userPoints < pointsCost) {
    return {ok: false, rejection: "insufficient_points", pointsCost};
  }

  return {
    ok: true,
    pointsCost,
    packAmount: String(pack.amount ?? ""),
    server,
    usedFirstRedeem,
  };
}

/** The `playerLinks` document id for a (game, player ID) pair. */
export function playerLinkId(gameId: string, playerId: string): string {
  // Lower-cased so "AbC123" and "abc123" cannot be used as two identities for
  // what the game itself treats as one account.
  return `${gameId}__${playerId.trim().toLowerCase()}`;
}
