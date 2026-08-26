/**
 * Pure unit tests for the level curve - no emulator, no Firestore, runs in
 * milliseconds. Run via: npm run test:unit   (from functions/)
 */
import {
  MAX_LEVEL,
  XP_THRESHOLDS,
  levelForXp,
  xpRequiredForLevel,
  applyXpGain,
  levelProgressForXp,
} from "../economy/levelCurve";

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

function assertTrue(desc: string, condition: boolean, detail?: unknown) {
  if (condition) {
    ok(desc);
  } else {
    fail(desc, detail);
  }
}

console.log("=== Level curve unit tests ===\n");

// --- levelForXp boundaries ---
assertEq("0 xp -> level 1", levelForXp(0), 1);
assertEq("negative xp -> level 1 (defensive)", levelForXp(-50), 1);
assertEq("NaN xp -> level 1 (defensive)", levelForXp(NaN), 1);

const firstThreshold = XP_THRESHOLDS[0];
assertEq("1 xp under level-2 threshold -> still level 1", levelForXp(firstThreshold - 1), 1);
assertEq("exactly level-2 threshold -> level 2", levelForXp(firstThreshold), 2);

const secondThreshold = XP_THRESHOLDS[1];
assertEq("1 xp under level-3 threshold -> still level 2", levelForXp(secondThreshold - 1), 2);
assertEq("exactly level-3 threshold -> level 3", levelForXp(secondThreshold), 3);

const lastThreshold = XP_THRESHOLDS[XP_THRESHOLDS.length - 1];
assertEq("exactly the top threshold -> MAX_LEVEL", levelForXp(lastThreshold), MAX_LEVEL);
assertEq("far past the top threshold -> capped at MAX_LEVEL", levelForXp(lastThreshold * 1000), MAX_LEVEL);
assertEq("1 xp under the top threshold -> MAX_LEVEL - 1", levelForXp(lastThreshold - 1), MAX_LEVEL - 1);

// --- xpRequiredForLevel ---
assertEq("level 1 requires 0 xp", xpRequiredForLevel(1), 0);
assertEq("level 0 or below requires 0 xp (defensive)", xpRequiredForLevel(0), 0);
assertEq("level 2 requires XP_THRESHOLDS[0]", xpRequiredForLevel(2), XP_THRESHOLDS[0]);
assertEq("MAX_LEVEL requires the last threshold", xpRequiredForLevel(MAX_LEVEL), lastThreshold);
assertEq(
  "a level beyond MAX_LEVEL is clamped to MAX_LEVEL's requirement",
  xpRequiredForLevel(MAX_LEVEL + 10),
  lastThreshold
);

// --- consistency between the two functions ---
for (let level = 2; level <= MAX_LEVEL; level++) {
  const req = xpRequiredForLevel(level);
  assertEq(`levelForXp(xpRequiredForLevel(${level})) === ${level}`, levelForXp(req), level);
  assertEq(`levelForXp(xpRequiredForLevel(${level}) - 1) === ${level - 1}`, levelForXp(req - 1), level - 1);
}

// --- monotonicity sanity checks (catch a bad curve formula automatically) ---
assertTrue(
  "XP_THRESHOLDS is strictly increasing",
  XP_THRESHOLDS.every((t, i) => i === 0 || t > XP_THRESHOLDS[i - 1])
);
assertTrue("XP_THRESHOLDS has MAX_LEVEL - 1 entries", XP_THRESHOLDS.length === MAX_LEVEL - 1);

{
  let previousLevel = 1;
  let monotonic = true;
  for (let xp = 0; xp <= lastThreshold + 1000; xp += 37) {
    const level = levelForXp(xp);
    if (level < previousLevel) {
      monotonic = false;
      break;
    }
    previousLevel = level;
  }
  assertTrue("levelForXp is monotonically non-decreasing as xp increases", monotonic);
}

// --- applyXpGain ---
{
  const result = applyXpGain(0, 10);
  assertEq("small gain from 0 xp -> still level 1", result.level, 1);
  assertEq("small gain from 0 xp -> not leveled up", result.leveledUp, false);
  assertEq("small gain from 0 xp -> no levels crossed", result.levelsCrossed, []);
  assertEq("xp accumulates", result.xp, 10);
}

