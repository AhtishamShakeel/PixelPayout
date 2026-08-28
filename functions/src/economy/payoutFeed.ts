/**
 * The public payout feed: "joh*******23 got 100 UC, 10 minutes ago".
 *
 * A separate collection rather than opening up `redemptions`, and that is the
 * whole point of it. Redemption documents carry the owner's uid, their
 * pointsCost, and - for cash payouts - their PHONE NUMBER. The security rules
 * deny cross-user reads for exactly that reason. This collection holds only
 * what is safe for everyone to see: a masked name, what was redeemed, and when.
 *
 * Written by resolveRedemption when a payout is approved, which is the single
 * place an approval can happen, so there is no path that pays out without
 * appearing here and none that appears here without paying out.
 */
import {FieldValue} from "firebase-admin/firestore";

export const PAYOUT_FEED_COLLECTION = "payoutFeed";

/** Kept short - this is social proof, not an audit log. */
export const PAYOUT_FEED_PAGE_SIZE = 20;

export interface PayoutFeedDoc {
  /** Already masked. The raw name is never written here. */
  name: string;
  optionTitle: string;
  approvedAt: FieldValue;
}

/**
 * Masks a display name for public view: three characters, then stars, then
 * two. "John_ahemd123" becomes "Joh*******23".
 *
 * Short names are the case worth being careful about, because the obvious
 * formula exposes them almost completely - a five character name would show
 * three of its five. Anything under six characters gets its first letter only.
 *
 * The star run is capped so a very long name does not produce a line of
 * asterisks wide enough to break the row it sits in.
 *
 * This reduces the moderation problem rather than removing it: the visible
 * head and tail are still user-chosen on the email signup path, where the name
 * comes from a free text field. It is a deliberate trade - reaching this feed
 * at all means earning and redeeming real stars first, which is a great deal
 * of work for a few characters seen briefly.
 */
export function maskDisplayName(raw: string | null | undefined): string {
  const name = (raw || "").trim();

  if (name.length === 0) return "Someone";
  if (name.length < 6) {
    return name.slice(0, 1) + "*".repeat(Math.max(name.length - 1, 1));
  }

  const stars = Math.min(name.length - 5, 8);
  return name.slice(0, 3) + "*".repeat(stars) + name.slice(-2);
}
