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
 *
 * ENTRY IS FREE AND AUTOMATIC, FROM LEVEL [TOURNAMENT_UNLOCK_LEVEL]. There is
 * no fee and nothing to tap: every player at or past the unlock level is in
 * every week they score. What decides a place is only the XP earned AFTER
 * unlocking - see [weeklyXpGain] - so the climb to level 10 itself never
 * counts, and a player does not arrive on the board already holding a place.
 * Taking part in a week is marked by FIELD_TOURNAMENT_WEEK, stamped by the
 * same claim that scores.
 */
import {XP_THRESHOLDS} from "./levelCurve";

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

/**
 * One document per settled week, so a re-run can tell at a glance whether it
 * has work to do without reading thirty ledger entries to find out.
 *
 * Server-only: it is not matched in firestore.rules, so the catch-all deny at
 * the bottom of that file covers it.
 */
export const LEADERBOARD_SETTLEMENTS_COLLECTION = "leaderboardSettlements";

/**
 * The level at which the tournament unlocks. Below it a player earns no
 * weekly XP, is not on the board and cannot be paid by it.
 */
export const TOURNAMENT_UNLOCK_LEVEL = 10;

/** The total XP at which [TOURNAMENT_UNLOCK_LEVEL] is reached. */
export function tournamentUnlockXp(): number {
  return XP_THRESHOLDS[TOURNAMENT_UNLOCK_LEVEL - 2];
}

/** Whether a player with [totalXp] has the tournament unlocked. */
export function isTournamentUnlocked(totalXp: number): boolean {
  return Number.isFinite(totalXp) && totalXp >= tournamentUnlockXp();
}

/**
 * How much of an XP gain counts toward the weekly board.
 *
 * Only the part earned at or past the unlock threshold. A gain that starts
 * below it and ends above it counts only what lies above, so reaching level
 * 10 is not itself a score - a player who takes 954 XP to get there starts
 * the board on zero, exactly like everyone else who unlocked earlier.
 *
 * `countedGain` is what the source is allowed to add (claimReward passes the
 * pre-buff figure, a double passes its bonus), and it is capped by the part of
 * the real XP movement - `xpBefore` to `xpAfter` - that lies past the
 * threshold. So a buff can never make more of a gain count than was earned
 * after unlocking.
 */
export function weeklyXpGain(xpBefore: number, xpAfter: number, countedGain: number): number {
  const gain = Math.max(Math.trunc(countedGain) || 0, 0);
  const before = Math.max(Math.trunc(xpBefore) || 0, 0);
  const after = Math.max(Math.trunc(xpAfter) || 0, 0);
  const pastUnlock = after - Math.max(before, tournamentUnlockXp());
  return Math.min(gain, Math.max(pastUnlock, 0));
}

/**
 * Last week's finished total, kept on the user document beside this week's.
 *
 * The reason it has to exist: the live counters are overwritten the moment a
 * user plays in a new week, and the settlement for the week that just ended
 * runs AFTER that boundary. Anyone who played between midnight on Monday and
 * the settlement would have had their winning total replaced by a fresh one
 * and would not have been found by a query for last week at all - the player
 * most likely to be at the top of the board is exactly the player most likely
 * to open the app the moment it resets.
 *
 * So the rollover copies the closing total across rather than discarding it,
 * and the settlement reads both. Two fields, written only on the one write
 * that crosses a boundary, and only for a player who was RANKED IN the week
 * being closed - so everything under lastWeekKey is a participant, and the
 * settlement's carried query needs no second filter.
 */
export const FIELD_LAST_WEEKLY_XP = "lastWeeklyXp";
export const FIELD_LAST_WEEK_KEY = "lastWeekKey";

/**
 * The week a player last took part in, as an unlocked player.
 *
 * Stamped automatically by every claim that scores weekly XP while the
 * tournament is unlocked - there is no entry step. Kept apart from weekKey on
 * purpose: weekKey says which week weeklyXp belongs to and moves on every
 * player's first claim of a week, unlocked or not, while this moves only for
 * players the board can rank. The board and the settlement filter on both -
 * see the composite index on (tournamentWeek, weekKey, weeklyXp).
 */
export const FIELD_TOURNAMENT_WEEK = "tournamentWeek";

