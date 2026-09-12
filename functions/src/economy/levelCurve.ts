/**
 * Pure XP -> Level curve. No Firestore, no I/O - deliberately isolated so it
 * can be unit tested without the emulator and reused wherever a level needs
 * to be derived from an XP total (Cloud Functions today, possibly the
 * Android client later for optimistic UI).
 *
 * Starting parameters, not final: fast early levels (onboarding hook),
 * slowing growth at higher levels (long-term pacing). Tune BASE_LEVEL_XP /
 * LEVEL_XP_GROWTH / MAX_LEVEL freely - everything below is derived from them.
 */

/**
 * RETUNING THESE IS A LIVE, UNIVERSAL CHANGE. READ THIS FIRST.
 *
 * These two numbers generate every threshold, so touching either one
 * re-prices EVERY level at once - there is no way to change what a single
 * level costs. Doubling BASE_LEVEL_XP moves level 10 from 954 XP to 1909, and
 * with it every other level.
 *
 * And the curve is not shipped in the APK. publishLevelCurve writes it to
 * config/levelCurve and the Android client reads that document at runtime, so
 * a retune:
 *
 *   * goes live the moment the functions deploy (and within six hours
 *     regardless - scheduledQuizAnswerKeySync republishes),
 *   * reaches every installed version, old APKs included, because they all
 *     read the same document, and
 *   * cannot be staged behind an app release or rolled out to a subset.
 *
 * RAISING THEM DEMOTES USERS AND CAN PAY LEVEL REWARDS TWICE. XP is the
 * source of truth and `level` is a cache of it, so the same stored XP now
 * maps lower: a user on 954 XP drops from level 10 to 7 on their next award
 * (buildAward repairs the cache), and as they re-earn they re-cross 8, 9 and
 * 10. Each re-crossing calls writeAward, which re-queues the level and
 * OVERWRITES its `levelup:<n>` ledger entry back to "locked" with a plain
 * set() - which is the one thing claimLevelReward's idempotency guard relies
 * on never happening. So the stars pay again, and the record that they had
 * already been paid is destroyed by the same write.
 *
 * LOWERING THEM SKIPS LEVELS SILENTLY. The jump is already baked into
 * previousLevel by the time the next award runs, so levelsCrossed comes back
 * empty: no queue entries, no ledger entries, no announcement, and no
 * backfill. The Level rewards screen then reports those levels' stars as
 * "earned" anyway, because it infers earned-ness from the current level
 * rather than from the ledger (see LevelLadder.build).
 *
 * THE SAFE WAY TO MAKE LEVELLING HARDER: extend rather than re-price. Raise
 * MAX_LEVEL and steepen only the new tail, or only raise thresholds above
 * where the highest live account already sits. Nobody is demoted, nothing is
 * re-crossed, nothing double-pays - and "harder from here on" is usually what
 * the change actually means.
 *
 * If existing levels genuinely must be re-priced, guard writeAward's
 * milestone set() first (one transaction.get per level crossed) so a
 * re-crossed level leaves its "applied" entry alone. The demotion remains,
 * but no stars leak and the ledger survives.
 */
export const MAX_LEVEL = 30;
const BASE_LEVEL_XP = 50;
const LEVEL_XP_GROWTH = 1.18;

/**
 * XP_THRESHOLDS[i] is the cumulative XP required to reach level (i + 2).
 * Level 1 requires 0 XP and is not stored here. Length is MAX_LEVEL - 1.
 */
export const XP_THRESHOLDS: readonly number[] = buildThresholds();

function buildThresholds(): number[] {
  const thresholds: number[] = [];
  let cumulative = 0;
  for (let level = 2; level <= MAX_LEVEL; level++) {
    const increment = Math.round(BASE_LEVEL_XP * Math.pow(LEVEL_XP_GROWTH, level - 2));
    cumulative += increment;
    thresholds.push(cumulative);
  }
  return thresholds;
}

/** Total XP -> current level. Never below 1, never above MAX_LEVEL. */
export function levelForXp(xp: number): number {
  if (!Number.isFinite(xp) || xp <= 0) return 1;

  let level = 1;
  for (const threshold of XP_THRESHOLDS) {
    if (xp >= threshold) {
      level++;
    } else {
      break;
    }
  }
  return level;
}

/**
 * Total XP required to reach `level` from zero. Level 1 requires 0.
 * Levels above MAX_LEVEL are clamped to MAX_LEVEL's requirement (there is
 * nothing beyond the cap yet).
 */
export function xpRequiredForLevel(level: number): number {
  if (level <= 1) return 0;
  const clamped = Math.min(level, MAX_LEVEL);
  return XP_THRESHOLDS[clamped - 2];
}

/**
 * How far a user is through their current level. The client renders a
 * progress bar from this rather than showing lifetime XP, which otherwise
 * looks like it never resets. Lifetime XP remains the stored source of truth
 * (leaderboards, the referral unlock threshold) - this is purely a view of it.
 */
export interface LevelProgress {
  level: number;
  /** XP earned since reaching the current level. */
  xpIntoLevel: number;
  /** XP the current level spans; 0 at max level. */
  xpForNextLevel: number;
  isMaxLevel: boolean;
}

export function levelProgressForXp(xp: number): LevelProgress {
  const safeXp = Number.isFinite(xp) && xp > 0 ? xp : 0;
  const level = levelForXp(safeXp);
  const floor = xpRequiredForLevel(level);
  const isMaxLevel = level >= MAX_LEVEL;

  return {
    level,
    xpIntoLevel: safeXp - floor,
    xpForNextLevel: isMaxLevel ? 0 : xpRequiredForLevel(level + 1) - floor,
    isMaxLevel,
  };
}

export interface LevelRecomputeResult {
  xp: number;
  level: number;
  previousLevel: number;
  leveledUp: boolean;
  /** Every milestone level strictly between previousLevel and level, in order. */
  levelsCrossed: number[];
}

/**
 * The "level recompute step": given a user's XP before a gain and how much
 * XP they just earned, returns the new totals plus which levels (if any)
 * were crossed. Pure - callers write the result to Firestore themselves,
 * inside whatever transaction is already awarding the XP.
 */
export function applyXpGain(currentXp: number, xpGained: number): LevelRecomputeResult {
  const safeCurrentXp = Number.isFinite(currentXp) && currentXp > 0 ? currentXp : 0;
  const safeGain = Number.isFinite(xpGained) && xpGained > 0 ? xpGained : 0;

  const previousLevel = levelForXp(safeCurrentXp);
  const newXp = safeCurrentXp + safeGain;
  const newLevel = levelForXp(newXp);

  const levelsCrossed: number[] = [];
  for (let level = previousLevel + 1; level <= newLevel; level++) {
    levelsCrossed.push(level);
  }

  return {
    xp: newXp,
    level: newLevel,
    previousLevel,
    leveledUp: newLevel > previousLevel,
    levelsCrossed,
  };
}
