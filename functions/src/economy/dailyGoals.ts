/**
 * Daily goals: three tasks a day, and an XP bonus for finishing all three.
 *
 * Pure, like the rest of this folder - it decides, the caller writes.
 *
 * The handoff shows goals that complete when tapped. That cannot work here:
 * a goal the client can mark done is a button that prints Points. Every goal
 * in this file is measured from counters the server increments inside the same
 * transaction that awards a quiz or a game, so "done" means the activity
 * actually happened.
 */

/**
 * What a goal measures.
 *
 * Deliberately only things claimReward can prove. There is no "earn N stars"
 * goal: stars are hard to come by on a given day, so it would be the goal that
 * quietly fails for most people and takes the whole set down with it. There is
 * no "watch an ad" goal either - the client asserts that, and a goal worth
 * Points must not rest on an assertion.
 */
export type GoalKind = "PLAY_GAMES" | "COMPLETE_QUIZZES" | "CORRECT_ANSWERS";

export interface GoalTemplate {
  id: string;
  kind: GoalKind;
  target: number;
}

/**
 * The pool, grouped by kind. Retuning the day is editing this array.
 *
 * Targets stay small. A daily set has to be finishable in one sitting by
 * someone with ten minutes, or it stops being a daily habit and becomes a
 * thing that is always half done.
 */
export const DAILY_GOAL_POOL: GoalTemplate[] = [
  {id: "play_1", kind: "PLAY_GAMES", target: 8},
  {id: "play_2", kind: "PLAY_GAMES", target: 9},
  {id: "play_3", kind: "PLAY_GAMES", target: 10},
  {id: "quiz_1", kind: "COMPLETE_QUIZZES", target: 7},
  {id: "quiz_2", kind: "COMPLETE_QUIZZES", target: 8},
  {id: "quiz_3", kind: "COMPLETE_QUIZZES", target: 9},
  {id: "correct_2", kind: "CORRECT_ANSWERS", target: 7},
  {id: "correct_3", kind: "CORRECT_ANSWERS", target: 8},
  {id: "correct_5", kind: "CORRECT_ANSWERS", target: 9},
];

/** One goal of each kind, so a day never asks for the same thing three times. */
export const GOAL_KINDS: GoalKind[] = [
  "PLAY_GAMES",
  "COMPLETE_QUIZZES",
  "CORRECT_ANSWERS",
];

export const DAILY_GOAL_COUNT = GOAL_KINDS.length;

/**
 * The fallback for the XP finishing all three pays.
 *
 * The live value is read from Firestore (config/dailyGoals.bonusXp) - see
 * resolveBonusXp - so it can be retuned from the console without a deploy.
 * This is what applies when that document is missing or unreadable, which must
 * never mean "pay nothing" or the goals would silently stop rewarding on a
 * config mistake.
 *
 * XP rather than Stars: the claim is free and a rewarded ad doubles it, and
 * the ad is unverified - doubling Stars on the client's word would be minting
 * currency. XP reaches Stars only through the level curve.
 */
export const DAILY_GOAL_BONUS_XP = 100;

/**
 * The ceiling on the configured bonus.
 *
 * config/dailyGoals is edited by hand in a console, and the failure that
 * matters is an extra zero. XP levels players up and levels pay Stars, so a
 * typo must cost a capped amount rather than an unbounded one. This is also
 * the ceiling claimDoubleXp holds a goal double to.
 */
export const MAX_DAILY_GOAL_BONUS_XP = 500;

/** The document holding the tunable values, read by the goal callables. */
export const DAILY_GOALS_CONFIG_DOC = "dailyGoals";

/**
 * The bonus to actually pay, from whatever the config document holds.
 *
 * Anything absent, negative, fractional or unparseable falls back to the
 * built-in value rather than to zero: a broken config should leave the economy
 * as it was, not quietly switch the reward off.
 */
export function resolveBonusXp(raw: unknown): number {
  // Type-checked before any coercion, because Number(null) and Number("") are
  // both 0 - so a field left null, or an empty string typed into the console,
  // would coerce cleanly to a zero reward and pass every numeric guard below
  // it. Switching the bonus silently off is the one outcome a bad config must
  // never produce.
  if (typeof raw !== "number") return DAILY_GOAL_BONUS_XP;
  if (!Number.isFinite(raw) || !Number.isInteger(raw) || raw < 0) {
    return DAILY_GOAL_BONUS_XP;
  }
  return Math.min(raw, MAX_DAILY_GOAL_BONUS_XP);
}

/** Per-day activity counters, reset when the day rolls over. */
export interface DailyStats {
  dayUtc: number;
  games: number;
  quizzes: number;
  correct: number;
}

export function emptyStats(dayUtc: number): DailyStats {
  return {dayUtc, games: 0, quizzes: 0, correct: 0};
}

/**
 * The stats to count against today. Anything from an earlier day is spent, so
 * it reads as zero rather than being carried forward.
 */
export function statsForDay(
  stored: Partial<DailyStats> | null | undefined,
  todayUtc: number
): DailyStats {
  if (!stored || stored.dayUtc !== todayUtc) return emptyStats(todayUtc);
  return {
    dayUtc: todayUtc,
    games: Number(stored.games) || 0,
    quizzes: Number(stored.quizzes) || 0,
    correct: Number(stored.correct) || 0,
  };
}

/**
 * A small, stable string hash (FNV-1a).
 *
 * Deterministic on purpose: today's goals are derived from the uid and the
 * day rather than written down, so they are the same on every read without
 * anything having to store them, and two users get different sets.
 */
function hash(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/** Today's three goals for one user - one of each kind. */
export function selectDailyGoals(uid: string, dayUtc: number): GoalTemplate[] {
  return GOAL_KINDS.map((kind, index) => {
    const options = DAILY_GOAL_POOL.filter((goal) => goal.kind === kind);
    return options[hash(`${uid}:${dayUtc}:${index}`) % options.length];
  });
}

/** How far along one goal is, capped at its target. */
export function goalProgress(goal: GoalTemplate, stats: DailyStats): number {
  const raw =
    goal.kind === "PLAY_GAMES" ? stats.games :
      goal.kind === "COMPLETE_QUIZZES" ? stats.quizzes :
        stats.correct;
  return Math.min(Math.max(raw, 0), goal.target);
}

export function isGoalDone(goal: GoalTemplate, stats: DailyStats): boolean {
  return goalProgress(goal, stats) >= goal.target;
}

export function allGoalsDone(goals: GoalTemplate[], stats: DailyStats): boolean {
  return goals.length > 0 && goals.every((goal) => isGoalDone(goal, stats));
}

export type GoalBonusDecision =
  | {pay: true}
  | {pay: false; reason: "already_claimed" | "not_complete"};

/**
 * Whether the bonus is payable now.
 *
 * Claimed rather than granted automatically, but no longer behind an ad: the
 * claim pays at once, and the rewarded ad is offered afterwards to double it
 * (claimDoubleXp) - so an ad that will not load costs the player nothing.
 */
export function resolveGoalBonus(
  lastBonusDayUtc: number | null | undefined,
  todayUtc: number,
  goals: GoalTemplate[],
  stats: DailyStats
): GoalBonusDecision {
  if (lastBonusDayUtc === todayUtc) {
    return {pay: false, reason: "already_claimed"};
  }
  if (!allGoalsDone(goals, stats)) {
    return {pay: false, reason: "not_complete"};
  }
  return {pay: true};
}
