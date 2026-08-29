/**
 * Pure unit tests for the daily goals. No emulator.
 * Run via: npm run test:unit
 */
import {
  allGoalsDone,
  resolveBonusPoints,
  goalProgress,
  isGoalDone,
  resolveGoalBonus,
  selectDailyGoals,
  statsForDay,
  DAILY_GOAL_BONUS_POINTS,
  DAILY_GOAL_COUNT,
  DAILY_GOAL_POOL,
  MAX_DAILY_GOAL_BONUS_POINTS,
  GOAL_KINDS,
  GoalTemplate,
} from "../economy/dailyGoals";
import {MAX_DAILY_QUIZ_ATTEMPTS} from "../economy/rewardConfig";

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

// --- the pool ----------------------------------------------------------------
{
  // The rule that keeps this honest: a goal must be measurable from something
  // the server counts itself. Stars-earned and ad-watched goals are excluded
  // by design and must not creep back in.
  const kinds = new Set(DAILY_GOAL_POOL.map((g) => g.kind));
  assertEq("the pool only uses server-measured kinds",
    [...kinds].sort(), [...GOAL_KINDS].sort());

  assertEq("every kind has something to choose from",
    GOAL_KINDS.every((k) => DAILY_GOAL_POOL.some((g) => g.kind === k)), true);

  assertEq("goal ids are unique",
    new Set(DAILY_GOAL_POOL.map((g) => g.id)).size, DAILY_GOAL_POOL.length);

  assertEq("no goal asks for nothing",
    DAILY_GOAL_POOL.every((g) => g.target >= 1), true);

  // The trap this guards: quiz attempts are capped per day, so a quiz target
  // above that cap is unreachable - and because the bonus needs all three
  // goals, one impossible goal silently kills the whole day for anyone who
  // draws it. No error is raised anywhere; the card simply never completes.
  const quizBound = DAILY_GOAL_POOL.filter(
    (g) => g.kind === "COMPLETE_QUIZZES" || g.kind === "CORRECT_ANSWERS"
  );
  assertEq("quiz goals stay within the daily attempt cap",
    quizBound.every((g) => g.target <= MAX_DAILY_QUIZ_ATTEMPTS), true);

  // Correct answers need the attempt AND the right answer, so a target at the
  // cap demands a flawless day. Keep it to a fraction of what is possible.
  assertEq("correct-answer goals leave room to get one wrong",
    DAILY_GOAL_POOL
      .filter((g) => g.kind === "CORRECT_ANSWERS")
      .every((g) => g.target < MAX_DAILY_QUIZ_ATTEMPTS), true);
}

// --- selection ---------------------------------------------------------------
{
  const day = 20693;

  const a = selectDailyGoals("user-a", day);
  assertEq("a day has one goal per kind", a.length, DAILY_GOAL_COUNT);
  assertEq("and never repeats a kind",
    new Set(a.map((g) => g.kind)).size, DAILY_GOAL_COUNT);

  // Stable without being stored: the same inputs must always give the same
  // set, or the goals would change under the user mid-day.
  assertEq("the same user and day gives the same goals",
    selectDailyGoals("user-a", day).map((g) => g.id), a.map((g) => g.id));

  // Different users should not all be given identical days. Compare across a
  // spread rather than one pair, since any two can legitimately collide.
  const spread = new Set(
    Array.from({length: 40}, (_, i) =>
      selectDailyGoals(`user-${i}`, day).map((g) => g.id).join("|"))
  );
  assertEq("different users get different sets", spread.size > 1, true);

  const overDays = new Set(
    Array.from({length: 40}, (_, i) =>
      selectDailyGoals("user-a", day + i).map((g) => g.id).join("|"))
  );
  assertEq("the set changes from day to day", overDays.size > 1, true);

  assertEq("selection only ever returns pool members",
    a.every((g) => DAILY_GOAL_POOL.some((p) => p.id === g.id)), true);
}

