/**
 * Every economy value in one place.
 *
 * These numbers are deliberately all here rather than scattered through the
 * reward paths, so rebalancing the economy is a single-file change. They are
 * starting values, not final ones - see the notes on each block.
 */

/** Every economy mutation carries one of these. New earning paths add cases. */
export type RewardSource =
  | "QUIZ"
  | "GAME"
  | "REFERRAL_REFEREE"
  | "REFERRAL_REFERRER"
  // Not wired up yet, but named now so the ledger's vocabulary is stable:
  | "OFFERWALL"
  | "SURVEY"
  | "SPONSORED_APP"
  | "DAILY_LOGIN"
  | "STREAK"
  | "MISSION"
  | "ACHIEVEMENT"
  | "LEADERBOARD"
  | "PROMOTION"
  | "LEVEL_UP"
  | "ADMIN_GRANT"
  | "REDEMPTION";

/**
 * Whether a source's Points are eligible for the (future) temporary points
 * buff. This is a property of the SOURCE, never a per-call argument, so no
 * code path can accidentally apply a buff to referrals or login rewards.
 *
 * Everything here is false today because no source that earns Points is
 * buff-eligible yet - the eligible ones (offerwall/survey/sponsored) don't
 * exist. The table states each source's real intent so the buff system can
 * land without revisiting every call site.
 */
export const MULTIPLIER_ELIGIBLE: Record<RewardSource, boolean> = {
  QUIZ: false, // XP only
  GAME: false, // XP only
  REFERRAL_REFEREE: false, // fixed acquisition cost
  REFERRAL_REFERRER: false, // fixed acquisition cost
  OFFERWALL: true,
  SURVEY: true,
  SPONSORED_APP: true,
  DAILY_LOGIN: false, // frictionless; must stay small and fixed
  STREAK: false,
  MISSION: false,
  ACHIEVEMENT: false,
  LEADERBOARD: false,
  PROMOTION: false,
  LEVEL_UP: false,
  ADMIN_GRANT: false,
  REDEMPTION: false, // spends, never earns
};

/**
 * The same question for XP. Separate table rather than a flag on the one
 * above, because the answers genuinely differ: the Points buff deliberately
 * skips quizzes and games so play cannot be farmed for currency, while an XP
 * buff is only interesting BECAUSE it applies to them - they are the sole
 * sources of XP today.
 *
 * XP is not currency, but it is not free either: level gates redemption
 * options through minLevel, and crossing a level pays a milestone Points
 * bonus. So a buff here does reach real value, just indirectly.
 *
 * Referrals and level-up bonuses stay false: both are fixed, one-off awards
 * whose size is the whole point of them.
 */
export const XP_MULTIPLIER_ELIGIBLE: Record<RewardSource, boolean> = {
  QUIZ: true,
  GAME: true,
  REFERRAL_REFEREE: false, // fixed acquisition cost
  REFERRAL_REFERRER: false, // fixed acquisition cost
  OFFERWALL: true,
  SURVEY: true,
  SPONSORED_APP: true,
  DAILY_LOGIN: false, // frictionless; must stay small and fixed
  STREAK: false,
  MISSION: false,
  ACHIEVEMENT: false,
  LEADERBOARD: false,
  PROMOTION: false,
  LEVEL_UP: false, // the milestone award itself is never scaled
  ADMIN_GRANT: false,
  REDEMPTION: false, // spends, never earns
};

// --- Quiz -------------------------------------------------------------------
// Quizzes are an engagement activity: XP only, no redeemable Points.
// A wrong answer earns nothing, mirroring the old points behaviour.
export const QUIZ_CORRECT_XP = 10;
export const QUIZ_INCORRECT_XP = 0;
export const MAX_DAILY_QUIZ_ATTEMPTS = 10;

// --- Games ------------------------------------------------------------------
// Also XP only. Raw game scores vary wildly in scale (a tower_game score runs
// to the thousands while a floppy_bird score is tens), so each game divides
// its score down, and every session is capped so one lucky run can't shortcut
// the level curve. The divisors are picked so a point of XP costs roughly the
// same effort in either game: one pipe, or one stacked block.
export const GAME_XP_PER_SESSION_CAP = 60;
// Games get the same daily allowance as quizzes, on the same UTC rollover and
// the same stored day stamp (FIELD_LAST_RESET_TIME), so whichever activity the
// user does first on a new day resets both counters.
export const MAX_DAILY_GAME_SESSIONS = 10;

/**
 * Extra attempts bought with a rewarded ad, per activity, per day.
 *
 * The day's ceiling is therefore 13 games plus 13 quizzes for someone who
 * watches every ad, and the predictable 10 plus 10 for everyone else. It is
 * bounded either way, which is the property that matters: nothing here is an
 * open-ended grind.
 *
 * THIS NUMBER IS THE SECURITY. The ad is taken on the client's word - there
 * is no server-side ad verification - so a client that lies about having
 * watched one still gets no further than a patient honest user does. Every
 * other defence would be defeated by the same lie; this one is not.
 *
 * Retuning it is a payout decision rather than a UX one. Extra attempts feed
 * XP, and through XP the level curve and its milestone stars; they also feed
 * the daily-goal targets, whose bonus pays redeemable Points, and weekly
 * leaderboard standing, which settles for real.
 */
export const MAX_DAILY_BONUS_ATTEMPTS = 3;

/**
 * How many attempts an activity actually allows today.
 *
 * Clamped rather than added straight, because the stored bonus count is data:
 * a console edit, a bad migration or a future bug that puts 99 in that field
 * must not open the day up. The floor earns its place too - a negative would
 * silently take the allowance BELOW what every user is entitled to without
 * watching anything, which is the more embarrassing of the two failures.
 */
