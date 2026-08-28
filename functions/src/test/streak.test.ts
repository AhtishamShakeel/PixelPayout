/**
 * Pure unit tests for the daily streak. No emulator.
 * Run via: npm run test:unit
 */
import {
  resolveStreakClaim,
  resolveStreakReward,
  streakRewardForDay,
  utcDayFor,
  STREAK_CYCLE_DAYS,
  STREAK_REWARDS,
} from "../economy/streak";

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

// --- utcDayFor ---------------------------------------------------------------
{
  assertEq("epoch is day 0", utcDayFor(0), 0);
  assertEq("one day later is day 1", utcDayFor(86_400_000), 1);
  assertEq("a moment before midnight is still the same day",
    utcDayFor(86_400_000 - 1), 0);
  assertEq("midday does not round up",
    utcDayFor(86_400_000 + 43_200_000), 1);

  // The rule has to agree with the UTC calendar day, because
  // checkAndResetQuizAttempts tests UTC dates and the two must not disagree
  // about when "tomorrow" starts.
  const d = Date.UTC(2026, 7, 31, 23, 59, 59);
  const nextDay = Date.UTC(2026, 8, 1, 0, 0, 0);
  assertEq("last second of a UTC day and the next are different days",
    utcDayFor(nextDay) - utcDayFor(d), 1);
}

// --- the day-boundary rule ---------------------------------------------------
{
  const today = utcDayFor(Date.UTC(2026, 7, 31));

  assertEq("a first ever claim starts the streak at day 1",
    resolveStreakClaim(null, today, 0),
    {status: "claimed", day: 1, continued: false});

  assertEq("claiming twice in one day is refused",
    resolveStreakClaim(today, today, 3),
    {status: "already_claimed", day: 3});

  assertEq("yesterday continues the streak",
    resolveStreakClaim(today - 1, today, 3),
    {status: "claimed", day: 4, continued: true});

  assertEq("a two day gap breaks the streak",
    resolveStreakClaim(today - 2, today, 6),
    {status: "claimed", day: 1, continued: false});

  assertEq("a long absence breaks the streak",
    resolveStreakClaim(today - 400, today, 200),
    {status: "claimed", day: 1, continued: false});

  // Should be unreachable - lastDay is only ever written from server time -
  // but resetting a real streak because one field looks wrong is the worse
  // of the two failures.
  assertEq("a future lastDay is treated as already claimed, not as a break",
    resolveStreakClaim(today + 1, today, 5),
    {status: "already_claimed", day: 5});

  assertEq("continuing from a zero count still yields day 1",
    resolveStreakClaim(today - 1, today, 0),
    {status: "claimed", day: 1, continued: true});

  // Crossing the cycle boundary must keep counting, not wrap the count -
  // only the REWARD wraps.
  assertEq("day 7 continues into day 8",
    resolveStreakClaim(today - 1, today, 7),
    {status: "claimed", day: 8, continued: true});
}

// --- the reward table --------------------------------------------------------
{
  assertEq("the cycle is seven days", STREAK_CYCLE_DAYS, 7);

  assertEq("day 1 pays 10 xp", streakRewardForDay(1), {points: 0, xp: 10});
  assertEq("day 2 pays 20 xp", streakRewardForDay(2), {points: 0, xp: 20});
  assertEq("day 3 pays 30 xp", streakRewardForDay(3), {points: 0, xp: 30});
  assertEq("day 4 pays 10 points", streakRewardForDay(4), {points: 10, xp: 0});
  assertEq("day 5 pays 50 xp", streakRewardForDay(5), {points: 0, xp: 50});
  assertEq("day 6 pays 60 xp", streakRewardForDay(6), {points: 0, xp: 60});
  assertEq("day 7 pays 20 points", streakRewardForDay(7), {points: 20, xp: 0});

  assertEq("day 8 wraps to the day 1 reward",
    streakRewardForDay(8), streakRewardForDay(1));
  assertEq("day 14 wraps to the day 7 reward",
    streakRewardForDay(14), streakRewardForDay(7));
  assertEq("day 100 stays inside the table",
    streakRewardForDay(100), STREAK_REWARDS[(100 - 1) % 7]);

  // Defensive: no caller should pass these, and none should throw.
  assertEq("day 0 falls back to the first day", streakRewardForDay(0),
    STREAK_REWARDS[0]);
  assertEq("a negative day falls back to the first day",
    streakRewardForDay(-5), STREAK_REWARDS[0]);

  // Only the marked days may cost real money.
  const payingDays = STREAK_REWARDS
    .map((r, i) => (r.points > 0 ? i + 1 : 0))
    .filter((d) => d > 0);
  assertEq("only days 4 and 7 award points", payingDays, [4, 7]);

  const total = STREAK_REWARDS.reduce((sum, r) => sum + r.points, 0);
  assertEq("a full cycle costs 30 points", total, 30);
}

// --- the reward gate ---------------------------------------------------------
// Deliberately independent of the streak gate: the streak advances whether or
// not an ad played, the reward does not.
{
  const today = utcDayFor(Date.UTC(2026, 7, 31));

  assertEq("an ad on an unrewarded day pays",
    resolveStreakReward(null, today, true), {pay: true});

  assertEq("no ad means no reward",
    resolveStreakReward(null, today, false),
    {pay: false, reason: "no_ad"});

  // The whole point of the retry design: a failed ad leaves the day open.
  assertEq("a day left unrewarded stays claimable",
    resolveStreakReward(today - 1, today, true), {pay: true});

  assertEq("a rewarded day cannot be rewarded twice",
    resolveStreakReward(today, today, true),
    {pay: false, reason: "already_rewarded"});

  assertEq("already rewarded outranks a missing ad",
    resolveStreakReward(today, today, false),
    {pay: false, reason: "already_rewarded"});

  // Advancing the streak without an ad must not consume the reward: the two
  // gates read different fields.
  assertEq("an adless claim still advances the streak",
    resolveStreakClaim(today - 1, today, 3),
    {status: "claimed", day: 4, continued: true});
  assertEq("and leaves the reward open for a later retry",
    resolveStreakReward(null, today, true), {pay: true});
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
