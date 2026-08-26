/**
 * Redemption: the first path that SPENDS Points rather than earning them.
 *
 * Two invariants this module exists to protect:
 *   1. The price is never taken from the client. The option is read from
 *      Firestore server-side and its stored pointsCost is what's charged.
 *   2. Spending Points can never touch XP or level. Progression is not for
 *      sale and is not lost by cashing out.
 *
 * Creating a redemption records a PENDING request and debits the balance;
 * actually paying out is a separate, human/business process.
 */

export const REDEMPTION_OPTIONS_COLLECTION = "redemptionOptions";

/**
 * Top-level rather than a subcollection under each user, so every pending
 * payout can be listed in one place - both in the Firebase console and in the
 * admin tool. Each document carries the owner's uid instead of relying on its
 * path for ownership.
 */
export const REDEMPTIONS_COLLECTION = "redemptions";

export type RedemptionType = "EASYPAISA" | "GAME_CURRENCY";
export type RedemptionStatus = "pending" | "approved" | "rejected";

export interface RedemptionOption {
  title: string;
  description?: string;
  pointsCost: number;
  type: RedemptionType;
  imageUrl?: string;
  enabled: boolean;
  /** Optional level gate - one of the things levelling unlocks. */
  minLevel?: number;
}

export type RedemptionRejection =
  | "unknown_option"
  | "option_disabled"
  | "invalid_option"
  | "level_too_low"
  | "insufficient_points"
  | "payout_details_required";

export interface RedemptionValidation {
  ok: boolean;
  rejection?: RedemptionRejection;
  /** The authoritative cost, read from the option - never from the client. */
  pointsCost?: number;
}

/** Light sanity check. Deliberately permissive about formatting. */
export function isPlausiblePayoutNumber(raw: string): boolean {
  const digits = raw.replace(/[^0-9]/g, "");
  return digits.length >= 10 && digits.length <= 15;
}

export function validateRedemption(input: {
  option: RedemptionOption | null;
  userPoints: number;
  userLevel: number;
  payoutNumber?: string;
}): RedemptionValidation {
  const {option} = input;

  if (!option) return {ok: false, rejection: "unknown_option"};
  if (option.enabled !== true) return {ok: false, rejection: "option_disabled"};

  const pointsCost = Number(option.pointsCost);
  if (!Number.isFinite(pointsCost) || pointsCost <= 0 || !Number.isInteger(pointsCost)) {
    return {ok: false, rejection: "invalid_option"};
  }

  const minLevel = Number(option.minLevel ?? 1);
  if (Number.isFinite(minLevel) && input.userLevel < minLevel) {
    return {ok: false, rejection: "level_too_low", pointsCost};
  }

  // A cash payout needs somewhere to send it.
  if (option.type === "EASYPAISA") {
    const number = (input.payoutNumber || "").trim();
    if (!number || !isPlausiblePayoutNumber(number)) {
      return {ok: false, rejection: "payout_details_required", pointsCost};
    }
  }

  if (input.userPoints < pointsCost) {
    return {ok: false, rejection: "insufficient_points", pointsCost};
  }

  return {ok: true, pointsCost};
}