export function attemptsAllowance(base: number, bonusGranted: number): number {
  const bonus = Number.isFinite(bonusGranted) ? Math.floor(bonusGranted) : 0;
  return base + Math.min(Math.max(bonus, 0), MAX_DAILY_BONUS_ATTEMPTS);
}
export const GAME_XP_SCORE_DIVISOR: Record<string, number> = {
  floppy_bird: 1,
  tower_game: 25,
};

export function gameXpForScore(gameId: string, score: number): number {
  const divisor = GAME_XP_SCORE_DIVISOR[gameId];
  if (!divisor || !Number.isFinite(score) || score <= 0) return 0;
  return Math.min(Math.floor(score / divisor), GAME_XP_PER_SESSION_CAP);
}

// --- Referrals --------------------------------------------------------------
// Referral rewards are an acquisition cost: fixed, never buffed.
// Both sides get Points (referrals are one of the few Points sources today)
// and XP.
export const REFERRED_USER_REWARD_POINTS = 50;
export const REFERRED_USER_REWARD_XP = 25;
export const REFERRER_REWARD_POINTS = 100;
export const REFERRER_REWARD_XP = 50;

// --- Level rewards ------------------------------------------------------------
// Levelling is the payoff for quizzes and games, which award no Points of
// their own. Rather than a permanent earning multiplier (an unbounded,
// hard-to-model ongoing cost), each level pays a fixed one-time bonus -
// finite per user, and easy to total up in advance.
//
// EVERY level pays now, not just the six round-numbered ones this used to
// list. Under the old table a player could climb four levels and be given
// nothing at all for it, which made levelling feel like it did not pay - the
// exact opposite of what it is for.
//
// The ramp is roughly geometric: 5 Points at level 2, doubling every five or
// so levels, 150 at level 30. Early levels come fast and pay small; the late
// ones are rare and pay enough to be worth the climb.
//
// LEVEL 1 IS ABSENT ON PURPOSE. Rewards are paid for levels CROSSED, and
// nobody crosses into level 1 - every account is created there (see
// buildNewUserProfile). An entry here would never pay out, so listing one
// would put a number on the Level rewards screen that no code honours.
//
// Lifetime cost of the full ladder is 1,282 Points per user across the whole
// 30-level curve, up from 650 under the old six-milestone table. The unit
// test below asserts that total, so changing a value here fails the suite
// until the figure is updated deliberately.
export const LEVEL_UP_POINTS: Record<number, number> = {
  2: 5,
  3: 6,
  4: 7,
  5: 8,
  6: 9,
  7: 10,
  8: 11,
  9: 12,
  10: 14,
  11: 16,
  12: 18,
  13: 20,
  14: 22,
  15: 25,
  16: 28,
  17: 31,
  18: 35,
  19: 40,
  20: 45,
  21: 50,
  22: 55,
  23: 65,
  24: 70,
  25: 80,
  26: 90,
  27: 105,
  28: 120,
  29: 135,
  30: 150,
};

/**
 * Validates a reward table read from Firestore, returning null - meaning
 * "fall back to the deployed table" - for anything that is not a map of
 * non-negative integer points keyed by a level in range.
 *
 * Strict on purpose, and ALL-OR-NOTHING: config/levelCurve.levelRewards is
 * hand-editable in the console, and a typo in it moves real money. A table
 * with one bad entry is rejected whole rather than partially honoured,
 * because silently dropping the level somebody just typed is the failure they
 * would not notice - they would see the other levels still paying and assume
 * the edit took.
 *
 * @param maxLevel the curve's top level; entries above it can never be
 *   crossed, so a table containing one is a mistake rather than a plan.
 */
export function parseLevelRewards(
  raw: unknown,
  maxLevel: number
): Record<number, number> | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;

  const table: Record<number, number> = {};
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    // Number("") is 0 and Number(" 3 ") is 3, so an empty or padded key would
    // otherwise slip through as a plausible level.
    if (key.trim() === "" || key.trim() !== key) return null;
    const level = Number(key);
    const points = Number(value);
    if (!Number.isInteger(level) || level < 1 || level > maxLevel) return null;
    if (typeof value !== "number" || !Number.isInteger(points) || points < 0) {
      return null;
    }
    if (points > 0) table[level] = points;
  }

  return Object.keys(table).length > 0 ? table : null;
}

/**
 * The reward for every level crossed by a single XP gain. A large enough gain
 * can cross several at once, and each one must pay - hence a list, not a
 * lookup of the final level only.
 *
 * @param table the live reward table. Defaults to the deployed one, but the
 *   server passes the copy read from config/levelCurve so the numbers can be
 *   retuned in the console without a deploy.
 */
export function levelUpPointsForLevels(
  levels: number[],
  table: Record<number, number> = LEVEL_UP_POINTS
): Array<{level: number; points: number}> {
  return levels
    .map((level) => ({level, points: table[level] ?? 0}))
    .filter((reward) => reward.points > 0);
}

/**
 * The referrer is only paid once the referee shows real engagement. This was
 * previously "referee reaches 100 points", which breaks now that quizzes and
 * games award no Points at all - a genuinely engaged referee could sit at 50
 * points forever and never pay out. XP is the signal that actually tracks
 * engagement across every activity, and (unlike Points) it can never be spent
 * back down below the threshold.
 */
export const REFERRAL_UNLOCK_XP = 100;
