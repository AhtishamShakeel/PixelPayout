/**
 * Pure unit tests for the award builder - the single place points/xp/level
 * changes are computed. No emulator. Run via: npm run test:unit
 */
import {buildAward, buildMilestoneEvent} from "../economy/awardReward";
import {
  MULTIPLIER_ELIGIBLE,
  gameXpForScore,
  GAME_XP_PER_SESSION_CAP,
  LEVEL_UP_POINTS,
  MAX_DAILY_GAME_SESSIONS,
  MAX_DAILY_QUIZ_ATTEMPTS,
  levelUpPointsForLevels,
  parseLevelRewards,
} from "../economy/rewardConfig";
import {XP_THRESHOLDS, MAX_LEVEL, applyXpGain} from "../economy/levelCurve";

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

console.log("=== Award builder unit tests ===\n");

// --- XP-only sources never touch points ---
{
  const award = buildAward(500, 0, {
    source: "QUIZ", basePoints: 0, baseXp: 10, metadata: {},
  });
  assertEq("quiz awards no points", award.pointsAwarded, 0);
  assertEq("quiz awards xp", award.xpAwarded, 10);
  assertEq("quiz does not write a points field at all", "points" in award.userUpdate, false);
  assertEq("quiz writes an xp field", "xp" in award.userUpdate, true);
  assertEq("ledger records xpAwarded", award.ledgerDoc.xpAwarded, 10);
  assertEq("ledger records zero finalPoints", award.ledgerDoc.finalPoints, 0);
}

// --- level is only written when it actually changes ---
{
  const noLevelUp = buildAward(0, 0, {source: "QUIZ", basePoints: 0, baseXp: 5, metadata: {}});
  assertEq("no level-up -> level field not written", "level" in noLevelUp.userUpdate, false);
  assertEq("no level-up -> leveledUp false", noLevelUp.level.leveledUp, false);

  const levelUp = buildAward(0, XP_THRESHOLDS[0] - 1, {
    source: "QUIZ", basePoints: 0, baseXp: 1, metadata: {},
  });
  assertEq("crossing a threshold -> level field written", levelUp.userUpdate.level, 2);
  assertEq("crossing a threshold -> leveledUp true", levelUp.level.leveledUp, true);
  assertEq("ledger records level before the event", levelUp.ledgerDoc.levelAtEvent, 1);
  assertEq("ledger records level after the event", levelUp.ledgerDoc.levelAfterEvent, 2);
}

// --- a stale stored level is repaired by the next award ---
{
  // XP says level 4, but the document still says level 1. This happens
  // whenever the curve is retuned (every stored level goes stale at once),
  // and the repair must not wait for the user to cross a new threshold.
  const staleXp = XP_THRESHOLDS[2]; // enough for level 4
  const repaired = buildAward(0, staleXp, {
    source: "QUIZ", basePoints: 0, baseXp: 1, metadata: {}, storedLevel: 1,
  });
  assertEq("a stale stored level is corrected", repaired.userUpdate.level, 4);
  assertEq("no threshold was actually crossed", repaired.level.leveledUp, false);

  // The common case must stay a no-op: no level write when nothing changed.
  const agreed = buildAward(0, staleXp, {
    source: "QUIZ", basePoints: 0, baseXp: 1, metadata: {}, storedLevel: 4,
  });
  assertEq("an accurate stored level is left alone", "level" in agreed.userUpdate, false);

  // A stored level that is too HIGH must also come back down, or a bad write
  // would grant permanent unearned level gating.
  const tooHigh = buildAward(0, 10, {
    source: "QUIZ", basePoints: 0, baseXp: 1, metadata: {}, storedLevel: 25,
  });
  assertEq("an inflated stored level is corrected downwards", tooHigh.userUpdate.level, 1);

  // Omitting storedLevel keeps the original behaviour.
  const legacy = buildAward(0, staleXp, {
    source: "QUIZ", basePoints: 0, baseXp: 1, metadata: {},
  });
  assertEq("without a stored level, only real level-ups are written", "level" in legacy.userUpdate, false);
}

