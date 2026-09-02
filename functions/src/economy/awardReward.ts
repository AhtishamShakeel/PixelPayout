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
import {
  MULTIPLIER_ELIGIBLE,
  RewardSource,
  XP_MULTIPLIER_ELIGIBLE,
} from "./rewardConfig";

export interface RewardEventDoc {
  source: RewardSource;
  basePoints: number;
  multiplierEligible: boolean;
  multiplierApplied: number;
  finalPoints: number;
  /** XP before any buff, so the ledger shows what was scaled, and by how
   *  much. */
  baseXp: number;
  xpMultiplierEligible: boolean;
  xpMultiplierApplied: number;
  xpAwarded: number;
  /** The user's level BEFORE this event - see the note on multipliers below. */
  levelAtEvent: number;
  levelAfterEvent: number;
  createdAt: FieldValue;
  metadata: Record<string, unknown>;
  /**
   * "reversed" marks an entry undone by a later refund, for the audit trail.
   *
   * "locked" marks an entry that RECORDS a reward without having paid it:
   * only level-up milestones are written this way, because the stars they
   * promise are now released by watching a rewarded ad rather than by
   * crossing the level. The amount is fixed at lock time - `finalPoints`
   * holds it - so retuning the table in the console never changes what an
   * already-earned level was promised. claimLevelReward flips it to
   * "applied" in the same transaction that credits the balance, which is
   * also what stops one level paying twice.
   */
  status: "applied" | "reversed" | "locked";
  /**
   * Whether this entry moved Points, as opposed to XP alone.
   *
   * Denormalised from `finalPoints !== 0` purely so the Star activity list can
   * be a single indexed query. Firestore cannot filter on `finalPoints != 0`
   * while ordering by `createdAt` - an inequality forces the ordering onto its
   * own field - so the question is asked as an equality instead.
   *
   * It exists because the two currencies have very different frequencies:
   * quizzes and games award XP and no Points, and they are most of this
   * collection. Without this flag the wallet had to read a wide window of
   * recent entries and throw most of them away.
   *
   * Kept as one collection rather than splitting XP events into their own:
   * streak and referral awards grant BOTH currencies, so a split by currency
   * would put some XP history in each collection and leave neither able to
   * answer "how did this account reach level 20" on its own.
   */
  affectsPoints: boolean;
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
   * The user's active Points buff, if any. Only ever applied to sources
   * MULTIPLIER_ELIGIBLE allows.
   */
  activeMultiplier?: number;
  /**
   * The user's active XP buff, if any. Tracked separately from the Points
   * buff all the way down: they are different grants with different
   * eligibility, and collapsing them into one number here would make it
   * impossible for a source to be eligible for one and not the other.
   */
  activeXpMultiplier?: number;
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
 *
 * WRITTEN LOCKED, NOT PAID. Crossing the level earns the reward; watching a
 * rewarded ad releases it (see claimLevelReward). So this entry is the
 * PROMISE - it records the level, the amount and the moment it was earned -
 * and the claim is what turns it into a balance movement. Two consequences
 * follow from that and both are deliberate:
 *
 *   * `affectsPoints` is false while locked, so the Stars activity list does
 *     not show a line for money that has not moved. The claim sets it true.
 *   * `finalPoints` is the amount as it stood WHEN THE LEVEL WAS CROSSED, and
 *     the claim pays that figure rather than re-reading the table. A console
 *     retune moves what future levels are worth, never what a player was
 *     already promised.
 */
export function buildMilestoneEvent(level: number, points: number): RewardEventDoc {
  return {
    source: "LEVEL_UP",
    basePoints: points,
    multiplierEligible: MULTIPLIER_ELIGIBLE.LEVEL_UP,
    multiplierApplied: 1,
    finalPoints: points,
    baseXp: 0,
    xpMultiplierEligible: XP_MULTIPLIER_ELIGIBLE.LEVEL_UP,
    xpMultiplierApplied: 1,
    xpAwarded: 0,
    levelAtEvent: level,
    levelAfterEvent: level,
    createdAt: FieldValue.serverTimestamp(),
    metadata: {milestoneLevel: level},
    status: "locked",
    // Nothing moved yet - see the note above.
    affectsPoints: false,
  };
}

export function buildAward(
  currentPoints: number,
  currentXp: number,
  input: AwardInput
): AwardResult {
  const multiplierEligible = MULTIPLIER_ELIGIBLE[input.source];
  const xpMultiplierEligible = XP_MULTIPLIER_ELIGIBLE[input.source];

  // A multiplier is applied only when the SOURCE allows it. Eligibility is
  // never passed in by the caller, so no path can accidentally buff a
  // referral or a login reward.
  const multiplierApplied = multiplierEligible ? (input.activeMultiplier ?? 1) : 1;
  const xpMultiplierApplied = xpMultiplierEligible ?
    (input.activeXpMultiplier ?? 1) :
    1;

  const basePoints = Math.trunc(input.basePoints) || 0;
  const baseXp = Math.max(Math.trunc(input.baseXp) || 0, 0);

  // Points may be negative (a redemption); XP never is.
  const finalPoints = basePoints < 0
    ? basePoints
    : Math.round(basePoints * multiplierApplied);
  const finalXp = Math.round(baseXp * xpMultiplierApplied);

  // Levels are recomputed from the BUFFED xp, which is what makes an XP buff
  // worth having - it reaches the level curve, and through it the minLevel
  // gates on redemption options and the level-up milestone bonuses.
  const level = applyXpGain(currentXp, finalXp);

  const userUpdate: Record<string, FieldValue | number> = {};
  if (finalPoints !== 0) {
    userUpdate.points = FieldValue.increment(finalPoints);
  }
  if (finalXp !== 0) {
    userUpdate.xp = FieldValue.increment(finalXp);
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
    xpAwarded: finalXp,
    level,
    ledgerDoc: {
      source: input.source,
      basePoints,
      multiplierEligible,
      multiplierApplied,
      finalPoints,
      baseXp,
      xpMultiplierEligible,
      xpMultiplierApplied,
      xpAwarded: finalXp,
      levelAtEvent: level.previousLevel,
      levelAfterEvent: level.level,
      createdAt: FieldValue.serverTimestamp(),
      metadata: input.metadata,
      status: "applied",
      affectsPoints: finalPoints !== 0,
    },
  };
}
