/**
 * Pure unit tests for redemption validation. No emulator.
 * Run via: npm run test:unit
 */
import {
  RedemptionGame,
  firstRedeemPacks,
  isPlausiblePlayerId,
  playerLinkId,
  validateRedemption,
} from "../economy/redemption";
import {buildAward} from "../economy/awardReward";

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(`  FAIL  ${desc}${detail !== undefined ? " -- " + JSON.stringify(detail) : ""}`);
}

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok(desc);
  } else {
    fail(desc, `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

console.log("=== Redemption unit tests ===\n");

const pubg: RedemptionGame = {
  name: "PUBG Mobile",
  code: "UC",
  enabled: true,
  requiresUsername: false,
  servers: ["Global", "Korea"],
  packs: {
    uc_60: {amount: "60 UC", pointsCost: 1200, firstRedeemCost: 300},
    uc_325: {amount: "325 UC", pointsCost: 5800, tag: "Popular"},
    uc_off: {amount: "660 UC", pointsCost: 11000, enabled: false},
  },
};

/** A game with no server concept and a required in-game name. */
const mlbb: RedemptionGame = {
  name: "Mobile Legends",
  code: "ML",
  enabled: true,
  requiresUsername: true,
  packs: {d_86: {amount: "86 Diamonds", pointsCost: 1300}},
};

const base = {
  packId: "uc_325",
  userPoints: 99999,
  userLevel: 50,
  playerId: "5218840977",
  server: "Global",
  callerUid: "user-a",
};

// --- happy paths ---
assertEq(
  "an affordable pack with a valid id is allowed",
  validateRedemption({...base, game: pubg}),
  {ok: true, pointsCost: 5800, packAmount: "325 UC", server: "Global", usedFirstRedeem: false}
);
assertEq(
  "exact balance is enough (boundary)",
  validateRedemption({...base, game: pubg, userPoints: 5800}).ok,
  true
);
assertEq(
  "a game with no servers needs no server",
  validateRedemption({
    ...base, game: mlbb, packId: "d_86", server: "", username: "AhmedX",
  }),
  {ok: true, pointsCost: 1300, packAmount: "86 Diamonds", server: "", usedFirstRedeem: false}
);

// --- the money-losing cases ---
assertEq(
  "one point short is rejected",
  validateRedemption({...base, game: pubg, userPoints: 5799}).rejection,
  "insufficient_points"
);
assertEq(
  "zero balance is rejected",
  validateRedemption({...base, game: pubg, userPoints: 0}).rejection,
  "insufficient_points"
);
assertEq(
  "a negative balance can never redeem",
  validateRedemption({...base, game: pubg, userPoints: -500}).rejection,
  "insufficient_points"
);

// --- catalogue integrity ---
assertEq(
  "an unknown game is rejected",
  validateRedemption({...base, game: null}).rejection,
  "unknown_option"
);
assertEq(
  "a disabled game is rejected",
  validateRedemption({...base, game: {...pubg, enabled: false}}).rejection,
  "option_disabled"
);
assertEq(
  "a game missing enabled is treated as disabled",
  validateRedemption({...base, game: {...pubg, enabled: undefined as never}}).rejection,
  "option_disabled"
);
assertEq(
  "an unknown pack is rejected",
  validateRedemption({...base, game: pubg, packId: "uc_9999"}).rejection,
  "unknown_pack"
);
assertEq(
  "a game with no packs at all is rejected",
  validateRedemption({...base, game: {...pubg, packs: undefined}}).rejection,
  "unknown_pack"
);
assertEq(
  "an explicitly disabled pack is rejected",
  validateRedemption({...base, game: pubg, packId: "uc_off"}).rejection,
  "pack_disabled"
);
assertEq(
  "a pack without enabled is live (opposite default to the game)",
  validateRedemption({...base, game: pubg, packId: "uc_325"}).ok,
  true
);
assertEq(
  "a zero-cost pack is rejected (would be a free payout)",
  validateRedemption({
    ...base, game: {...pubg, packs: {p: {amount: "x", pointsCost: 0}}}, packId: "p",
  }).rejection,
  "invalid_option"
);
assertEq(
  "a negative-cost pack is rejected (would ADD points)",
  validateRedemption({
    ...base, game: {...pubg, packs: {p: {amount: "x", pointsCost: -500}}}, packId: "p",
  }).rejection,
  "invalid_option"
);
assertEq(
  "a fractional cost is rejected",
  validateRedemption({
    ...base, game: {...pubg, packs: {p: {amount: "x", pointsCost: 10.5}}}, packId: "p",
  }).rejection,
  "invalid_option"
);
assertEq(
  "a numeric string cost is coerced rather than rejected",
  validateRedemption({
    ...base, game: {...pubg, packs: {p: {amount: "x", pointsCost: "100" as never}}}, packId: "p",
  }).pointsCost,
  100
);
assertEq(
  "a genuinely non-numeric cost is rejected",
  validateRedemption({
    ...base, game: {...pubg, packs: {p: {amount: "x", pointsCost: "free" as never}}}, packId: "p",
  }).rejection,
  "invalid_option"
);

// --- level gating ---
assertEq(
  "a game above the user's level is rejected",
  validateRedemption({...base, game: {...pubg, minLevel: 10}, userLevel: 9}).rejection,
  "level_too_low"
);
assertEq(
  "exactly meeting minLevel is allowed (boundary)",
  validateRedemption({...base, game: {...pubg, minLevel: 10}, userLevel: 10}).ok,
  true
);
assertEq(
  "no minLevel means no gate",
  validateRedemption({...base, game: pubg, userLevel: 1}).ok,
  true
);

// --- player id / username / server ---
assertEq(
  "a missing player id is rejected",
  validateRedemption({...base, game: pubg, playerId: ""}).rejection,
  "player_id_required"
);
assertEq(
  "a whitespace player id is rejected",
  validateRedemption({...base, game: pubg, playerId: "   "}).rejection,
  "player_id_required"
);
assertEq(
  "a too-short player id is rejected",
  validateRedemption({...base, game: pubg, playerId: "12"}).rejection,
  "player_id_required"
);
assertEq(
  "a per-game idMinLength is honoured",
  validateRedemption({...base, game: {...pubg, idMinLength: 9}, playerId: "12345678"}).rejection,
  "player_id_required"
);
assertEq(
  "a username is required only when the game asks for one",
  validateRedemption({...base, game: mlbb, packId: "d_86", username: ""}).rejection,
  "username_required"
);
assertEq(
  "a one-character username is rejected",
  validateRedemption({...base, game: mlbb, packId: "d_86", username: "A"}).rejection,
  "username_required"
);
assertEq(
  "a server is required when the game has servers",
  validateRedemption({...base, game: pubg, server: ""}).rejection,
  "server_required"
);
assertEq(
  "an unrecognised server is rejected rather than silently corrected",
  validateRedemption({...base, game: pubg, server: "Atlantis"}).rejection,
  "server_required"
);
assertEq("a numeric id is plausible", isPlausiblePlayerId("5218840977", 4), true);
assertEq("an alphanumeric id is plausible", isPlausiblePlayerId("Abc123", 4), true);
assertEq("an id with a space is not plausible", isPlausiblePlayerId("521 884", 4), false);
assertEq("an over-long id is rejected", isPlausiblePlayerId("1".repeat(33), 4), false);

// --- the anti-farming rule ---
assertEq(
  "a player id linked to another account is refused",
  validateRedemption({...base, game: pubg, linkedUid: "user-b"}).rejection,
  "uid_linked_to_another_account"
);
assertEq(
  "the owner may redeem into their own linked id again",
  validateRedemption({...base, game: pubg, linkedUid: "user-a"}).ok,
  true
);
assertEq(
  "an unclaimed id is allowed",
  validateRedemption({...base, game: pubg, linkedUid: null}).ok,
  true
);
assertEq(
  "a linked id is refused even when the caller could afford it many times over",
  validateRedemption({...base, game: pubg, linkedUid: "user-b", userPoints: 9999999}).rejection,
  "uid_linked_to_another_account"
);
assertEq(
  "the link id is case-insensitive so one game account is one identity",
  playerLinkId("pubg", "AbC123"),
  playerLinkId("pubg", "abc123")
);
assertEq("the link id namespaces by game", playerLinkId("pubg", "1"), "pubg__1");

// --- the discounted first redeem ---
const firstBase = {...base, packId: "uc_60", useFirstRedeem: true, userLevel: 10};
assertEq(
  "the first redeem charges the discounted price",
  validateRedemption({...firstBase, game: pubg, userPoints: 300}),
  {ok: true, pointsCost: 300, packAmount: "60 UC", server: "Global", usedFirstRedeem: true}
);
assertEq(
  "level 10 exactly is allowed (boundary)",
  validateRedemption({...firstBase, game: pubg, userLevel: 10}).ok,
  true
);
assertEq(
  "below level 10 the discount is refused",
  validateRedemption({...firstBase, game: pubg, userLevel: 9}).rejection,
  "first_redeem_level_too_low"
);
assertEq(
  "the level gate is configurable",
  validateRedemption({
    ...firstBase, game: pubg, userLevel: 14, firstRedeemMinLevel: 15,
  }).rejection,
  "first_redeem_level_too_low"
);
assertEq(
  "a second discounted redeem is refused",
  validateRedemption({...firstBase, game: pubg, hasUsedFirstRedeem: true}).rejection,
  "first_redeem_used"
);
assertEq(
  "a pack outside the offer cannot be discounted",
  validateRedemption({...firstBase, game: pubg, packId: "uc_325"}).rejection,
  "first_redeem_unavailable"
);
assertEq(
  "the discount can only lower the price, never raise it",
  validateRedemption({
    ...firstBase,
    game: {...pubg, packs: {p: {amount: "x", pointsCost: 500, firstRedeemCost: 900}}},
    packId: "p",
    userPoints: 600,
  }).pointsCost,
  500
);
assertEq(
  "the discounted price is still charged, not waived",
  validateRedemption({...firstBase, game: pubg, userPoints: 299}).rejection,
  "insufficient_points"
);
assertEq(
  "not asking for the discount pays list price",
  validateRedemption({...base, game: pubg, packId: "uc_60"}).pointsCost,
  1200
);
assertEq(
  "the offer lists only packs carrying a discount, from enabled games",
  firstRedeemPacks([
    {id: "pubg", game: pubg},
    {id: "off", game: {...pubg, enabled: false}},
    {id: "mlbb", game: mlbb},
  ]).map((p) => `${p.gameId}/${p.packId}`),
  ["pubg/uc_60"]
);

// --- ordering: config and identity problems are reported before the balance ---
assertEq(
  "a disabled game is rejected even when affordable",
  validateRedemption({...base, game: {...pubg, enabled: false}, userPoints: 5}).rejection,
  "option_disabled"
);
assertEq(
  "a bad player id is reported before an insufficient balance",
  validateRedemption({...base, game: pubg, playerId: "1", userPoints: 0}).rejection,
  "player_id_required"
);
assertEq(
  "a linked id is reported before an insufficient balance",
  validateRedemption({...base, game: pubg, linkedUid: "user-b", userPoints: 0}).rejection,
  "uid_linked_to_another_account"
);

// --- spending must never touch progression ---
{
  const spend = buildAward(1000, 500, {
    source: "REDEMPTION", basePoints: -1000, baseXp: 0, metadata: {},
  });
  assertEq("spending debits the points", spend.pointsAwarded, -1000);
  assertEq("spending awards no xp", spend.xpAwarded, 0);
  assertEq("spending never writes an xp field", "xp" in spend.userUpdate, false);
  assertEq("spending never writes a level field", "level" in spend.userUpdate, false);
  assertEq("the user's level is unchanged by spending", spend.level.level, spend.level.previousLevel);
  assertEq("spending is recorded as a REDEMPTION", spend.ledgerDoc.source, "REDEMPTION");
  assertEq("the ledger records a negative amount", spend.ledgerDoc.finalPoints, -1000);
}

// --- a refund is the mirror image ---
{
  const refund = buildAward(0, 500, {
    source: "REDEMPTION", basePoints: 1000, baseXp: 0, metadata: {},
  });
  assertEq("a refund credits the points back", refund.pointsAwarded, 1000);
  assertEq("a refund awards no xp", refund.xpAwarded, 0);
  assertEq("a refund never changes the level", "level" in refund.userUpdate, false);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
