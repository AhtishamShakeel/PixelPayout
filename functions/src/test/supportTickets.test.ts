/**
 * Pure unit tests for support tickets. No emulator.
 * Run via: npm run test:unit
 */
import {
  SUPPORT_MESSAGES_IN_A_ROW,
  SUPPORT_MESSAGES_PER_DAY,
  SUPPORT_MESSAGE_MAX,
  cleanMessage,
  isSupportCategory,
  messagesSentToday,
  resolveNewTicket,
  resolveUserReply,
  subjectFor,
} from "../economy/supportTickets";

let passed = 0;
let failed = 0;

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    passed++;
    console.log(`  PASS  ${desc}`);
  } else {
    failed++;
    console.log(`  FAIL  ${desc} -- expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

// --- categories ----------------------------------------------------------------
assertEq("known category", isSupportCategory("redemption"), true);
assertEq("unknown category", isSupportCategory("refund_me_now"), false);
assertEq("non-string category", isSupportCategory(3), false);

// --- messages ------------------------------------------------------------------
assertEq("a normal message is kept, trimmed",
  cleanMessage("  My order 1234 has not arrived yet  "),
  {ok: true, text: "My order 1234 has not arrived yet"});
assertEq("too short", cleanMessage("help"), {ok: false, reason: "too_short"});
assertEq("whitespace only is too short", cleanMessage("   \n\n  "), {ok: false, reason: "too_short"});
assertEq("not a string is too short", cleanMessage(undefined), {ok: false, reason: "too_short"});
assertEq("too long", cleanMessage("x".repeat(SUPPORT_MESSAGE_MAX + 1)), {ok: false, reason: "too_long"});
assertEq("exactly the max is fine", cleanMessage("x".repeat(SUPPORT_MESSAGE_MAX)).ok, true);
assertEq("blank-line padding is collapsed",
  cleanMessage("first line here\n\n\n\n\nsecond line"),
  {ok: true, text: "first line here\n\nsecond line"});

assertEq("short subject is the message", subjectFor("Order not arrived"), "Order not arrived");
assertEq("long subject is cut to 80 with an ellipsis", subjectFor("a ".repeat(100)).length, 80);
assertEq("newlines become spaces in the subject", subjectFor("one\ntwo"), "one two");

// --- daily counter ---------------------------------------------------------------
assertEq("no counter yet", messagesSentToday(undefined, 100), 0);
assertEq("yesterday's counter resets", messagesSentToday({dayUtc: 99, count: 15}, 100), 0);
assertEq("today's counter counts", messagesSentToday({dayUtc: 100, count: 7}, 100), 7);

// --- opening a ticket --------------------------------------------------------------
assertEq("no active ticket: allowed", resolveNewTicket(false, 0), {ok: true});
assertEq("one active ticket at a time",
  resolveNewTicket(true, 0), {ok: false, reason: "active_ticket_exists"});
assertEq("daily limit applies to new tickets",
  resolveNewTicket(false, SUPPORT_MESSAGES_PER_DAY), {ok: false, reason: "daily_limit"});

// --- replying ------------------------------------------------------------------------
assertEq("owner replies to an open ticket",
  resolveUserReply({uid: "u1", status: "answered"}, "u1", 3), {ok: true});
assertEq("someone else's ticket is refused",
  resolveUserReply({uid: "u1", status: "open"}, "u2", 0), {ok: false, reason: "not_owner"});
assertEq("a resolved ticket is read-only",
  resolveUserReply({uid: "u1", status: "resolved"}, "u1", 0), {ok: false, reason: "ticket_closed"});
assertEq("daily limit applies to replies",
  resolveUserReply({uid: "u1", status: "open"}, "u1", SUPPORT_MESSAGES_PER_DAY),
  {ok: false, reason: "daily_limit"});

// --- messages in a row -------------------------------------------------------------
assertEq("the limit is two in a row", SUPPORT_MESSAGES_IN_A_ROW, 2);
assertEq("one follow-up after the opening message is allowed",
  resolveUserReply({uid: "u1", status: "open", userMessagesSinceReply: 1}, "u1", 1), {ok: true});
assertEq("a third message in a row waits for support",
  resolveUserReply({uid: "u1", status: "open", userMessagesSinceReply: 2}, "u1", 2),
  {ok: false, reason: "awaiting_support"});
assertEq("after support replies the count is reset",
  resolveUserReply({uid: "u1", status: "answered", userMessagesSinceReply: 0}, "u1", 2), {ok: true});
assertEq("a closed ticket says closed, not waiting",
  resolveUserReply({uid: "u1", status: "resolved", userMessagesSinceReply: 2}, "u1", 0),
  {ok: false, reason: "ticket_closed"});

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
