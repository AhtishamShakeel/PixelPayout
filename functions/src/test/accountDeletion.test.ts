/**
 * Pure unit tests for account deletion. No emulator.
 * Run via: npm run test:unit
 */
import {
  DELETION_RETENTION_MS,
  emailFingerprint,
  purgeAfterMillis,
  resolveDeletion,
} from "../economy/accountDeletion";

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

// --- email fingerprint --------------------------------------------------------
{
  const fp = emailFingerprint("player@example.com");
  assertEq("is a sha-256 hex string", /^[0-9a-f]{64}$/.test(fp), true);
  assertEq("does not contain the email", fp.includes("player"), false);
  assertEq("ignores case and surrounding space",
    emailFingerprint("  Player@Example.COM "), fp);
  assertEq("different emails differ",
    emailFingerprint("other@example.com") === fp, false);
  assertEq("empty email has no fingerprint", emailFingerprint(""), "");
  assertEq("missing email has no fingerprint", emailFingerprint(undefined), "");
}

// --- retention ----------------------------------------------------------------
{
  assertEq("retention is two years", DELETION_RETENTION_MS, 730 * 86_400_000);
  assertEq("purge is two years after deletion",
    purgeAfterMillis(1_000), 1_000 + DELETION_RETENTION_MS);
}

// --- when deletion is allowed -------------------------------------------------
{
  assertEq("no pending order: allowed", resolveDeletion(false), {ok: true});
  assertEq("pending order: refused",
    resolveDeletion(true), {ok: false, reason: "pending_redemption"});
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
