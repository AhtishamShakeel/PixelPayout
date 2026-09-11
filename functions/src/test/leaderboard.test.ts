/**
 * Pure unit tests for the weekly leaderboard. No emulator.
 * Run via: npm run test:unit
 */
import {
  buildSettlement,
  mergeSettlementBoard,
  nextWeeklyXp,
  weeklyRollover,
  prizeForRank,
  settlementCost,
  settlementWeekFor,
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

// --- the rollover carry ------------------------------------------------------
//
// The bug this guards: settlement runs five minutes into the new week, and a
// claim before then used to overwrite the total it was about to be paid for.
{
  const week = 2900;

  const same = weeklyRollover(week, 40, week, 10);
  assertEq("a claim inside the week carries nothing",
    same, {weekKey: week, weeklyXp: 50});

  const rolled = weeklyRollover(week - 1, 5000, week, 10);
  assertEq("the first claim of a new week preserves the closing total",
    rolled,
    {weekKey: week, weeklyXp: 10, lastWeekKey: week - 1, lastWeeklyXp: 5000});

  assertEq("a second claim in the new week does not re-carry",
    weeklyRollover(week, 10, week, 10), {weekKey: week, weeklyXp: 20});

  assertEq("a brand new user has no week to preserve",
    weeklyRollover(undefined, undefined, week, 10),
    {weekKey: week, weeklyXp: 10});

  // Nothing to pay, so nothing to find: a zero carry would only add rows to a
  // query that exists to locate winners.
  assertEq("a week that scored nothing is not carried",
    weeklyRollover(week - 1, 0, week, 10), {weekKey: week, weeklyXp: 10});

  assertEq("a corrupt stored total is not carried",
    weeklyRollover(week - 1, -5, week, 10), {weekKey: week, weeklyXp: 10});

  // The reproduction from the report, end to end: last week's winner plays
  // right after the reset, and must still be findable as last week's winner.
  const winner = weeklyRollover(week - 1, 4800, week, 30);
  assertEq("last week's winner is still payable after playing on Monday",
    winner.lastWeeklyXp, 4800);
  assertEq("...and is ranked under last week, not this one",
    winner.lastWeekKey, week - 1);
}

// --- merging the two halves of a finished week -------------------------------
{
  const merged = mergeSettlementBoard(
    [{uid: "idle", weeklyXp: 300}],
    [{uid: "played-on", weeklyXp: 900}]
  );
  assertEq("a player who moved on still outranks one who did not",
    merged, [
      {uid: "played-on", weeklyXp: 900},
      {uid: "idle", weeklyXp: 300},
    ]);

  // Firestore separates equal scores by document name; the merge has to agree
  // with it or the settlement would rank ties differently from the screen.
  assertEq("ties break on uid, ascending, as Firestore orders them",
    mergeSettlementBoard(
      [{uid: "zoe", weeklyXp: 100}],
      [{uid: "amy", weeklyXp: 100}]
    ),
    [{uid: "amy", weeklyXp: 100}, {uid: "zoe", weeklyXp: 100}]);

  assertEq("a uid in both halves is counted once, at its best total",
    mergeSettlementBoard(
      [{uid: "dup", weeklyXp: 100}],
      [{uid: "dup", weeklyXp: 400}]
    ),
    [{uid: "dup", weeklyXp: 400}]);

  // Each half arrives already capped at LEADERBOARD_SIZE, so the union can be
  // twice that; the board itself must not be.
  const wide = mergeSettlementBoard(
    Array.from({length: LEADERBOARD_SIZE}, (_, i) => ({
      uid: `live-${i}`, weeklyXp: 1000 - i,
    })),
    Array.from({length: LEADERBOARD_SIZE}, (_, i) => ({
      uid: `carried-${i}`, weeklyXp: 2000 - i,
    }))
  );
  assertEq("the merged board is still capped at the board size",
    wide.length, LEADERBOARD_SIZE);
  assertEq("and the cap keeps the highest scores",
    wide[0], {uid: "carried-0", weeklyXp: 2000});

  assertEq("a week nobody played merges to nothing",
    mergeSettlementBoard([], []), []);

  // The merge feeds buildSettlement, so the whole path has to hold together.
  const paid = buildSettlement(mergeSettlementBoard(
    [{uid: "runner-up", weeklyXp: 400}],
    [{uid: "winner", weeklyXp: 900}]
  ));
  assertEq("the winner is paid first prize, not the runner-up",
    paid, [
      {uid: "winner", rank: 1, weeklyXp: 900, points: 350},
      {uid: "runner-up", rank: 2, weeklyXp: 400, points: 200},
    ]);
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

// --- which week a settlement pays ------------------------------------------
{
  const monday = weekStartMillis(2900);
  assertEq("a run at the boundary settles the week that just ended",
    settlementWeekFor(monday), 2899);
  assertEq("a run mid-week still settles the week that just ended",
    settlementWeekFor(monday + 3 * 86_400_000), 2899);
  assertEq("a run one millisecond before the boundary settles the week before",
    settlementWeekFor(monday - 1), 2898);
}

// --- turning a board into payouts -------------------------------------------
{
  const board = (count: number, xp = 100) =>
    Array.from({length: count}, (_, i) => ({uid: `u${i}`, weeklyXp: xp - i}));

  const full = buildSettlement(board(LEADERBOARD_SIZE));
  assertEq("a full board pays every place", full.length, LEADERBOARD_SIZE);
  assertEq("a full board costs exactly the pool",
    settlementCost(full), totalWeeklyPrizePool());
  assertEq("first place takes the top prize", full[0],
    {uid: "u0", rank: 1, weeklyXp: 100, points: 350});

  // The cost has to be bounded by the table, not by turnout.
  const overflowing = buildSettlement(board(LEADERBOARD_SIZE + 20));
  assertEq("nobody past the board is paid", overflowing.length, LEADERBOARD_SIZE);
  assertEq("an oversubscribed week still costs the pool",
    settlementCost(overflowing), totalWeeklyPrizePool());

  // The case that actually happens: quiz XP comes in tens, so exact ties are
  // common. Tied players must take adjacent ranks, not a shared higher one.
  const tied = buildSettlement([
    {uid: "a", weeklyXp: 400},
    {uid: "b", weeklyXp: 400},
    {uid: "c", weeklyXp: 400},
  ]);
  assertEq("ties take adjacent ranks", tied.map((t) => t.rank), [1, 2, 3]);
  assertEq("a tied week costs the same as any other",
    settlementCost(tied), 350 + 200 + 200);

  // A thirty-way tie is the blow-up case a shared-rank rule would produce.
  const allTied = buildSettlement(
    Array.from({length: LEADERBOARD_SIZE}, (_, i) => ({uid: `t${i}`, weeklyXp: 50}))
  );
  assertEq("a thirty-way tie is still capped at the pool",
    settlementCost(allTied), totalWeeklyPrizePool());

  // Nobody is paid for not playing.
  const withIdle = buildSettlement([
    {uid: "played", weeklyXp: 40},
    {uid: "idle", weeklyXp: 0},
    {uid: "negative", weeklyXp: -10},
  ]);
  assertEq("only players are paid", withIdle.map((p) => p.uid), ["played"]);

  assertEq("an empty week pays nothing", buildSettlement([]), []);
  assertEq("an empty week costs nothing", settlementCost([]), 0);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
