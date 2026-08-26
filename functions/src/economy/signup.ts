/**
 * Server-side account setup.
 *
 * User documents used to be written by the Android client straight after
 * sign-in. That forced the security rules to allow clients to write to
 * users/{uid}, which is the same door an attacker walks through to set their
 * own points balance. Creating the document here instead lets the rules deny
 * client writes outright.
 *
 * It also moves two things that were never safe on the client:
 *   - the androidId duplicate check (referral-abuse prevention), which a
 *     modified client could simply skip
 *   - referral code generation, which had no uniqueness check at all
 */

const REFERRAL_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
export const REFERRAL_CODE_LENGTH = 6;

/** How many times to retry on a code collision before giving up. */
export const REFERRAL_CODE_MAX_ATTEMPTS = 5;

export function generateReferralCode(
  random: () => number = Math.random
): string {
  let code = "";
  for (let i = 0; i < REFERRAL_CODE_LENGTH; i++) {
    code += REFERRAL_CODE_ALPHABET.charAt(
      Math.floor(random() * REFERRAL_CODE_ALPHABET.length)
    );
  }
  return code;
}

export interface NewUserProfile {
  displayName: string;
  email: string;
  androidId: string;
  hasUsedReferral: boolean;
  referralCode: string;
  points: number;
  xp: number;
  level: number;
  referralRewardClaimed: boolean;
}

/**
 * The starting state of every account. Points/xp/level are fixed here rather
 * than accepted from the caller - the only way to move them afterwards is
 * through a reward path.
 */
export function buildNewUserProfile(input: {
  displayName: string;
  email: string;
  androidId: string;
  hasUsedReferral: boolean;
  referralCode: string;
}): NewUserProfile {
  return {
    displayName: input.displayName.trim().slice(0, 60) || "User",
    email: input.email,
    androidId: input.androidId || "UNKNOWN_ANDROID_ID",
    // A device that has already held an account cannot claim a referral
    // bonus again.
    hasUsedReferral: input.hasUsedReferral,
    referralCode: input.referralCode,
    points: 0,
    xp: 0,
    level: 1,
    referralRewardClaimed: false,
  };
}
