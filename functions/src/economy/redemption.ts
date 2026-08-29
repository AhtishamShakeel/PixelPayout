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
 * The claim on a game player ID: `{gameId}__{playerId}` -> the uid that first
 * redeemed into it.
 *
 * A deterministic document id rather than a query, because the check and the
 * claim have to happen inside the redemption transaction - two devices
 * redeeming into the same fresh ID at the same moment must not both win, and
 * only a read of a known document id can be part of a transaction.
 *
 * Server-only in firestore.rules. A client that could read this could
 * enumerate which player IDs are taken, which is exactly the reconnaissance
 * the rule exists to prevent.
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

/** Level required for the discounted first redeem, when config says nothing. */
export const DEFAULT_FIRST_REDEEM_MIN_LEVEL = 10;

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
}

/** One game, and everything the redeem flow needs to ask for. */
export interface RedemptionGame {
  name: string;
  /** Two or three characters for the tile ("UC", "FF", "ML"). */
  code?: string;
  /** One line under the name in the sheet header. */
  subtitle?: string;
  enabled: boolean;
  packs?: Record<string, RedemptionPack>;
  /** Level gate for the whole game; 1 or absent means no gate. */
  minLevel?: number;
  sortOrder?: number;
  imageUrl?: string;
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
  | "uid_linked_to_another_account"
  | "first_redeem_used"
  | "first_redeem_level_too_low"
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
  hasUsedFirstRedeem?: boolean;
  firstRedeemMinLevel?: number;
  /**
   * The uid already linked to (this game, this player ID), or null if the ID
   * has never been redeemed into. Read inside the transaction by the caller.
   */
  linkedUid?: string | null;
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

  // The anti-farming rule. Checked before the balance so the message the user
  // sees names the real problem: a second account cannot pay its way past a
  // linked ID no matter how many points it has.
  const linkedUid = input.linkedUid ?? null;
  if (linkedUid && linkedUid !== input.callerUid) {
    return {ok: false, rejection: "uid_linked_to_another_account"};
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
    const giftLevel = Number(input.firstRedeemMinLevel ?? DEFAULT_FIRST_REDEEM_MIN_LEVEL);
    const requiredLevel = Number.isFinite(giftLevel) ?
      giftLevel :
      DEFAULT_FIRST_REDEEM_MIN_LEVEL;
    if (input.userLevel < requiredLevel) {
      return {ok: false, rejection: "first_redeem_level_too_low"};
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
