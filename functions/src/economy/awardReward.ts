/**
 * The single place economy mutations are computed.
 *
 * Nothing anywhere should be doing `points += n` or `xp += n` directly. Every
 * earning path describes what it wants to award (source, base points, base XP)
 * and this module turns that into the exact Firestore writes plus the ledger
 * entry that records why it happened.
 *
 * Deliberately pure: it takes the user's current state and returns the writes,
 * rather than performing them. The caller owns the transaction, which lets a
 * single transaction combine an award with its own conditions (a quiz attempt
 * counter, a game session burn) and stay atomic.
 */
import {FieldValue} from "firebase-admin/firestore";
import {applyXpGain, LevelRecomputeResult} from "./levelCurve";
import {MULTIPLIER_ELIGIBLE, RewardSource} from "./rewardConfig";

export interface RewardEventDoc {
  source: RewardSource;
  basePoints: number;
  multiplierEligible: boolean;
  multiplierApplied: number;
  finalPoints: number;
  xpAwarded: number;
  /** The user's level BEFORE this event - see the note on multipliers below. */
  levelAtEvent: number;
  levelAfterEvent: number;
  createdAt: FieldValue;
  metadata: Record<string, unknown>;
  /** "reversed" marks an entry undone by a later refund, for the audit trail. */
  status: "applied" | "reversed";
}

export interface AwardInput {
  source: RewardSource;
  basePoints: number;
  baseXp: number;
  metadata: Record<string, unknown>;
  /**
   * The level currently stored on the user document. XP is the source of
   * truth and `level` is only a cache of it, so passing the stored value lets
   * an award repair a cache that has drifted - which is exactly what happens
   * to every user the moment the level curve is retuned.
   */
  storedLevel?: number;
  /**
   * The user's active points buff, if any. Not implemented yet (always 1),
   * but threaded through so the buff system is a value change, not a
   * restructure. Only ever applied to multiplier-eligible sources.
   */
  activeMultiplier?: number;
}

export interface AwardResult {
  /** Fields to merge into the user document update. */
  userUpdate: Record<string, FieldValue | number>;
  ledgerDoc: RewardEventDoc;
  pointsAwarded: number;
  xpAwarded: number;
  level: LevelRecomputeResult;
}

/**
 * A one-time bonus for reaching a milestone level. Built directly rather than
 * through buildAward, because the level here is the milestone that was just
 * reached - not something to recompute from an XP gain (this awards no XP).
 */
export function buildMilestoneEvent(level: number, points: number): RewardEventDoc {
  return {
    source: "LEVEL_UP",
    basePoints: points,
    multiplierEligible: MULTIPLIER_ELIGIBLE.LEVEL_UP,
    multiplierApplied: 1,
    finalPoints: points,
    xpAwarded: 0,
    levelAtEvent: level,
    levelAfterEvent: level,
    createdAt: FieldValue.serverTimestamp(),
    metadata: {milestoneLevel: level},
    status: "applied",
  };
}

export function buildAward(
  currentPoints: number,
  currentXp: number,
  input: AwardInput
): AwardResult {
  const multiplierEligible = MULTIPLIER_ELIGIBLE[input.source];

  // A multiplier is applied only when the SOURCE allows it. Eligibility is
  // never passed in by the caller, so no path can accidentally buff a
  // referral or a login reward.
  const multiplierApplied = multiplierEligible ? (input.activeMultiplier ?? 1) : 1;

  const basePoints = Math.trunc(input.basePoints) || 0;
  const baseXp = Math.max(Math.trunc(input.baseXp) || 0, 0);

  // Points may be negative (a redemption); XP never is.
  const finalPoints = basePoints < 0
    ? basePoints
    : Math.round(basePoints * multiplierApplied);

  const level = applyXpGain(currentXp, baseXp);

  const userUpdate: Record<string, FieldValue | number> = {};
  if (finalPoints !== 0) {
    userUpdate.points = FieldValue.increment(finalPoints);
  }
  if (baseXp !== 0) {
    userUpdate.xp = FieldValue.increment(baseXp);
  }
  // Written when the XP gain crosses a threshold, and also whenever the
  // stored level disagrees with what the XP says - otherwise a stale cached
  // level would persist until the user happened to cross a new threshold.
  const storedLevel = Number.isFinite(input.storedLevel as number) ?
    (input.storedLevel as number) :
    level.previousLevel;

  if (level.leveledUp || level.level !== storedLevel) {
    userUpdate.level = level.level;
  }

  return {
    userUpdate,
    pointsAwarded: finalPoints,
    xpAwarded: baseXp,
    level,
    ledgerDoc: {
      source: input.source,
      basePoints,
      multiplierEligible,
      multiplierApplied,
      finalPoints,
      xpAwarded: baseXp,
      levelAtEvent: level.previousLevel,
      levelAfterEvent: level.level,
      createdAt: FieldValue.serverTimestamp(),
      metadata: input.metadata,
      status: "applied",
    },
  };
}
