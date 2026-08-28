/**
 * Daily streak: the day-boundary rule, and what each day pays.
 *
 * Pure, like the rest of this folder - it decides, the caller writes. That is
 * what lets the callable run the decision and the award inside one
 * transaction, and what makes the boundary logic testable without an emulator.
 */

/**
 * A day, counted as whole UTC days since the epoch.
 *
 * Deliberately an integer rather than a Timestamp: the only question ever
 * asked of it is "how many days between these two", and that is subtraction on
 * an integer instead of calendar arithmetic. It matches the UTC-day rule
 * checkAndResetQuizAttempts already uses, because the epoch begins at UTC
 * midnight - day N here is exactly the UTC calendar day that function tests
 * for.
 */
export function utcDayFor(epochMillis: number): number {
  return Math.floor(epochMillis / 86_400_000);
}

/** What a single day of the streak pays out. */
export interface StreakDayReward {
  points: number;
  xp: number;
  /**
   * An optional temporary buff instead of, or as well as, the flat award.
   * Nothing in the table uses this yet; it is here so adding "day 7 grants a
   * 2x XP boost" is a data change rather than a new code path.
   */
  buff?: {
    kind: "points" | "xp";
    multiplier: number;
    durationMs: number;
  };
}

/**
 * The reward for each day of the cycle, day 1 first.
 *
 * A table rather than a formula because these are product decisions that will
 * be retuned by feel, not derived. Editing this array is the whole change -
 * nothing reads day numbers directly.
 *
 * XP on most days and Points on the milestones: XP is the cheap, frequent
 * reward that keeps the streak feeling alive, and Points are the ones that
 * cost real money to honour, so they sit where they buy the most retention.
 *
 * The cycle repeats: day 8 pays what day 1 pays. A streak that runs for months
 * should not pay unboundedly more each week, and the card only ever shows
 * seven cells.
 */
export const STREAK_REWARDS: StreakDayReward[] = [
  {points: 0, xp: 10},
  {points: 0, xp: 20},
  {points: 0, xp: 30},
  {points: 10, xp: 0},
  {points: 0, xp: 50},
  {points: 0, xp: 60},
  {points: 20, xp: 0},
];

/** Length of one full cycle - what the seven-cell strip on Home draws. */
export const STREAK_CYCLE_DAYS = STREAK_REWARDS.length;

/** The reward for a given streak day (1-based), wrapping at the cycle end. */
export function streakRewardForDay(day: number): StreakDayReward {
  const index = ((Math.max(day, 1) - 1) % STREAK_CYCLE_DAYS + STREAK_CYCLE_DAYS)
    % STREAK_CYCLE_DAYS;
  return STREAK_REWARDS[index];
}

export type StreakClaim =
  | {status: "already_claimed"; day: number}
  | {status: "claimed"; day: number; continued: boolean};

/**
 * Decides what today's claim does to the streak.
 *
 * The quiz reset asks only "is it a new day". A streak has to ask the sharper
 * question - is it the NEXT day, or a later one - because that is the
 * difference between continuing and starting over:
 *
 *   same day      already claimed, nothing happens
 *   exactly +1    the streak continues
 *   +2 or more    the streak broke; today is day 1 again
 *
 * A lastDay in the future is treated as already claimed. It should be
 * impossible - the value is only ever written from server time - but the
 * alternative reading, that the streak is enormously stale and should reset,
 * would wipe a real streak on the strength of a corrupt field.
 */
export function resolveStreakClaim(
  lastDayUtc: number | null | undefined,
  todayUtc: number,
  currentCount: number
): StreakClaim {
  if (lastDayUtc !== null && lastDayUtc !== undefined && lastDayUtc >= todayUtc) {
    return {status: "already_claimed", day: Math.max(currentCount, 1)};
  }

  const continued =
    lastDayUtc !== null &&
    lastDayUtc !== undefined &&
    todayUtc - lastDayUtc === 1;

  return {
    status: "claimed",
    day: continued ? Math.max(currentCount, 0) + 1 : 1,
    continued,
  };
}

export type StreakRewardDecision =
  | {pay: true}
  | {pay: false; reason: "already_rewarded" | "no_ad"};

/**
 * Whether today's reward should be paid, which is a SEPARATE question from
 * whether the streak advances.
 *
 * The streak is retention and must never be lost to something outside the
 * user's control - an ad that would not load, a region with no fill. The
 * reward is what the ad buys. So a claim without a watched ad still moves the
 * streak on, pays nothing, and leaves the reward claimable for the rest of the
 * day: the user can retry as often as they like until an ad actually plays.
 *
 * adWatched is asserted by the client, which cannot be proven. It is still
 * worth gating on, because the case it defends against - a user blocking ads
 * with a VPN or a DNS blocker - never reaches the callback that sets it. Only
 * a repackaged app can lie here, which is a different and much smaller
 * problem. AdMob server-side verification is the real answer if the numbers
 * ever justify it; the adlessStreakClaims counter is there to tell you.
 */
export function resolveStreakReward(
  lastRewardedDayUtc: number | null | undefined,
  todayUtc: number,
  adWatched: boolean
): StreakRewardDecision {
  if (lastRewardedDayUtc === todayUtc) {
    return {pay: false, reason: "already_rewarded"};
  }
  if (!adWatched) {
    return {pay: false, reason: "no_ad"};
  }
  return {pay: true};
}
