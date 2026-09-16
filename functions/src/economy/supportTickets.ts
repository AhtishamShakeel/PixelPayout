/**
 * Support tickets: what a ticket may contain and when a user may write.
 *
 * Pure, like the rest of this folder - index.ts does the reads and writes.
 *
 * ONE ACTIVE TICKET PER USER. A user with a ticket still open or answered
 * replies in it, or closes it, before starting another. Keeps the queue one
 * conversation per person and caps what a spammer can pile up.
 *
 * Clients never write tickets directly. firestore.rules lets an owner read
 * their tickets and flip their own `userUnread` flag off - nothing else - so
 * every limit here is enforced where a modified app cannot skip it.
 */

export const SUPPORT_TICKETS_COLLECTION = "supportTickets";
export const SUPPORT_MESSAGES_SUBCOLLECTION = "messages";

export const SUPPORT_CATEGORIES = [
  "redemption",
  "missing_reward",
  "account",
  "referral",
  "bug",
  "other",
] as const;
export type SupportCategory = typeof SUPPORT_CATEGORIES[number];

/**
 * open      waiting on support
 * answered  support replied, waiting on the user
 * resolved  closed, by support or by the user; read-only from then on
 */
export type TicketStatus = "open" | "answered" | "resolved";

export const SUPPORT_MESSAGE_MIN = 10;
export const SUPPORT_MESSAGE_MAX = 1000;
export const ADMIN_MESSAGE_MAX = 4000;
/** Messages a user may send across all tickets in one UTC day. */
export const SUPPORT_MESSAGES_PER_DAY = 20;
/**
 * Messages a user may send in a row before support replies. The opening
 * message counts, so a new ticket allows one follow-up, then waits. Stops a
 * user flooding a ticket (and our function bill) while nobody has answered.
 */
export const SUPPORT_MESSAGES_IN_A_ROW = 2;
/** Ticket field counting user messages since support's last reply. */
export const FIELD_USER_MESSAGES_SINCE_REPLY = "userMessagesSinceReply";
/** Subject is the start of the first message, for the list rows. */
export const SUPPORT_SUBJECT_LENGTH = 80;

export function isSupportCategory(raw: unknown): raw is SupportCategory {
  return typeof raw === "string" && (SUPPORT_CATEGORIES as readonly string[]).includes(raw);
}

/**
 * Trims and checks a message. Collapses runs of blank lines so a message
 * cannot be padded to the limit with whitespace.
 */
export function cleanMessage(
  raw: unknown,
  max: number = SUPPORT_MESSAGE_MAX,
  min: number = SUPPORT_MESSAGE_MIN
): {ok: true; text: string} | {ok: false; reason: "too_short" | "too_long"} {
  const text = (typeof raw === "string" ? raw : "")
    .replace(/\r\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  if (text.length < min) return {ok: false, reason: "too_short"};
  if (text.length > max) return {ok: false, reason: "too_long"};
  return {ok: true, text};
}

export function subjectFor(text: string): string {
  const oneLine = text.replace(/\s+/g, " ").trim();
  return oneLine.length <= SUPPORT_SUBJECT_LENGTH ?
    oneLine :
    oneLine.slice(0, SUPPORT_SUBJECT_LENGTH - 1).trimEnd() + "…";
}

/** Today's message count, from a counter that resets on a new UTC day. */
export function messagesSentToday(
  stored: {dayUtc?: number; count?: number} | null | undefined,
  todayUtc: number
): number {
  if (!stored || stored.dayUtc !== todayUtc) return 0;
  return Math.max(Number(stored.count) || 0, 0);
}

export type SupportRefusal =
  | "active_ticket_exists"
  | "daily_limit"
  | "ticket_closed"
  | "not_owner"
  | "awaiting_support";

export function resolveNewTicket(
  hasActiveTicket: boolean,
  sentToday: number
): {ok: true} | {ok: false; reason: SupportRefusal} {
  if (hasActiveTicket) return {ok: false, reason: "active_ticket_exists"};
  if (sentToday >= SUPPORT_MESSAGES_PER_DAY) return {ok: false, reason: "daily_limit"};
  return {ok: true};
}

export function resolveUserReply(
  ticket: {uid?: string; status?: string; userMessagesSinceReply?: number},
  callerUid: string,
  sentToday: number
): {ok: true} | {ok: false; reason: SupportRefusal} {
  if (ticket.uid !== callerUid) return {ok: false, reason: "not_owner"};
  if (ticket.status === "resolved") return {ok: false, reason: "ticket_closed"};
  if ((Number(ticket.userMessagesSinceReply) || 0) >= SUPPORT_MESSAGES_IN_A_ROW) {
    return {ok: false, reason: "awaiting_support"};
  }
  if (sentToday >= SUPPORT_MESSAGES_PER_DAY) return {ok: false, reason: "daily_limit"};
  return {ok: true};
}