{
  const result = applyXpGain(0, firstThreshold);
  assertEq("gain that exactly hits threshold -> leveled up", result.leveledUp, true);
  assertEq("gain that exactly hits threshold -> level 2", result.level, 2);
  assertEq("gain that exactly hits threshold -> previousLevel 1", result.previousLevel, 1);
  assertEq("gain that exactly hits threshold -> levelsCrossed [2]", result.levelsCrossed, [2]);
}

{
  // A single huge gain (e.g. a big offerwall completion) can cross several
  // milestone levels at once - this is exactly the scenario Step 6's
  // milestone-bonus logic needs to handle correctly.
  const result = applyXpGain(0, lastThreshold);
  assertEq("huge gain crosses every level from 2 to MAX_LEVEL", result.levelsCrossed.length, MAX_LEVEL - 1);
  assertEq("huge gain -> final level is MAX_LEVEL", result.level, MAX_LEVEL);
  assertEq("huge gain -> levelsCrossed starts at 2", result.levelsCrossed[0], 2);
  assertEq(
    "huge gain -> levelsCrossed ends at MAX_LEVEL",
    result.levelsCrossed[result.levelsCrossed.length - 1],
    MAX_LEVEL
  );
}

{
  const result = applyXpGain(-100, -50);
  assertEq("negative current xp is treated as 0 (defensive)", result.xp, 0);
  assertEq("negative gain is treated as 0 (defensive)", result.level, 1);
}

// --- levelProgressForXp: the "XP resets each level" view ---
{
  const atZero = levelProgressForXp(0);
  assertEq("0 xp -> level 1, no progress", atZero.xpIntoLevel, 0);
  assertEq("0 xp -> next level spans the first threshold", atZero.xpForNextLevel, XP_THRESHOLDS[0]);
  assertEq("0 xp -> not max level", atZero.isMaxLevel, false);

  const midLevel1 = levelProgressForXp(20);
  assertEq("20 xp -> 20 into level 1", midLevel1.xpIntoLevel, 20);
  assertEq("20 xp -> still level 1", midLevel1.level, 1);

  // The whole point: crossing a threshold resets the displayed progress.
  const justBelow = levelProgressForXp(XP_THRESHOLDS[0] - 1);
  const justAt = levelProgressForXp(XP_THRESHOLDS[0]);
  assertEq("1 xp below the threshold is nearly a full bar", justBelow.xpIntoLevel, XP_THRESHOLDS[0] - 1);
  assertEq("hitting the threshold resets progress to 0", justAt.xpIntoLevel, 0);
  assertEq("hitting the threshold advances the level", justAt.level, 2);
  assertEq(
    "the new level spans threshold[1] - threshold[0]",
    justAt.xpForNextLevel,
    XP_THRESHOLDS[1] - XP_THRESHOLDS[0]
  );

  // Progress must never exceed the level's span, at any XP value.
  let withinSpan = true;
  for (let xp = 0; xp <= XP_THRESHOLDS[XP_THRESHOLDS.length - 1]; xp += 101) {
    const p = levelProgressForXp(xp);
    if (!p.isMaxLevel && (p.xpIntoLevel < 0 || p.xpIntoLevel >= p.xpForNextLevel + 1)) {
      withinSpan = false;
      break;
    }
  }
  assertTrue("xpIntoLevel always sits within the current level's span", withinSpan);

  const maxed = levelProgressForXp(XP_THRESHOLDS[XP_THRESHOLDS.length - 1]);
  assertEq("at the top threshold -> max level", maxed.isMaxLevel, true);
  assertEq("max level reports no next-level span", maxed.xpForNextLevel, 0);

  const beyondMax = levelProgressForXp(XP_THRESHOLDS[XP_THRESHOLDS.length - 1] * 10);
  assertEq("far beyond max stays max level", beyondMax.isMaxLevel, true);
  assertEq("far beyond max reports no next-level span", beyondMax.xpForNextLevel, 0);

  const negative = levelProgressForXp(-100);
  assertEq("negative xp -> level 1, zero progress (defensive)", negative.xpIntoLevel, 0);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
