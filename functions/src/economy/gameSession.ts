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
 * Deliberately NOT: score checkpointing or replay simulation. We host the
 * games ourselves now, so that is no longer out of reach - but the client
 * still runs the code either way, and the exposure here is XP-only.
 */

import {GAME_XP_PER_SESSION_CAP, GAME_XP_SCORE_DIVISOR} from "./rewardConfig";

export const GAME_SESSIONS_SUBCOLLECTION = "gameSessions";

export interface GameRules {
  /** Highest believable score per second of play, generous on purpose. */
  maxScorePerSecond: number;
  /** Absolute cap, independent of duration. */
  maxScore: number;
}

export const GAME_RULES: Record<string, GameRules> = {
  // One point per pipe, and the game's own physics say a pipe cannot arrive
  // faster than once a second: PIPE_SPEED is H * 0.42 per second while
  // PIPE_SPACING is at least H * 0.42. The old bound of 2/s allowed exactly
  // twice the fastest rate the game can produce, which at a 60 XP ceiling let
  // a tampered client claim a maxed run in half the time a person needs.
  // 1.1 leaves room for frame-timing jitter and nothing else.
  floppy_bird: {maxScorePerSecond: 1.1, maxScore: 100_000},
  // Tower pays 25 a block plus 10 per block of an unbroken perfect streak
  // (see public/games/tower/index.html). Reaching the XP ceiling needs about
  // fifteen perfect blocks, and a block cycle - swing, drop, settle - is well
  // over a second, so ~15s is the floor for a maxed run. 100/s puts the bound
  // exactly there.
  tower_game: {maxScorePerSecond: 100, maxScore: 200_000},
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

  // THE RATE IS TESTED AGAINST THE SCORE THAT CAN ACTUALLY PAY, not the raw
  // total. Above GAME_XP_PER_SESSION_CAP * the game's divisor, more score
  // earns nothing, so there is no reason to police it - and every reason not
  // to. Tower's score is quadratic in a perfect streak while time is linear,
  // so a genuinely excellent long run has a HIGHER average rate than a short
  // one: a two-minute flawless run averages about 165/s. Policing the raw
  // total would mean either rejecting that player or setting the bound so
  // high it stops protecting anything.
  //
  // Capping first fixes both ends. The bound can be tight enough to make
  // reaching the XP ceiling take real time, while a long flawless run is
  // never punished for the points it piled up after the ceiling.
  //
  // Absurd raw totals are still refused by maxScore above, and the raw score
  // is still what gets written to the ledger - which is where botting is
  // actually detected, from the shape of a user's sessions over time rather
  // than from any single claim.
  const payingScore = Math.min(
    input.score,
    GAME_XP_PER_SESSION_CAP * (GAME_XP_SCORE_DIVISOR[input.gameId] ?? 1)
  );
  const maxPlausibleScore = (input.elapsedMs / 1000) * rules.maxScorePerSecond;
  if (payingScore > maxPlausibleScore) {
    return {valid: false, rejection: "implausible_rate"};
  }

  return {valid: true};
}
