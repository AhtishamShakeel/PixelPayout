/**
 * Pure unit tests for quiz grading + game claim plausibility.
 * No emulator. Run via: npm run test:unit
 */
import {buildAnswerKey, gradeAnswer, answerKeyLookupKey} from "../economy/quizAnswerKey";
import {validateGameClaim, isKnownGame, MIN_SESSION_MS, MAX_SESSION_AGE_MS} from "../economy/gameSession";

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(`  FAIL  ${desc}${detail !== undefined ? " -- " + JSON.stringify(detail) : ""}`);
}

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok(desc);
  } else {
    fail(desc, `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

console.log("=== Quiz answer key + game session unit tests ===\n");

// Mirrors the real quizzes.json shape, including the fact that quiz ids
// repeat across categories.
const rawQuizJson = {
  version: 2,
  categories: [
    {
      name: "Animals",
      quizzes: [
        {id: "1", questions: [{correctAnswer: 1}, {correctAnswer: 2}, {correctAnswer: 0}]},
        {id: "2", questions: [{correctAnswer: 3}]},
      ],
    },
    {
      name: "Sports",
      quizzes: [
        {id: "1", questions: [{correctAnswer: 0}, {correctAnswer: 3}]},
      ],
    },
  ],
};

const key = buildAnswerKey(rawQuizJson);

// --- buildAnswerKey ---
assertEq("answer key carries the source version", key.version, 2);
assertEq("answer key has one entry per category:quiz pair", Object.keys(key.answers).length, 3);
assertEq("Animals:1 answers extracted in order", key.answers[answerKeyLookupKey("Animals", "1")], [1, 2, 0]);
assertEq("Sports:1 is distinct from Animals:1 (ids repeat per category)", key.answers["Sports:1"], [0, 3]);

// --- gradeAnswer: correctness ---
assertEq("correct answer grades true", gradeAnswer(key, "Animals", "1", 0, 1), true);
assertEq("wrong answer grades false", gradeAnswer(key, "Animals", "1", 0, 3), false);
assertEq("correct answer at a later index", gradeAnswer(key, "Animals", "1", 2, 0), true);
assertEq("wrong answer at a later index", gradeAnswer(key, "Animals", "1", 2, 1), false);

// This is the whole point of the fix: the same quizId in another category
// must not be gradeable against the wrong answer list.
assertEq("Sports:1 q0 correct is 0, not Animals' 1", gradeAnswer(key, "Sports", "1", 0, 0), true);
assertEq("Animals' answer applied to Sports grades false", gradeAnswer(key, "Sports", "1", 0, 1), false);

// --- gradeAnswer: unverifiable cases return null (fail closed) ---
assertEq("unknown category -> null", gradeAnswer(key, "Nope", "1", 0, 0), null);
assertEq("unknown quiz id -> null", gradeAnswer(key, "Animals", "999", 0, 0), null);
assertEq("question index past the end -> null", gradeAnswer(key, "Animals", "1", 99, 0), null);
assertEq("negative question index -> null", gradeAnswer(key, "Animals", "1", -1, 0), null);
assertEq("non-integer question index -> null", gradeAnswer(key, "Animals", "1", 1.5, 0), null);
assertEq("NaN question index -> null", gradeAnswer(key, "Animals", "1", NaN, 0), null);

// A selected answer that isn't even a valid option is simply incorrect.
assertEq("out-of-range selected answer grades false", gradeAnswer(key, "Animals", "1", 0, 99), false);
assertEq("no-answer sentinel (-1, timeout) grades false", gradeAnswer(key, "Animals", "1", 0, -1), false);

// --- isKnownGame ---
assertEq("floppy_bird is known", isKnownGame("floppy_bird"), true);
assertEq("game_2048 is known", isKnownGame("game_2048"), true);
assertEq("unknown game rejected", isKnownGame("doom"), false);
assertEq("prototype pollution guard: 'constructor' is not a game", isKnownGame("constructor"), false);
assertEq("prototype pollution guard: 'toString' is not a game", isKnownGame("toString"), false);

// --- validateGameClaim ---
assertEq(
  "plausible floppy_bird claim accepted",
  validateGameClaim({gameId: "floppy_bird", score: 20, elapsedMs: 60_000}).valid,
  true
);
assertEq(
  "unknown game rejected",
  validateGameClaim({gameId: "doom", score: 1, elapsedMs: 60_000}).rejection,
  "unknown_game"
);
assertEq(
  "negative score rejected",
  validateGameClaim({gameId: "floppy_bird", score: -1, elapsedMs: 60_000}).rejection,
  "score_out_of_range"
);
assertEq(
  "score above the game's absolute cap rejected",
  validateGameClaim({gameId: "floppy_bird", score: 500_000, elapsedMs: 60_000}).rejection,
  "score_out_of_range"
);
assertEq(
  "claim faster than MIN_SESSION_MS rejected",
  validateGameClaim({gameId: "floppy_bird", score: 1, elapsedMs: MIN_SESSION_MS - 1}).rejection,
  "too_fast"
);
assertEq(
  "claim exactly at MIN_SESSION_MS accepted (boundary)",
  validateGameClaim({gameId: "floppy_bird", score: 1, elapsedMs: MIN_SESSION_MS}).valid,
  true
);
assertEq(
  "stale session rejected",
  validateGameClaim({gameId: "floppy_bird", score: 1, elapsedMs: MAX_SESSION_AGE_MS + 1}).rejection,
  "stale_session"
);
assertEq(
  "implausibly high score for the elapsed time rejected",
  validateGameClaim({gameId: "floppy_bird", score: 5_000, elapsedMs: 10_000}).rejection,
  "implausible_rate"
);
assertEq(
  "score exactly at the plausible rate accepted (boundary)",
  validateGameClaim({gameId: "floppy_bird", score: 20, elapsedMs: 10_000}).valid,
  true
);
assertEq(
  "one point above the plausible rate rejected",
  validateGameClaim({gameId: "floppy_bird", score: 21, elapsedMs: 10_000}).rejection,
  "implausible_rate"
);
assertEq(
  "2048 allows a much higher rate than floppy_bird",
  validateGameClaim({gameId: "game_2048", score: 2_000, elapsedMs: 10_000}).valid,
  true
);
assertEq(
  "the headline attack: max score claimed instantly is rejected",
  validateGameClaim({gameId: "game_2048", score: 1_000_000, elapsedMs: 3_000}).rejection,
  "implausible_rate"
);

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
process.exit(failed > 0 ? 1 : 0);