/**
 * The week a settlement running now should pay: the one that has just ended.
 *
 * Derived from the clock rather than from the schedule, so a job that fires
 * late - or is triggered by hand on a Wednesday to catch up - still settles
 * the right week instead of whichever one the trigger happened to imply.
 */
export function settlementWeekFor(nowMillis: number): number {
  return utcWeekFor(nowMillis) - 1;
}

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
/** One winner's share of a settled week. */
export interface SettlementEntry {
  uid: string;
  rank: number;
  weeklyXp: number;
  points: number;
}

/**
 * The one ordering of a board: XP descending, then uid DESCENDING.
 *
 * The uid half is not a preference - it is what Firestore does. A query
 * ordered by weeklyXp desc with no explicit tie-breaker breaks ties on
 * __name__ in the direction of the last orderBy, which is descending (the
 * board's composite index is defined with `__name__ DESC`). The live board is
 * read in that order, so every in-code sort of a board has to use it too, or
 * two players on equal XP would be shown in one order and paid in the other.
 *
 * Shared by the settlement merge and the caller-row splice for exactly that
 * reason: they once disagreed - the merge sorted ties ascending - and a tie at
 * a band edge would have handed the bigger prize to the player the screen
 * ranked below.
 */
export function compareStanding(
  a: {uid: string; xp: number},
  b: {uid: string; xp: number}
): number {
  return (b.xp - a.xp) || (a.uid < b.uid ? 1 : a.uid > b.uid ? -1 : 0);
}

/**
 * The board a settlement should pay, from the two places last week can hide.
 *
 * `current` is everyone whose live counters still read the settled week -
 * players who have not touched the app since it ended. `carried` is everyone
 * who has played since, whose closing total was preserved by the rollover.
 * Neither list is the board on its own, and the missing half is always the
 * more active one.
 *
 * ORDERED THE WAY FIRESTORE ORDERS IT - see compareStanding. Two players on the
 * same XP are separated by document name in the live board's query, and this
 * merge has to reproduce that or a settlement would rank ties differently from
 * the screen that promised the prize.
 *
 * A uid can only appear in one list - the rollover never writes the same week
 * into both the live and the carried key - but it is deduplicated anyway,
 * keeping the higher total, because paying somebody twice for one week is the
 * failure this whole path exists to avoid.
 */
export function mergeSettlementBoard(
  current: Array<{uid: string; weeklyXp: number}>,
  carried: Array<{uid: string; weeklyXp: number}>
): Array<{uid: string; weeklyXp: number}> {
  const best = new Map<string, number>();

  for (const entry of [...current, ...carried]) {
    const xp = Math.max(Math.trunc(entry.weeklyXp) || 0, 0);
    const seen = best.get(entry.uid);
    if (seen === undefined || xp > seen) best.set(entry.uid, xp);
  }

  return [...best.entries()]
    .map(([uid, weeklyXp]) => ({uid, weeklyXp}))
    .sort((a, b) =>
      compareStanding({uid: a.uid, xp: a.weeklyXp}, {uid: b.uid, xp: b.weeklyXp})
    )
    .slice(0, LEADERBOARD_SIZE);
}

/**
 * Turns an ordered board into the list of payouts.
 *
 * Pure, so the one decision that moves real money out of the business can be
 * tested without an emulator and without a database full of fixtures.
 *
 * RANK IS POSITION IN THE LIST, ties included. Two players on identical XP
 * take adjacent ranks and adjacent prizes, decided by Firestore's ordering
 * (document name, for equal weeklyXp) rather than by who got there first.
 * That is not the fairest rule imaginable, but it is the only one that keeps
 * the weekly outgoing bounded at [totalWeeklyPrizePool]: paying every tied
 * player the higher band would make a week where thirty players all finish on
 * the same score cost thirty first prizes. Exact ties are likely here - quiz
 * XP comes in tens and a game session caps at thirty - so this is a case that
 * will really happen, not a hypothetical.
 *
 * getLeaderboard ranks the same way for exactly this reason, so what the
 * board showed and what the settlement pays cannot disagree.
 *
 * Entries with no XP are dropped rather than paid. They can only be trailing
 * ones - the caller orders by XP descending - so dropping them never shifts
 * anybody else's rank.
 */
