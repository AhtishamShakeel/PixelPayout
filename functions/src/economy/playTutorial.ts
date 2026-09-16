/**
 * The Play tab's first-run tutorial, and the level it promises.
 *
 * The tutorial walks a new player through one game run and two quiz answers
 * and ends on "Level 2". Play alone cannot keep that promise: a run that dies
 * on the first pipe pays nothing and a wrong answer pays nothing, so a player
 * who follows every step can finish with no XP at all. The completion call
 * closes the gap instead - it tops XP up to exactly the level-2 threshold,
 * once per account.
 *
 * WHY THIS IS SAFE TO HAND OUT:
 *
 *   * once per account - a flag on the user document, set in the same
 *     transaction as the XP, so a retry or a second device finds it set,
 *   * bounded - a top-up TO the threshold, never a fixed grant on top of
 *     whatever the account already has, so the most it can ever pay is the
 *     threshold itself, and an account already past it gets nothing,
 *   * earned - the server checks its own ledger for the run and the answers,
 *     so a client that calls this without playing is refused,
 *   * tournament-neutral - it is not play, so claimReward's weekly counter
 *     never sees it, and the unlock level is far above level 2 anyway.
 *
 * The level-2 star bonus still goes through the normal queue (writeAward
 * locks it, claimLevelReward releases it), so the tutorial ends on the same
 * Level rewards screen as any other level-up.
 */
import {XP_THRESHOLDS} from "./levelCurve";

/** Game runs the ledger must show before the tutorial can complete. */
export const PLAY_TUTORIAL_GAMES_REQUIRED = 1;

/** Quiz answers - right or wrong - the ledger must show. */
export const PLAY_TUTORIAL_QUIZZES_REQUIRED = 2;

/** The level the tutorial ends on. */
export const PLAY_TUTORIAL_TARGET_LEVEL = 2;

/** Deterministic ledger id, so a retried transaction cannot pay twice. */
export const PLAY_TUTORIAL_LEDGER_ID = "tutorial:play";

/**
 * The XP that takes an account holding [currentXp] exactly to the target
 * level's threshold. Zero when it is already there or beyond.
 */
export function playTutorialTopUpXp(currentXp: number): number {
  const target = XP_THRESHOLDS[PLAY_TUTORIAL_TARGET_LEVEL - 2];
  const xp = Number.isFinite(currentXp) ? Math.max(Math.floor(currentXp), 0) : 0;
  return Math.max(target - xp, 0);
}

/** Whether the ledger counts are enough to call the tutorial played. */
export function playTutorialRequirementsMet(games: number, quizzes: number): boolean {
  return games >= PLAY_TUTORIAL_GAMES_REQUIRED && quizzes >= PLAY_TUTORIAL_QUIZZES_REQUIRED;
}
