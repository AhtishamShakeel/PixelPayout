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
// Also XP only. Raw game scores vary wildly in scale (a 2048 score can reach
// tens of thousands while a floppy_bird score is tens), so each game divides
// its score down, and every session is capped so one lucky run can't shortcut
// the level curve.
export const GAME_XP_PER_SESSION_CAP = 30;
export const GAME_XP_SCORE_DIVISOR: Record<string, number> = {
  floppy_bird: 1,
  game_2048: 20,
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

// --- Level milestones ---------------------------------------------------------
// Levelling is the payoff for quizzes and games, which award no Points of
// their own. Rather than a permanent earning multiplier (an unbounded,
// hard-to-model ongoing cost), each milestone level pays a fixed one-time
// bonus - finite per user, and easy to total up in advance.
//
// Lifetime cost of the full ladder below is 650 Points per user, spread over
// the whole 30-level curve. Levels not listed here award no Points; they
// still count as progression.
export const LEVEL_MILESTONE_POINTS: Record<number, number> = {
  5: 25,
  10: 50,
  15: 75,
  20: 100,
  25: 150,
  30: 250,
};

/**
 * Milestones for every level crossed by a single XP gain. A large enough gain
 * can cross several at once, and each one must pay - hence a list, not a
 * lookup of the final level only.
 */
export function milestonePointsForLevels(
  levels: number[]
): Array<{level: number; points: number}> {
  return levels
    .map((level) => ({level, points: LEVEL_MILESTONE_POINTS[level] ?? 0}))
    .filter((milestone) => milestone.points > 0);
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
