/**
 * Pure unit tests for the award builder - the single place points/xp/level
 * changes are computed. No emulator. Run via: npm run test:unit
 */
import {buildAward, buildMilestoneEvent} from "../economy/awardReward";
import {
  MULTIPLIER_ELIGIBLE,
  gameXpForScore,
  GAME_XP_PER_SESSION_CAP,
  LEVEL_MILESTONE_POINTS,
  milestonePointsForLevels,
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
assertEq("2048 score 400 -> 20 xp", gameXpForScore("game_2048", 400), 20);
assertEq("game xp is capped per session", gameXpForScore("floppy_bird", 999_999), GAME_XP_PER_SESSION_CAP);
assertEq("2048 xp is capped per session", gameXpForScore("game_2048", 999_999), GAME_XP_PER_SESSION_CAP);
assertEq("unknown game earns no xp", gameXpForScore("doom", 500), 0);
assertEq("zero score earns no xp", gameXpForScore("floppy_bird", 0), 0);
assertEq("negative score earns no xp", gameXpForScore("floppy_bird", -10), 0);
assertEq("a score below the divisor earns no xp", gameXpForScore("game_2048", 5), 0);

// --- level milestones ---
assertEq("no levels crossed -> no milestones", milestonePointsForLevels([]), []);
assertEq("a non-milestone level pays nothing", milestonePointsForLevels([2, 3, 4]), []);
assertEq(
  "a milestone level pays its configured bonus",
  milestonePointsForLevels([5]),
  [{level: 5, points: LEVEL_MILESTONE_POINTS[5]}]
);

{
  // The case that matters: one big XP grant (a future offerwall completion)
  // can jump several milestones at once, and EVERY one must pay - not just
  // the level the user landed on.
  const crossed = milestonePointsForLevels([4, 5, 6, 7, 8, 9, 10, 11]);
  assertEq("a multi-level jump pays every milestone crossed", crossed.length, 2);
  assertEq("multi-level jump includes the first milestone", crossed[0].level, 5);
  assertEq("multi-level jump includes the later milestone", crossed[1].level, 10);
  assertEq(
    "multi-level jump totals both bonuses",
    crossed.reduce((s, m) => s + m.points, 0),
    LEVEL_MILESTONE_POINTS[5] + LEVEL_MILESTONE_POINTS[10]
  );
}

{
  // Sanity-check the ladder against the curve: crossing every level from 1 to
  // MAX_LEVEL must pay each configured milestone exactly once.
  const all = applyXpGain(0, XP_THRESHOLDS[XP_THRESHOLDS.length - 1]);
  const milestones = milestonePointsForLevels(all.levelsCrossed);
  assertEq(
    "a full run of the curve pays every configured milestone",
    milestones.length,
    Object.keys(LEVEL_MILESTONE_POINTS).length
  );
  assertEq(
    "lifetime milestone cost per user matches the configured total",
    milestones.reduce((s, m) => s + m.points, 0),
    Object.values(LEVEL_MILESTONE_POINTS).reduce((s, p) => s + p, 0)
  );
  assertEq(
    "no milestone is configured above MAX_LEVEL",
    Object.keys(LEVEL_MILESTONE_POINTS).every((l) => Number(l) <= MAX_LEVEL),
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

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
