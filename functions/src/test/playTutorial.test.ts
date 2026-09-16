/**
 * Pure unit tests for the Play tutorial's level-2 top-up - no emulator.
 * Run via: npm run test:unit   (from functions/)
 */
import {XP_THRESHOLDS, levelForXp} from "../economy/levelCurve";
import {
  PLAY_TUTORIAL_TARGET_LEVEL,
  playTutorialRequirementsMet,
  playTutorialTopUpXp,
} from "../economy/playTutorial";

let passed = 0;
let failed = 0;

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    passed++;
    console.log(`  PASS  ${desc}`);
  } else {
    failed++;
    console.log(`  FAIL  ${desc} -- expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

const threshold = XP_THRESHOLDS[PLAY_TUTORIAL_TARGET_LEVEL - 2];

console.log("\n-- top-up --");
assertEq("a fresh account is topped up to the full threshold", playTutorialTopUpXp(0), threshold);
assertEq("partial XP is topped up by the difference", playTutorialTopUpXp(34), threshold - 34);
assertEq("exactly at the threshold gets nothing", playTutorialTopUpXp(threshold), 0);
assertEq("past the threshold gets nothing", playTutorialTopUpXp(threshold + 500), 0);
assertEq("negative XP is treated as zero", playTutorialTopUpXp(-20), threshold);
assertEq("NaN is treated as zero", playTutorialTopUpXp(Number.NaN), threshold);
assertEq("fractional XP is floored before topping up", playTutorialTopUpXp(10.9), threshold - 10);

for (const xp of [0, 1, 17, threshold - 1]) {
  assertEq(
    `after the top-up from ${xp} XP the account is at the target level`,
    levelForXp(xp + playTutorialTopUpXp(xp)),
    PLAY_TUTORIAL_TARGET_LEVEL
  );
}

console.log("\n-- requirements --");
assertEq("nothing played is refused", playTutorialRequirementsMet(0, 0), false);
assertEq("a game without quizzes is refused", playTutorialRequirementsMet(1, 0), false);
assertEq("one quiz short is refused", playTutorialRequirementsMet(1, 1), false);
assertEq("quizzes without a game are refused", playTutorialRequirementsMet(0, 2), false);
assertEq("one game and two quizzes pass", playTutorialRequirementsMet(1, 2), true);

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