// --- multiplier eligibility comes from the source, never the caller ---
{
  // An active buff must NOT apply to an ineligible source, even when one is
  // passed in. This is the guard that keeps referrals/login rewards fixed.
  const referral = buildAward(0, 0, {
    source: "REFERRAL_REFEREE", basePoints: 50, baseXp: 25, metadata: {}, activeMultiplier: 3,
  });
  assertEq("referral points are not multiplied by an active buff", referral.pointsAwarded, 50);
  assertEq("referral ledger records multiplierApplied 1", referral.ledgerDoc.multiplierApplied, 1);
  assertEq("referral ledger records it as ineligible", referral.ledgerDoc.multiplierEligible, false);

  // An eligible source does apply it (nothing uses this yet, but the
  // mechanism must be correct before a real buff source exists).
  const offerwall = buildAward(0, 0, {
    source: "OFFERWALL", basePoints: 50, baseXp: 25, metadata: {}, activeMultiplier: 2,
  });
  assertEq("offerwall points are multiplied", offerwall.pointsAwarded, 100);
  assertEq("offerwall ledger records the multiplier used", offerwall.ledgerDoc.multiplierApplied, 2);
  assertEq("offerwall ledger keeps the pre-multiplier base", offerwall.ledgerDoc.basePoints, 50);

  // XP is never multiplied - that would compound into the level curve.
  assertEq("an active buff never multiplies xp", offerwall.xpAwarded, 25);
}

{
  const noBuff = buildAward(0, 0, {source: "OFFERWALL", basePoints: 50, baseXp: 0, metadata: {}});
  assertEq("eligible source with no active buff uses 1x", noBuff.pointsAwarded, 50);
  assertEq("eligible source is still flagged eligible in the ledger", noBuff.ledgerDoc.multiplierEligible, true);
}

// --- the eligibility table itself ---
assertEq("QUIZ is not multiplier eligible", MULTIPLIER_ELIGIBLE.QUIZ, false);
assertEq("GAME is not multiplier eligible", MULTIPLIER_ELIGIBLE.GAME, false);
assertEq("REFERRAL_REFEREE is not multiplier eligible", MULTIPLIER_ELIGIBLE.REFERRAL_REFEREE, false);
assertEq("REFERRAL_REFERRER is not multiplier eligible", MULTIPLIER_ELIGIBLE.REFERRAL_REFERRER, false);
assertEq("DAILY_LOGIN is not multiplier eligible", MULTIPLIER_ELIGIBLE.DAILY_LOGIN, false);
assertEq("OFFERWALL is multiplier eligible", MULTIPLIER_ELIGIBLE.OFFERWALL, true);
assertEq("SURVEY is multiplier eligible", MULTIPLIER_ELIGIBLE.SURVEY, true);
assertEq("SPONSORED_APP is multiplier eligible", MULTIPLIER_ELIGIBLE.SPONSORED_APP, true);

// --- redemptions (negative points) ---
{
  const spend = buildAward(500, 300, {
    source: "REDEMPTION", basePoints: -200, baseXp: 0, metadata: {}, activeMultiplier: 2,
  });
  assertEq("a redemption deducts points", spend.pointsAwarded, -200);
  assertEq("a redemption is never scaled by a buff", spend.ledgerDoc.multiplierApplied, 1);
  assertEq("a redemption awards no xp", spend.xpAwarded, 0);
  assertEq("a redemption never writes a level change", "level" in spend.userUpdate, false);
}

// --- defensive input handling ---
{
  const negativeXp = buildAward(0, 100, {source: "QUIZ", basePoints: 0, baseXp: -50, metadata: {}});
  assertEq("negative xp is clamped to 0 (xp must never decrease)", negativeXp.xpAwarded, 0);

  const fractional = buildAward(0, 0, {source: "QUIZ", basePoints: 0, baseXp: 10.7, metadata: {}});
  assertEq("fractional xp is truncated", fractional.xpAwarded, 10);

  const empty = buildAward(0, 0, {source: "QUIZ", basePoints: 0, baseXp: 0, metadata: {}});
  assertEq("a zero award writes no user fields", Object.keys(empty.userUpdate).length, 0);
  assertEq("a zero award still produces a ledger entry", empty.ledgerDoc.source, "QUIZ");
}

// --- game xp curve ---
assertEq("floppy_bird score 20 -> 20 xp", gameXpForScore("floppy_bird", 20), 20);
assertEq("tower score 500 (20 plain blocks) -> 20 xp", gameXpForScore("tower_game", 500), 20);
assertEq("game xp is capped per session", gameXpForScore("floppy_bird", 999_999), GAME_XP_PER_SESSION_CAP);
assertEq("tower xp is capped per session", gameXpForScore("tower_game", 999_999), GAME_XP_PER_SESSION_CAP);
assertEq("unknown game earns no xp", gameXpForScore("doom", 500), 0);
assertEq("zero score earns no xp", gameXpForScore("floppy_bird", 0), 0);
assertEq("negative score earns no xp", gameXpForScore("floppy_bird", -10), 0);
assertEq("a score below the divisor earns no xp", gameXpForScore("tower_game", 5), 0);
assertEq("the retired 2048 game earns no xp", gameXpForScore("game_2048", 400), 0);

