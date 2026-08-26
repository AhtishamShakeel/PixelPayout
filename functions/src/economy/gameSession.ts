/**
 * Game session validation - the anti-abuse baseline for game rewards.
 *
 * The HTML5 games run in a WebView and report their own score through a
 * JavascriptInterface, so the score is client-controlled by construction.
 * We can't verify it's a real score without rewriting the games themselves,
 * but we can bound how absurd it's allowed to be:
 *
 *   1. A claim must present a session started server-side (so a claim can't
 *      be conjured without at least going through the app's game flow).
 *   2. A session is single-use (also gives game claims a natural idempotency
 *      key, which they previously lacked).
 *   3. The score must be plausible for the elapsed wall-clock time.
 *
 * Deliberately NOT: score checkpointing, replay simulation, or anything
 * requiring changes to the external games. The exposure here is XP-only.
 */

export const GAME_SESSIONS_SUBCOLLECTION = "gameSessions";

export interface GameRules {
  /** Highest believable score per second of play, generous on purpose. */
  maxScorePerSecond: number;
  /** Absolute cap, independent of duration. */
  maxScore: number;
}

export const GAME_RULES: Record<string, GameRules> = {
  floppy_bird: {maxScorePerSecond: 2, maxScore: 100_000},
  game_2048: {maxScorePerSecond: 200, maxScore: 1_000_000},
};

/** A session shorter than this didn't involve real play. */
export const MIN_SESSION_MS = 3_000;
/** Sessions older than this are stale and must be restarted. */
export const MAX_SESSION_AGE_MS = 4 * 60 * 60 * 1000;

export function isKnownGame(gameId: string): boolean {
  return Object.prototype.hasOwnProperty.call(GAME_RULES, gameId);
}

export type SessionRejection =
  | "unknown_game"
  | "too_fast"
  | "stale_session"
  | "score_out_of_range"
  | "implausible_rate";

export interface SessionValidation {
  valid: boolean;
  rejection?: SessionRejection;
}

/**
 * Pure plausibility check for a completed session. Callers supply the elapsed
 * time (server timestamps at both ends - never client-reported duration).
 */
export function validateGameClaim(input: {
  gameId: string;
  score: number;
  elapsedMs: number;
}): SessionValidation {
  const rules = GAME_RULES[input.gameId];
  if (!rules) return {valid: false, rejection: "unknown_game"};

  if (!Number.isFinite(input.score) || input.score < 0 || input.score > rules.maxScore) {
    return {valid: false, rejection: "score_out_of_range"};
  }

  if (!Number.isFinite(input.elapsedMs) || input.elapsedMs < MIN_SESSION_MS) {
    return {valid: false, rejection: "too_fast"};
  }

  if (input.elapsedMs > MAX_SESSION_AGE_MS) {
    return {valid: false, rejection: "stale_session"};
  }

  const maxPlausibleScore = (input.elapsedMs / 1000) * rules.maxScorePerSecond;
  if (input.score > maxPlausibleScore) {
    return {valid: false, rejection: "implausible_rate"};
  }

  return {valid: true};
}
