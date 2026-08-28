/**
 * Pure unit tests for the public payout feed's name masking. No emulator.
 * Run via: npm run test:unit
 */
import {maskDisplayName} from "../economy/payoutFeed";

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(
    `  FAIL  ${desc}${detail !== undefined ? " -- " + JSON.stringify(detail) : ""}`
  );
}

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok(desc);
  } else {
    fail(desc, {actual, expected});
  }
}

{
  assertEq("the worked example from the spec",
    maskDisplayName("John_ahemd123"), "Joh********23");

  assertEq("a six character name keeps three and two",
    maskDisplayName("Ahmed1"), "Ahm*d1");

  // The case the obvious formula gets wrong: taking three and two from a five
  // character name would reveal four fifths of it.
  assertEq("five characters show only the first",
    maskDisplayName("Ahmed"), "A****");
  assertEq("two characters show only the first",
    maskDisplayName("Jo"), "J*");
  assertEq("one character is not left bare",
    maskDisplayName("J"), "J*");

  // A very long name must not produce a row-breaking run of asterisks.
  const long = maskDisplayName("A".repeat(60));
  assertEq("a long name is capped at eight stars", long.length, 3 + 8 + 2);
  assertEq("and still keeps its head and tail",
    long.startsWith("AAA") && long.endsWith("AA"), true);

  // Nothing usable must ever fall through as an empty string.
  assertEq("an empty name becomes Someone", maskDisplayName(""), "Someone");
  assertEq("whitespace becomes Someone", maskDisplayName("   "), "Someone");
  assertEq("null becomes Someone", maskDisplayName(null), "Someone");
  assertEq("undefined becomes Someone", maskDisplayName(undefined), "Someone");

  assertEq("surrounding whitespace is trimmed before masking",
    maskDisplayName("  John_ahemd123  "), "Joh********23");

  // The property that actually matters: the middle is never readable.
  const masked = maskDisplayName("SensitiveName99");
  assertEq("the middle of a name is never shown",
    masked.includes("sitiveNam"), false);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