export function buildSettlement(
  ordered: Array<{uid: string; weeklyXp: number}>
): SettlementEntry[] {
  const payouts: SettlementEntry[] = [];

  ordered.forEach((entry, index) => {
    const rank = index + 1;
    if (rank > LEADERBOARD_SIZE) return;

    const weeklyXp = Math.max(Math.trunc(entry.weeklyXp) || 0, 0);
    if (weeklyXp <= 0) return;

    const points = prizeForRank(rank);
    if (points <= 0) return;

    payouts.push({uid: entry.uid, rank, weeklyXp, points});
  });

  return payouts;
}

/** What a settlement will cost, before any of it is written. */
export function settlementCost(payouts: SettlementEntry[]): number {
  return payouts.reduce((sum, payout) => sum + payout.points, 0);
}

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

/**
 * Everything a claim has to write to the weekly counters, rollover included.
 *
 * [nextWeeklyXp] answers what this week's total becomes; this answers what
 * the document should look like afterwards, which is a different question on
 * the one claim in a week that crosses a boundary. On that claim the closing
 * total of the week being left is copied to [FIELD_LAST_WEEKLY_XP] so the
 * settlement can still find it.
 *
 * The carry fields come back only when there is genuinely a finished week to
 * preserve - a real stored week, with a real total on it. Returning them on
 * every claim would rewrite two fields on every reward for nothing, and
 * writing a zero-XP carry would put users who never scored into a query that
 * only wants winners.
 */
export interface WeeklyRollover {
  weekKey: number;
  weeklyXp: number;
  /** Set only when this claim is the one that crosses into a new week. */
  lastWeekKey?: number;
  lastWeeklyXp?: number;
}

/**
 * @param storedTournamentWeek the week the player last took part in. The
 *   closing total is carried only when it names the week being closed: a week
 *   the player was not ranked in can never be paid, so carrying it would be a
 *   write for nothing and a row the settlement would then have to filter back
 *   out.
 */
export function weeklyRollover(
  storedWeekKey: number | null | undefined,
  storedWeeklyXp: number | null | undefined,
  currentWeekKey: number,
  xpAwarded: number,
  storedTournamentWeek: number | null | undefined
): WeeklyRollover {
  const weeklyXp = nextWeeklyXp(
    storedWeekKey, storedWeeklyXp, currentWeekKey, xpAwarded
  );
  const rollover: WeeklyRollover = {weekKey: currentWeekKey, weeklyXp};

  if (typeof storedWeekKey !== "number" || storedWeekKey === currentWeekKey) {
    return rollover;
  }
  if (storedTournamentWeek !== storedWeekKey) return rollover;

  const closing = Math.max(Math.trunc(storedWeeklyXp as number) || 0, 0);
  if (closing <= 0) return rollover;

  rollover.lastWeekKey = storedWeekKey;
  rollover.lastWeeklyXp = closing;
  return rollover;
}

/** One place on the live board, as getLeaderboard serves it. */
export interface BoardRow {
  uid: string;
  name: string;
  xp: number;
}

/**
 * The cached board with the caller's own row replaced by a fresh one.
 *
 * The top of the board is cached for a minute per function instance, but the
 * caller's own document is read fresh on every getLeaderboard call anyway. So
 * their row - the one row on the screen they are sure to be watching - is
 * taken from that read rather than from the cache: an entry, or a quiz just
 * finished, shows on the board at once instead of a minute later. Everyone
 * else's movement still waits for the cache, which is what keeps it cheap.
 *
 * `caller` is null when they should not be on the board at all (not entered,
 * or no XP yet); any stale row of theirs is still removed.
 *
 * ORDERED THE WAY THE LIVE QUERY IS - see compareStanding - or a tied caller
 * would sit on the wrong side of the rows they are spliced among.
 *
 * Pure: never mutates `board`, which is the shared cached array.
 */
export function withCallerRow(
  board: BoardRow[],
  callerUid: string,
  caller: BoardRow | null,
  size: number = LEADERBOARD_SIZE
): BoardRow[] {
  const others = board.filter((row) => row.uid !== callerUid);
  if (!caller || !(caller.xp > 0)) return others.slice(0, size);

  return [...others, caller].sort(compareStanding).slice(0, size);
}

/** Whether the player took part in the running week. */
export function isRankedInWeek(
  storedTournamentWeek: number | null | undefined,
  currentWeekKey: number
): boolean {
  return typeof storedTournamentWeek === "number" &&
    storedTournamentWeek === currentWeekKey;
}
