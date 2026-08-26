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
