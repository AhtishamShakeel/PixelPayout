/**
 * Pure unit tests for the temporary Points buff. No emulator.
 * Run via: npm run test:unit
 */
import {
  activeMultiplier,
  isBuffActive,
  resolveBuffGrant,
  MAX_BUFF_MULTIPLIER,
  MAX_BUFF_DURATION_MS,
  PointsBuff,
} from "../economy/pointsBuff";
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

console.log("=== Points buff unit tests ===\n");

const NOW = 1_000_000_000_000;
const buff = (multiplier: number, expiresAt: number): PointsBuff => ({
  multiplier,
  expiresAt,
  grantedAt: NOW,
  source: "ADMIN_GRANT",
});

// --- activeMultiplier: expiry is evaluated on read ---
assertEq("no buff -> 1x", activeMultiplier(null, NOW), 1);
assertEq("undefined buff -> 1x", activeMultiplier(undefined, NOW), 1);
assertEq("active buff -> its multiplier", activeMultiplier(buff(2, NOW + 60_000), NOW), 2);
assertEq("expired buff -> 1x", activeMultiplier(buff(2, NOW - 1), NOW), 1);
assertEq("buff expiring exactly now -> 1x", activeMultiplier(buff(2, NOW), NOW), 1);
assertEq("buff expiring 1ms from now -> still active", activeMultiplier(buff(2, NOW + 1), NOW), 2);
assertEq("a 1x buff is not a buff", activeMultiplier(buff(1, NOW + 60_000), NOW), 1);
assertEq("a malformed multiplier -> 1x", activeMultiplier(buff(NaN, NOW + 60_000), NOW), 1);
assertEq("a malformed expiry -> 1x", activeMultiplier(buff(2, NaN), NOW), 1);
assertEq(
  "a stored multiplier above the cap is clamped on read",
  activeMultiplier(buff(999, NOW + 60_000), NOW),
  MAX_BUFF_MULTIPLIER
);
assertEq("isBuffActive agrees with activeMultiplier", isBuffActive(buff(2, NOW + 60_000), NOW), true);
assertEq("isBuffActive false once expired", isBuffActive(buff(2, NOW - 1), NOW), false);

// --- resolveBuffGrant: never stacks multiplicatively ---
{
  const fresh = resolveBuffGrant(null, {multiplier: 2, durationMs: 60_000, source: "PROMOTION"}, NOW);
  assertEq("granting with no active buff applies it", fresh?.multiplier, 2);
  assertEq("expiry is now + duration", fresh?.expiresAt, NOW + 60_000);

  // The headline rule: 2x on top of 2x must never become 4x.
  const sameStrength = resolveBuffGrant(
    buff(2, NOW + 10_000),
    {multiplier: 2, durationMs: 60_000, source: "PROMOTION"},
    NOW
  );
  assertEq("an equal buff does not compound the multiplier", sameStrength?.multiplier, 2);
  assertEq("an equal buff extends to the later expiry", sameStrength?.expiresAt, NOW + 60_000);

  const equalButShorter = resolveBuffGrant(
    buff(2, NOW + 60_000),
    {multiplier: 2, durationMs: 10_000, source: "PROMOTION"},
    NOW
  );
  assertEq(
    "an equal but shorter buff never shortens the active one",
    equalButShorter?.expiresAt,
    NOW + 60_000
  );

  const stronger = resolveBuffGrant(
    buff(2, NOW + 60_000),
    {multiplier: 3, durationMs: 10_000, source: "PROMOTION"},
    NOW
  );
  assertEq("a stronger buff replaces the active one", stronger?.multiplier, 3);
  assertEq("a stronger buff uses its own expiry", stronger?.expiresAt, NOW + 10_000);

  const weaker = resolveBuffGrant(
    buff(3, NOW + 60_000),
    {multiplier: 2, durationMs: 600_000, source: "PROMOTION"},
    NOW
  );
  assertEq("a weaker buff is ignored while a stronger one runs", weaker, null);

  const overExpired = resolveBuffGrant(
    buff(3, NOW - 1),
    {multiplier: 2, durationMs: 60_000, source: "PROMOTION"},
    NOW
  );
  assertEq("a weaker buff applies once the stronger one has expired", overExpired?.multiplier, 2);
}

// --- resolveBuffGrant: bounds ---
{
  const capped = resolveBuffGrant(
    null,
    {multiplier: 99, durationMs: 60_000, source: "PROMOTION"},
    NOW
  );
  assertEq("multiplier is capped at the maximum", capped?.multiplier, MAX_BUFF_MULTIPLIER);

  const longRun = resolveBuffGrant(
    null,
    {multiplier: 2, durationMs: MAX_BUFF_DURATION_MS * 10, source: "PROMOTION"},
    NOW
  );
  assertEq("duration is capped at the maximum", longRun?.expiresAt, NOW + MAX_BUFF_DURATION_MS);

  assertEq(
    "a 1x grant is not a buff",
    resolveBuffGrant(null, {multiplier: 1, durationMs: 60_000, source: "PROMOTION"}, NOW),
    null
  );
  assertEq(
    "a zero-duration grant is not a buff",
    resolveBuffGrant(null, {multiplier: 2, durationMs: 0, source: "PROMOTION"}, NOW),
    null
  );
  assertEq(
    "a negative duration is not a buff",
    resolveBuffGrant(null, {multiplier: 2, durationMs: -5, source: "PROMOTION"}, NOW),
    null
  );
}

// --- THE guard: an active buff must not leak onto ineligible sources ---
{
  const active = activeMultiplier(buff(3, NOW + 60_000), NOW);
  assertEq("the buff under test is genuinely active", active, 3);

  for (const source of ["QUIZ", "GAME", "REFERRAL_REFEREE", "REFERRAL_REFERRER", "DAILY_LOGIN"] as const) {
    const award = buildAward(0, 0, {
      source,
      basePoints: 100,
      baseXp: 10,
      metadata: {},
      activeMultiplier: active,
    });
    assertEq(`${source} points are untouched by an active buff`, award.pointsAwarded, 100);
    assertEq(`${source} ledger records multiplierApplied 1`, award.ledgerDoc.multiplierApplied, 1);
    assertEq(`${source} xp is untouched by an active buff`, award.xpAwarded, 10);
  }

  for (const source of ["OFFERWALL", "SURVEY", "SPONSORED_APP"] as const) {
    const award = buildAward(0, 0, {
      source,
      basePoints: 100,
      baseXp: 10,
      metadata: {},
      activeMultiplier: active,
    });
    assertEq(`${source} points ARE multiplied by an active buff`, award.pointsAwarded, 300);
    assertEq(`${source} ledger records the multiplier used`, award.ledgerDoc.multiplierApplied, 3);
    assertEq(`${source} xp is still never multiplied`, award.xpAwarded, 10);
  }

  // A redemption must never be scaled up by a buff.
  const spend = buildAward(1000, 0, {
    source: "REDEMPTION", basePoints: -100, baseXp: 0, metadata: {}, activeMultiplier: active,
  });
  assertEq("a redemption is never scaled by an active buff", spend.pointsAwarded, -100);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
