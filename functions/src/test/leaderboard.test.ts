/**
 * Pure unit tests for the weekly leaderboard. No emulator.
 * Run via: npm run test:unit
 */
import {
  nextWeeklyXp,
  prizeForRank,
  totalWeeklyPrizePool,
  utcWeekFor,
  weekEndMillis,
  weekStartMillis,
  LEADERBOARD_PRIZES,
  LEADERBOARD_SIZE,
} from "../economy/leaderboard";

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(
    `  FAIL  ${desc}${detail !== undefined ? " -- " + JSON.stringify(detail) : ""}`
  );
}

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok(desc);
  } else {
    fail(desc, {actual, expected});
  }
}

// --- the week boundary -------------------------------------------------------
{
  // The property that matters: a week must start on a Monday. Epoch day 0 was
  // a Thursday, so an unshifted division would put the boundary there.
  for (let w = 2800; w < 2810; w++) {
    const start = new Date(weekStartMillis(w));
    if (start.getUTCDay() !== 1) {
      fail(`week ${w} starts on a Monday`, start.toUTCString());
      break;
    }
  }
  ok("weeks start on a Monday");

  // Known instants, checked against the calendar rather than the formula.
  const monday = Date.UTC(2026, 7, 31, 0, 0, 0); // Mon 31 Aug 2026
  const sunday = Date.UTC(2026, 8, 6, 23, 59, 59); // Sun 6 Sep 2026
  const nextMonday = Date.UTC(2026, 8, 7, 0, 0, 0);

  assertEq("a Monday and the Sunday after it are one week",
    utcWeekFor(monday), utcWeekFor(sunday));
  assertEq("the next Monday starts a new week",
    utcWeekFor(nextMonday) - utcWeekFor(monday), 1);
  assertEq("the last instant of a week is not the next one",
    utcWeekFor(nextMonday - 1), utcWeekFor(monday));

  assertEq("a week starts where the previous one ends",
    weekEndMillis(2900), weekStartMillis(2901));
  assertEq("a week is seven days long",
    weekEndMillis(2900) - weekStartMillis(2900), 7 * 86_400_000);
  assertEq("the start of a week is inside it",
    utcWeekFor(weekStartMillis(2900)), 2900);
  assertEq("the end of a week is not",
    utcWeekFor(weekEndMillis(2900)), 2901);
}

// --- the lazy reset ----------------------------------------------------------
{
  const week = 2900;

  assertEq("a first award this week starts the count",
    nextWeeklyXp(undefined, undefined, week, 10), 10);

  assertEq("a later award in the same week adds",
    nextWeeklyXp(week, 40, week, 10), 50);

  // The whole point of the lazy reset: last week's figure is replaced, not
  // added to, so no job has to touch every document at the boundary.
  assertEq("last week's total is discarded, not carried",
    nextWeeklyXp(week - 1, 5000, week, 10), 10);

  assertEq("a week from long ago is also discarded",
    nextWeeklyXp(week - 52, 99999, week, 10), 10);

  // Defensive: a corrupt stored value must not poison the running total.
  assertEq("a missing stored total reads as zero",
    nextWeeklyXp(week, undefined, week, 10), 10);
  assertEq("a negative stored total reads as zero",
    nextWeeklyXp(week, -50, week, 10), 10);
  assertEq("a negative award adds nothing",
    nextWeeklyXp(week, 40, week, -10), 40);
  assertEq("a fractional award is truncated",
    nextWeeklyXp(week, 40, week, 10.9), 50);
}

// --- the prize table ---------------------------------------------------------
{
  assertEq("first place wins the top band", prizeForRank(1), 350);
  assertEq("second and third share a band",
    prizeForRank(2), prizeForRank(3));
  assertEq("the last ranked place still wins something",
    prizeForRank(LEADERBOARD_SIZE) > 0, true);
  assertEq("one place past the board wins nothing",
    prizeForRank(LEADERBOARD_SIZE + 1), 0);

  assertEq("an unranked caller wins nothing", prizeForRank(0), 0);
  assertEq("a negative rank wins nothing", prizeForRank(-1), 0);
  assertEq("a fractional rank wins nothing", prizeForRank(1.5), 0);

  // Bands must not overlap or leave a gap, or a rank would win twice or
  // nothing by accident.
  const sorted = [...LEADERBOARD_PRIZES].sort((a, b) => a.fromRank - b.fromRank);
  let contiguous = sorted[0].fromRank === 1;
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].fromRank !== sorted[i - 1].toRank + 1) contiguous = false;
  }
  assertEq("the bands are contiguous from rank 1", contiguous, true);
  assertEq("the bands end exactly at the board size",
    sorted[sorted.length - 1].toRank, LEADERBOARD_SIZE);

  // Every rank inside the board must be covered.
  let allCovered = true;
  for (let rank = 1; rank <= LEADERBOARD_SIZE; rank++) {
    if (prizeForRank(rank) <= 0) allCovered = false;
  }
  assertEq("every place on the board wins something", allCovered, true);

  // A prize table must not reward finishing lower.
  let monotonic = true;
  for (let rank = 2; rank <= LEADERBOARD_SIZE; rank++) {
    if (prizeForRank(rank) > prizeForRank(rank - 1)) monotonic = false;
  }
  assertEq("a lower place never wins more", monotonic, true);

  // The number that has to be budgeted: a fixed weekly outgoing that does not
  // scale with how many people play.
  assertEq("the weekly pool totals up", totalWeeklyPrizePool(),
    350 + 2 * 200 + 7 * 100 + 20 * 50);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