// --- daily game allowance ---
// Pinned to the quiz allowance on purpose: the two counters share a day stamp
// on the user document, and a day's ceiling is meant to read as "10 and 10".
assertEq(
  "games get the same daily allowance as quizzes",
  MAX_DAILY_GAME_SESSIONS,
  MAX_DAILY_QUIZ_ATTEMPTS
);

// --- level rewards ---
assertEq("no levels crossed -> no rewards", levelUpPointsForLevels([]), []);
assertEq(
  "every level from 2 up pays something now",
  levelUpPointsForLevels([2, 3, 4]),
  [
    {level: 2, points: LEVEL_UP_POINTS[2]},
    {level: 3, points: LEVEL_UP_POINTS[3]},
    {level: 4, points: LEVEL_UP_POINTS[4]},
  ]
);

{
  // Level 1 is where every account STARTS, so it is never crossed and must
  // never be configured - an entry there would show on the Level rewards
  // screen and never pay.
  assertEq("level 1 is not in the reward table", LEVEL_UP_POINTS[1], undefined);
  const fromScratch = applyXpGain(0, 1);
  assertEq(
    "a brand new account crosses no level and is paid nothing",
    levelUpPointsForLevels(fromScratch.levelsCrossed),
    []
  );
}

{
  // The case that matters: one big XP grant (a future offerwall completion)
  // can jump several levels at once, and EVERY one must pay - not just the
  // level the user landed on.
  const crossed = levelUpPointsForLevels([4, 5, 6, 7, 8, 9, 10, 11]);
  assertEq("a multi-level jump pays every level crossed", crossed.length, 8);
  assertEq("multi-level jump starts at the first level crossed", crossed[0].level, 4);
  assertEq("multi-level jump ends at the last level crossed", crossed[7].level, 11);
  assertEq(
    "multi-level jump totals every bonus",
    crossed.reduce((sum: number, m) => sum + m.points, 0),
    [4, 5, 6, 7, 8, 9, 10, 11].reduce((sum, l) => sum + LEVEL_UP_POINTS[l], 0)
  );
}

{
  // The reward table is published to config/levelCurve and is editable in the
  // console, so the lookup has to honour a table that is NOT the deployed one.
  const retuned = levelUpPointsForLevels([2, 3], {2: 100, 3: 200});
  assertEq(
    "a caller-supplied table overrides the deployed one",
    retuned,
    [{level: 2, points: 100}, {level: 3, points: 200}]
  );
}

{
  // The ramp has to climb: a level that paid less than the one below it would
  // make the ladder read as a demotion.
  const levels = Object.keys(LEVEL_UP_POINTS).map(Number).sort((a, b) => a - b);
  assertEq("the reward table starts at level 2", levels[0], 2);
  assertEq("the reward table runs to MAX_LEVEL", levels[levels.length - 1], MAX_LEVEL);
  assertEq(
    "the reward table has no gaps between 2 and MAX_LEVEL",
    levels.length,
    MAX_LEVEL - 1
  );
  assertEq(
    "rewards never go down as levels go up",
    levels.every((l, i) => i === 0 || LEVEL_UP_POINTS[l] >= LEVEL_UP_POINTS[levels[i - 1]]),
    true
  );
}

