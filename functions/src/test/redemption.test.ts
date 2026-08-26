/**
 * Pure unit tests for redemption validation. No emulator.
 * Run via: npm run test:unit
 */
import {
  validateRedemption,
  isPlausiblePayoutNumber,
  RedemptionOption,
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

const cashOption: RedemptionOption = {
  title: "Rs 100 Easypaisa",
  pointsCost: 1000,
  type: "EASYPAISA",
  enabled: true,
};

const gameOption: RedemptionOption = {
  title: "Game currency",
  pointsCost: 200,
  type: "GAME_CURRENCY",
  enabled: true,
};

// --- happy paths ---
assertEq(
  "affordable cash redemption with a number is allowed",
  validateRedemption({option: cashOption, userPoints: 1000, userLevel: 1, payoutNumber: "03001234567"}),
  {ok: true, pointsCost: 1000}
);
assertEq(
  "exact balance is enough (boundary)",
  validateRedemption({option: cashOption, userPoints: 1000, userLevel: 1, payoutNumber: "03001234567"}).ok,
  true
);
assertEq(
  "non-cash options need no payout number",
  validateRedemption({option: gameOption, userPoints: 500, userLevel: 1}).ok,
  true
);

// --- the money-losing cases ---
assertEq(
  "one point short is rejected",
  validateRedemption({option: cashOption, userPoints: 999, userLevel: 1, payoutNumber: "03001234567"}).rejection,
  "insufficient_points"
);
assertEq(
  "zero balance is rejected",
  validateRedemption({option: cashOption, userPoints: 0, userLevel: 1, payoutNumber: "03001234567"}).rejection,
  "insufficient_points"
);
assertEq(
  "a negative balance can never redeem",
  validateRedemption({option: cashOption, userPoints: -500, userLevel: 1, payoutNumber: "03001234567"}).rejection,
  "insufficient_points"
);

// --- option integrity ---
assertEq(
  "an unknown option is rejected",
  validateRedemption({option: null, userPoints: 99999, userLevel: 99}).rejection,
  "unknown_option"
);
assertEq(
  "a disabled option is rejected",
  validateRedemption({option: {...cashOption, enabled: false}, userPoints: 99999, userLevel: 9, payoutNumber: "03001234567"}).rejection,
  "option_disabled"
);
assertEq(
  "an option missing enabled is treated as disabled",
  validateRedemption({option: {...cashOption, enabled: undefined as never}, userPoints: 99999, userLevel: 9, payoutNumber: "03001234567"}).rejection,
  "option_disabled"
);
assertEq(
  "a zero-cost option is rejected (would be a free payout)",
  validateRedemption({option: {...gameOption, pointsCost: 0}, userPoints: 10, userLevel: 1}).rejection,
  "invalid_option"
);
assertEq(
  "a negative-cost option is rejected (would ADD points)",
  validateRedemption({option: {...gameOption, pointsCost: -500}, userPoints: 10, userLevel: 1}).rejection,
  "invalid_option"
);
assertEq(
  "a fractional cost is rejected",
  validateRedemption({option: {...gameOption, pointsCost: 10.5}, userPoints: 999, userLevel: 1}).rejection,
  "invalid_option"
);
assertEq(
  "a numeric string cost is coerced rather than rejected",
  validateRedemption({option: {...gameOption, pointsCost: "100" as never}, userPoints: 999, userLevel: 1}),
  {ok: true, pointsCost: 100}
);
assertEq(
  "a genuinely non-numeric cost is rejected",
  validateRedemption({option: {...gameOption, pointsCost: "free" as never}, userPoints: 999, userLevel: 1}).rejection,
  "invalid_option"
);
assertEq(
  "a missing cost is rejected",
  validateRedemption({option: {...gameOption, pointsCost: undefined as never}, userPoints: 999, userLevel: 1}).rejection,
  "invalid_option"
);

// --- level gating ---
assertEq(
  "an option above the user's level is rejected",
  validateRedemption({option: {...gameOption, minLevel: 10}, userPoints: 9999, userLevel: 9}).rejection,
  "level_too_low"
);
assertEq(
  "exactly meeting minLevel is allowed (boundary)",
  validateRedemption({option: {...gameOption, minLevel: 10}, userPoints: 9999, userLevel: 10}).ok,
  true
);
assertEq(
  "no minLevel means no gate",
  validateRedemption({option: gameOption, userPoints: 9999, userLevel: 1}).ok,
  true
);

// --- payout details ---
assertEq(
  "cash redemption without a number is rejected",
  validateRedemption({option: cashOption, userPoints: 9999, userLevel: 1}).rejection,
  "payout_details_required"
);
assertEq(
  "cash redemption with a blank number is rejected",
  validateRedemption({option: cashOption, userPoints: 9999, userLevel: 1, payoutNumber: "   "}).rejection,
  "payout_details_required"
);
assertEq(
  "cash redemption with a too-short number is rejected",
  validateRedemption({option: cashOption, userPoints: 9999, userLevel: 1, payoutNumber: "12345"}).rejection,
  "payout_details_required"
);
assertEq("a local-format number is plausible", isPlausiblePayoutNumber("03001234567"), true);
assertEq("an international-format number is plausible", isPlausiblePayoutNumber("+92 300 1234567"), true);
assertEq("letters are not a number", isPlausiblePayoutNumber("not-a-number"), false);
assertEq("an over-long number is rejected", isPlausiblePayoutNumber("1234567890123456789"), false);

// --- ordering: a rejection must be reported before the balance is considered ---
assertEq(
  "a disabled option is rejected even when affordable",
  validateRedemption({option: {...cashOption, enabled: false}, userPoints: 5, userLevel: 1}).rejection,
  "option_disabled"
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