// --- counters ----------------------------------------------------------------
{
  const today = 20693;

  assertEq("no stored stats reads as a fresh day",
    statsForDay(undefined, today), {dayUtc: today, games: 0, quizzes: 0, correct: 0});

  assertEq("yesterday's counters do not carry over",
    statsForDay({dayUtc: today - 1, games: 5, quizzes: 5, correct: 5}, today),
    {dayUtc: today, games: 0, quizzes: 0, correct: 0});

  assertEq("today's counters are kept",
    statsForDay({dayUtc: today, games: 2, quizzes: 1, correct: 1}, today),
    {dayUtc: today, games: 2, quizzes: 1, correct: 1});

  assertEq("malformed counters read as zero, not NaN",
    statsForDay({dayUtc: today, games: undefined, quizzes: NaN}, today),
    {dayUtc: today, games: 0, quizzes: 0, correct: 0});
}

// --- progress ----------------------------------------------------------------
{
  const today = 20693;
  const play3: GoalTemplate = {id: "play_3", kind: "PLAY_GAMES", target: 3};
  const quiz1: GoalTemplate = {id: "quiz_1", kind: "COMPLETE_QUIZZES", target: 1};
  const correct2: GoalTemplate = {id: "correct_2", kind: "CORRECT_ANSWERS", target: 2};

  const stats = {dayUtc: today, games: 2, quizzes: 1, correct: 3};

  assertEq("progress reads the matching counter", goalProgress(play3, stats), 2);
  assertEq("progress never exceeds the target",
    goalProgress(correct2, stats), 2);
  assertEq("a met target is done", isGoalDone(quiz1, stats), true);
  assertEq("an unmet target is not", isGoalDone(play3, stats), false);

  assertEq("all done requires every goal",
    allGoalsDone([play3, quiz1, correct2], stats), false);
  assertEq("all done when every goal is met",
    allGoalsDone([quiz1, correct2], stats), true);
  // An empty set must not read as complete, or a bug upstream would pay out.
  assertEq("an empty set is never complete", allGoalsDone([], stats), false);
}

// --- the bonus gate ----------------------------------------------------------
{
  const today = 20693;
  const goals = selectDailyGoals("user-a", today);
  const done = {
    dayUtc: today,
    games: 99,
    quizzes: 99,
    correct: 99,
  };
  const nothing = {dayUtc: today, games: 0, quizzes: 0, correct: 0};

  assertEq("a finished set pays",
    resolveGoalBonus(null, today, goals, done, true), {pay: true});

  assertEq("an unfinished set does not",
    resolveGoalBonus(null, today, goals, nothing, true),
    {pay: false, reason: "not_complete"});

  assertEq("the bonus cannot be claimed twice in a day",
    resolveGoalBonus(today, today, goals, done, true),
    {pay: false, reason: "already_claimed"});

  assertEq("yesterday's claim does not block today",
    resolveGoalBonus(today - 1, today, goals, done, true), {pay: true});

  assertEq("a finished set without an ad pays nothing",
    resolveGoalBonus(null, today, goals, done, false),
    {pay: false, reason: "no_ad"});

  // Nothing is consumed by refusing the ad, so the day stays claimable.
  assertEq("and leaves the day open to try again",
    resolveGoalBonus(null, today, goals, done, true), {pay: true});

  assertEq("an unfinished set fails on the set, not the ad",
    resolveGoalBonus(null, today, goals, nothing, false),
    {pay: false, reason: "not_complete"});

  assertEq("already claimed outranks an unfinished set",
    resolveGoalBonus(today, today, goals, nothing, true),
    {pay: false, reason: "already_claimed"});
}

// --- the configured bonus ----------------------------------------------------
// config/dailyGoals is edited by hand in a console, so every way that edit can
// be wrong has to land somewhere safe.
{
  assertEq("a sensible value is used as given", resolveBonusPoints(45), 45);
  assertEq("zero is a legitimate setting", resolveBonusPoints(0), 0);

  // The failure that matters: an extra zero must cost a capped amount.
  assertEq("an enormous value is capped",
    resolveBonusPoints(999999), MAX_DAILY_GOAL_BONUS_POINTS);

  // A broken config must leave the economy as it was, never switch the
  // reward silently off.
  for (const bad of [undefined, null, "", "thirty", NaN, Infinity, -5, 12.5]) {
    assertEq(`${JSON.stringify(bad)} falls back to the built-in value`,
      resolveBonusPoints(bad), DAILY_GOAL_BONUS_POINTS);
  }

  assertEq("the fallback is itself within the cap",
    DAILY_GOAL_BONUS_POINTS <= MAX_DAILY_GOAL_BONUS_POINTS, true);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