// --- the console-editable reward table ---
// config/levelCurve.levelRewards is edited by hand, and whatever survives
// this function is paid out for real. Everything below is a shape somebody
// could plausibly type into the console by mistake.
assertEq(
  "a well-formed table is accepted",
  parseLevelRewards({"2": 5, "3": 6}, MAX_LEVEL),
  {2: 5, 3: 6}
);
assertEq("a missing table is rejected", parseLevelRewards(undefined, MAX_LEVEL), null);
assertEq("null is rejected", parseLevelRewards(null, MAX_LEVEL), null);
assertEq("an array is rejected", parseLevelRewards([5, 6], MAX_LEVEL), null);
assertEq("an empty table is rejected", parseLevelRewards({}, MAX_LEVEL), null);
assertEq(
  "a table of only zeroes is rejected rather than paying nothing",
  parseLevelRewards({"2": 0, "3": 0}, MAX_LEVEL),
  null
);
assertEq(
  "a zero entry is dropped but does not sink the table",
  parseLevelRewards({"2": 0, "3": 6}, MAX_LEVEL),
  {3: 6}
);
assertEq(
  "a negative payout is rejected whole",
  parseLevelRewards({"2": 5, "3": -6}, MAX_LEVEL),
  null
);
assertEq(
  "a fractional payout is rejected whole",
  parseLevelRewards({"2": 5, "3": 6.5}, MAX_LEVEL),
  null
);
assertEq(
  "a string payout is rejected whole - \"6\" is not 6",
  parseLevelRewards({"2": 5, "3": "6"}, MAX_LEVEL),
  null
);
assertEq(
  "a level above MAX_LEVEL is rejected whole",
  parseLevelRewards({"2": 5, [String(MAX_LEVEL + 1)]: 6}, MAX_LEVEL),
  null
);
assertEq(
  "level 0 is rejected whole",
  parseLevelRewards({"0": 5, "2": 5}, MAX_LEVEL),
  null
);
assertEq(
  "a non-numeric key is rejected whole",
  parseLevelRewards({two: 5}, MAX_LEVEL),
  null
);
assertEq(
  "an empty key is rejected rather than read as level 0",
  parseLevelRewards({"": 5}, MAX_LEVEL),
  null
);
assertEq(
  "a padded key is rejected rather than trimmed into a level",
  parseLevelRewards({" 3 ": 5}, MAX_LEVEL),
  null
);
assertEq(
  "the deployed table survives its own validator",
  parseLevelRewards(LEVEL_UP_POINTS, MAX_LEVEL),
  LEVEL_UP_POINTS
);

{
  // Sanity-check the ladder against the curve: crossing every level from 1 to
  // MAX_LEVEL must pay each configured level exactly once.
  const all = applyXpGain(0, XP_THRESHOLDS[XP_THRESHOLDS.length - 1]);
  const milestones = levelUpPointsForLevels(all.levelsCrossed);
  assertEq(
    "a full run of the curve pays every configured level",
    milestones.length,
    Object.keys(LEVEL_UP_POINTS).length
  );
  assertEq(
    "lifetime level-up cost per user matches the configured total",
    milestones.reduce((sum: number, m) => sum + m.points, 0),
    Object.values(LEVEL_UP_POINTS).reduce((sum: number, p) => sum + p, 0)
  );
  // The headline economy figure. Deliberately hardcoded: retuning the table
  // should fail here and make somebody re-state the lifetime cost on purpose.
  assertEq(
    "the full climb pays 1,282 stars",
    milestones.reduce((sum: number, m) => sum + m.points, 0),
    1282
  );
  assertEq(
    "no level reward is configured above MAX_LEVEL",
    Object.keys(LEVEL_UP_POINTS).every((l) => Number(l) <= MAX_LEVEL),
    true
  );
}

{
  const event = buildMilestoneEvent(10, 50);
  assertEq("milestone event uses the LEVEL_UP source", event.source, "LEVEL_UP");
  assertEq("milestone event awards points", event.finalPoints, 50);
  assertEq("milestone event awards no xp", event.xpAwarded, 0);
  assertEq("milestone event is not multiplier eligible", event.multiplierEligible, false);
  assertEq("milestone event records which level triggered it", (event.metadata as any).milestoneLevel, 10);
  assertEq("milestone event does not imply a further level change", event.levelAtEvent, event.levelAfterEvent);
}

// --- affectsPoints: what the wallet's Star activity list is indexed on ------
// This flag decides whether an entry is visible in the wallet at all, so it
// is asserted rather than trusted to stay in step with finalPoints.
{
  const xpOnly = buildAward(100, 50, {
    source: "QUIZ", basePoints: 0, baseXp: 12, metadata: {},
  });
  assertEq("an xp-only award is not a star movement", xpOnly.ledgerDoc.affectsPoints, false);
  assertEq("...and really did award no points", xpOnly.ledgerDoc.finalPoints, 0);

  const earned = buildAward(100, 50, {
    source: "STREAK", basePoints: 25, baseXp: 10, metadata: {},
  });
  assertEq("an award granting both currencies is a star movement",
    earned.ledgerDoc.affectsPoints, true);

  const spent = buildAward(1000, 50, {
    source: "REDEMPTION", basePoints: -300, baseXp: 0, metadata: {},
  });
  assertEq("spending is a star movement", spent.ledgerDoc.affectsPoints, true);

  const refund = buildAward(0, 50, {
    source: "REDEMPTION", basePoints: 300, baseXp: 0, metadata: {},
  });
  assertEq("a refund is a star movement", refund.ledgerDoc.affectsPoints, true);

  assertEq("a milestone bonus is a star movement",
    buildMilestoneEvent(10, 50).affectsPoints, true);
  assertEq("a zero-point milestone is not",
    buildMilestoneEvent(11, 0).affectsPoints, false);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
