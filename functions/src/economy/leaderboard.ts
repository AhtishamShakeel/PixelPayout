/**
 * The weekly leaderboard: XP earned from playing, ranked, reset every week.
 *
 * Pure, like the rest of this folder - it decides, the caller writes.
 *
 * What it counts is a deliberate choice. Only XP from claimReward - quizzes
 * and games - accrues here, not the XP from streaks or referrals. The board
 * measures play, and counting passive XP would let someone rank by signing up
 * friends and opening the app once a day rather than by playing. Those still
 * level you up; they just do not place you.
 */

/**
 * A week, counted as whole weeks since the epoch, starting Monday.
 *
 * Epoch day 0 was a Thursday, so the +3 shifts the boundary back to the
 * preceding Monday. An integer for the same reasons the daily counters use
 * one: comparing and resetting are integer operations, and it is legible in a
 * console.
 */
export function utcWeekFor(epochMillis: number): number {
  const day = Math.floor(epochMillis / 86_400_000);
  return Math.floor((day + 3) / 7);
}

/** The Monday that a given week index begins on, as epoch millis. */
export function weekStartMillis(weekIndex: number): number {
  return (weekIndex * 7 - 3) * 86_400_000;
}

/** When a week ends - the instant the next one begins. */
export function weekEndMillis(weekIndex: number): number {
  return weekStartMillis(weekIndex + 1);
}

/**
 * How many places the board shows, and the deepest a prize can reach.
 *
 * The two are the same number on purpose. If prizes reached further than the
 * board, someone could win without ever being able to see themselves on it -
 * which reads as a bug however carefully it is explained.
 */
export const LEADERBOARD_SIZE = 30;

/**
 * How many places Home shows without being asked.
 *
 * The row on Home is about the caller's own standing; the podium is context.
 * Sending a hundred rows to draw three of them is wasted payload on every
 * resume.
 */
export const LEADERBOARD_PREVIEW_SIZE = 3;

/**
 * What each rank wins, as a list of bands rather than a hundred entries.
 *
 * Bands because the shape of a prize table is "the top few get a lot, the next
 * several get less, everyone in the top hundred gets something" - and writing
 * that as ranges keeps it readable and cheap to retune.
 *
 * Nothing pays these out yet. The table is here so the board can honestly say
 * what is at stake before the distribution job exists.
 */
export interface PrizeBand {
  /** Inclusive, 1-based. */
  fromRank: number;
  toRank: number;
  points: number;
}

export const LEADERBOARD_PRIZES: PrizeBand[] = [
  {fromRank: 1, toRank: 1, points: 350},
  {fromRank: 2, toRank: 3, points: 200},
  {fromRank: 4, toRank: 10, points: 100},
  {fromRank: 11, toRank: 30, points: 50},
];

/** What one rank wins, or zero if it is outside every band. */
export function prizeForRank(rank: number): number {
  if (!Number.isInteger(rank) || rank < 1) return 0;
  const band = LEADERBOARD_PRIZES.find(
    (b) => rank >= b.fromRank && rank <= b.toRank
  );
  return band ? band.points : 0;
}

/**
 * What a full week of prizes costs, every rank added up.
 *
 * Worth being able to state in one number: this is a fixed weekly outgoing
 * that does not scale with how many people play, so it has to be set against
 * revenue in absolute terms rather than per user.
 */
export function totalWeeklyPrizePool(): number {
  return LEADERBOARD_PRIZES.reduce(
    (sum, band) => sum + (band.toRank - band.fromRank + 1) * band.points,
    0
  );
}

/**
 * The weekly XP to store, given what was already there.
 *
 * The reset is lazy - a stale week is overwritten rather than added to - so no
 * scheduled job has to touch every user document at the week boundary. A user
 * who does not play simply never has last week's figure read again.
 */
export function nextWeeklyXp(
  storedWeekKey: number | null | undefined,
  storedWeeklyXp: number | null | undefined,
  currentWeekKey: number,
  xpAwarded: number
): number {
  const gain = Math.max(Math.trunc(xpAwarded) || 0, 0);
  if (storedWeekKey !== currentWeekKey) return gain;
  return Math.max(Math.trunc(storedWeeklyXp as number) || 0, 0) + gain;
}
