/**
 * Temporary Points buff.
 *
 * Deliberately NOT tied to levels. A permanent, level-derived earning
 * multiplier is an unbounded ongoing cost that's hard to model against
 * offerwall/survey margins; a buff is a bounded, one-shot grant whose cost is
 * knowable in advance (expected points during the window x (multiplier - 1)).
 * Levels pay out through fixed milestone bonuses instead.
 *
 * A buff only ever affects sources marked multiplier-eligible in
 * rewardConfig - it can never inflate a referral, a login reward, or a
 * redemption.
 */
import {RewardSource} from "./rewardConfig";

export interface PointsBuff {
  multiplier: number;
  /** Epoch millis. */
  expiresAt: number;
  /** Epoch millis. */
  grantedAt: number;
  /** What granted it, for the audit trail. */
  source: RewardSource;
}

/** Bounds on any single grant, enforced server-side. */
export const MAX_BUFF_MULTIPLIER = 3;
export const MIN_BUFF_MULTIPLIER = 1;
export const MAX_BUFF_DURATION_MS = 24 * 60 * 60 * 1000;

/**
 * The multiplier to apply right now. An expired or malformed buff is simply
 * inactive (1x) - expiry is evaluated on read rather than cleaned up on a
 * timer, so there's no window where a stale buff still applies.
 */
export function activeMultiplier(buff: PointsBuff | null | undefined, nowMs: number): number {
  if (!buff) return 1;
  if (!Number.isFinite(buff.multiplier) || buff.multiplier <= 1) return 1;
  if (!Number.isFinite(buff.expiresAt) || buff.expiresAt <= nowMs) return 1;
  return Math.min(buff.multiplier, MAX_BUFF_MULTIPLIER);
}

export function isBuffActive(buff: PointsBuff | null | undefined, nowMs: number): boolean {
  return activeMultiplier(buff, nowMs) > 1;
}

export interface BuffGrantInput {
  multiplier: number;
  durationMs: number;
  source: RewardSource;
}

/**
 * Applies the stacking rule and returns the buff that should be stored.
 *
 * Buffs never stack multiplicatively - 2x on top of 2x must not become 4x, or
 * total exposure per user becomes unbounded as more buff sources are added.
 * Instead:
 *   - a stronger incoming buff replaces the active one outright
 *   - an equal one extends the expiry (whichever runs longer wins)
 *   - a weaker one is ignored while a stronger buff is still running
 *
 * The last rule means a weaker-but-longer grant can be lost. That's the
 * deliberate trade for a hard ceiling on what any user can be earning at once;
 * if it ever feels punitive, queueing the weaker buff would be the fix.
 */
export function resolveBuffGrant(
  current: PointsBuff | null | undefined,
  incoming: BuffGrantInput,
  nowMs: number
): PointsBuff | null {
  const multiplier = Math.min(
    Math.max(incoming.multiplier, MIN_BUFF_MULTIPLIER),
    MAX_BUFF_MULTIPLIER
  );
  const durationMs = Math.min(Math.max(incoming.durationMs, 0), MAX_BUFF_DURATION_MS);

  // Nothing to grant.
  if (multiplier <= 1 || durationMs <= 0) return null;

  const candidate: PointsBuff = {
    multiplier,
    expiresAt: nowMs + durationMs,
    grantedAt: nowMs,
    source: incoming.source,
  };

  const currentMultiplier = activeMultiplier(current, nowMs);
  if (currentMultiplier <= 1 || !current) {
    return candidate;
  }

  if (multiplier > currentMultiplier) {
    return candidate;
  }

  if (multiplier === currentMultiplier) {
    return {
      ...candidate,
      expiresAt: Math.max(current.expiresAt, candidate.expiresAt),
    };
  }

  // Weaker than what's already running - leave the stronger buff alone.
  return null;
}
