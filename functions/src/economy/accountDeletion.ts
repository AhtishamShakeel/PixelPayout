/**
 * Account deletion: what is kept, for how long, and how it is keyed.
 *
 * Pure, like the rest of this folder. index.ts does the reads and writes.
 *
 * Google Play requires an in-app way to delete an account and its data, and
 * allows keeping some data for fraud prevention and disputes as long as the
 * privacy policy says so (public/legal/privacy.html, "How long we keep it").
 * What this app keeps, and why:
 *
 *   deletedAccounts/{uid}   Android ID + a SHA-256 of the email. Stops a
 *                           person deleting and re-signing to claim new-user
 *                           and referral rewards again.
 *   redemptions             Orders, for payout disputes.
 *   offerwallTransactions   Partner-confirmed offer records.
 *   playerLinks             Per game account first-redeem marks. Anonymised
 *                           at purge (uid fields cleared), not deleted, so the
 *                           one-time discount stays spent on that game UID.
 *
 * Everything under users/{uid}, and the Firebase Auth user, goes at once.
 */

import {createHash} from "crypto";

export const DELETED_ACCOUNTS_COLLECTION = "deletedAccounts";

/** How long the retained records live after a deletion. Matches the policy. */
export const DELETION_RETENTION_MS = 2 * 365 * 24 * 60 * 60 * 1000;

/**
 * One-way email fingerprint. Lower-cased and trimmed first so "A@x.com " and
 * "a@x.com" match - the same person must not get a second new-user reward by
 * changing capitalisation.
 */
export function emailFingerprint(email: string | null | undefined): string {
  const normalised = (email || "").trim().toLowerCase();
  if (!normalised) return "";
  return createHash("sha256").update(normalised).digest("hex");
}

/** When a deletion's retained records may be purged. */
export function purgeAfterMillis(deletedAtMillis: number): number {
  return deletedAtMillis + DELETION_RETENTION_MS;
}

export type DeletionRefusal = "pending_redemption";

/**
 * Whether an account may be deleted right now.
 *
 * A pending order blocks it: the admin still has to deliver or decline it,
 * and a declined order refunds Stars to an account that would no longer
 * exist. The user waits for it to settle, which the policy tells them.
 */
export function resolveDeletion(hasPendingRedemption: boolean):
  {ok: true} | {ok: false; reason: DeletionRefusal} {
  return hasPendingRedemption ? {ok: false, reason: "pending_redemption"} : {ok: true};
}
