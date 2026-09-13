/**
 * Regression harness for the existing reward flows, run against the Firebase
 * emulator suite. This is intentionally plain Node + assertions (no test
 * framework) and grows with each economy-refactor step's test gate.
 *
 * Run via: npm run test:smoke   (from functions/)
 */
import * as admin from "firebase-admin";
import {Timestamp} from "firebase-admin/firestore";
import {initializeApp as initClientApp, deleteApp} from "firebase/app";
import {
  getAuth,
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  type User,
} from "firebase/auth";
import {getFunctions, connectFunctionsEmulator, httpsCallable} from "firebase/functions";
import {
  LEVEL_UP_POINTS,
  MAX_DAILY_BONUS_ATTEMPTS,
  MAX_DAILY_GAME_SESSIONS,
} from "../economy/rewardConfig";

const PROJECT_ID = "pixelpayout-check";

admin.initializeApp({projectId: PROJECT_ID});
const db = admin.firestore();

const clientApp = initClientApp({projectId: PROJECT_ID, apiKey: "fake-api-key"});
const clientAuth = getAuth(clientApp);
connectAuthEmulator(clientAuth, "http://127.0.0.1:9099", {disableWarnings: true});
const clientFunctions = getFunctions(clientApp);
connectFunctionsEmulator(clientFunctions, "127.0.0.1", 5001);

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

async function assertThrows(desc: string, fn: () => Promise<unknown>, expectedCodeFragment: string) {
  try {
    await fn();
    fail(desc, "expected to throw, did not");
  } catch (e) {
    const code = (e as {code?: string}).code || "";
    if (code.includes(expectedCodeFragment)) {
      ok(desc);
    } else {
      fail(desc, `wrong error code: ${code}`);
    }
  }
}

async function makeUser(prefix: string): Promise<User> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@test.local`;
  const cred = await createUserWithEmailAndPassword(clientAuth, email, "Test1234!");
  return cred.user;
}

async function seedUserDoc(uid: string, referralCode: string, overrides: Record<string, unknown> = {}) {
  await db.collection("users").doc(uid).set({
    displayName: "Test User",
    email: `${uid}@test.local`,
    points: 0,
    xp: 0,
    level: 1,
    quiz_attempts: 0,
    hasUsedReferral: false,
    referralLevelRewardPaid: false,
    referralRedeemRewardPaid: false,
    referralCode,
    ...overrides,
  });
}

async function getLedgerEvents(uid: string): Promise<Array<Record<string, unknown> & {id: string}>> {
  const snap = await db.collection("users").doc(uid).collection("rewardEvents").get();
  return snap.docs.map((d) => ({id: d.id, ...d.data()}));
}

/**
 * Seeds the server-side answer key directly, so grading tests don't depend on
 * the live quizzes.json being reachable. Shape matches buildAnswerKey().
 */
async function seedAnswerKey() {
  await db.collection("config").doc("quizAnswerKey").set({
    version: 99,
    answers: {
      "Animals:1": [1, 2, 0],
      "Sports:1": [0, 3],
    },
    syncedAt: Date.now(),
  });
}

/** Opens a real game session via the callable, as the app does. */
async function openGameSession(gameId: string): Promise<string> {
  const startGameSession = httpsCallable(clientFunctions, "startGameSession");
  const res = await startGameSession({gameId});
  return (res.data as {sessionId: string}).sessionId;
}

/** Backdates a session's startedAt so elapsed-time rules can be exercised. */
async function backdateSession(uid: string, sessionId: string, msAgo: number) {
  await db.collection("users").doc(uid).collection("gameSessions").doc(sessionId).update({
    startedAt: Timestamp.fromMillis(Date.now() - msAgo),
  });
}

async function run() {
  console.log("=== Smoke test: existing reward flows against emulator ===\n");

  // --- answer key bootstraps itself when missing (fresh deploy) ---
  {
    await db.collection("config").doc("quizAnswerKey").delete();

    const user = await makeUser("quizbootstrap");
    await seedUserDoc(user.uid, "QUIZBOOT");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Uses the real published quizzes.json, so this also proves the live data
    // still parses into a usable answer key.
    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq(
      "grading works on a fresh deploy with no answer key stored",
      (res.data as {wasCorrect: boolean}).wasCorrect,
      true
    );

    const keySnap = await db.collection("config").doc("quizAnswerKey").get();
    assertEq("bootstrap persisted the answer key", keySnap.exists, true);
    assertEq(
      "bootstrapped key covers every published quiz",
      Object.keys(keySnap.get("answers") as Record<string, number[]>).length,
      50
    );
  }

  await seedAnswerKey();

  // --- claimReward: quiz, graded server-side ---
  {
    const user = await makeUser("quiz");
    await seedUserDoc(user.uid, "QUIZREF1");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Animals:1 question 0 -> correct answer is 1
    const res1 = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("correct answer -> +10 xp", (res1.data as {xpAwarded: number}).xpAwarded, 10);
    assertEq("correct answer -> no points (XP-only source)", (res1.data as {pointsAwarded: number}).pointsAwarded, 0);
    assertEq("correct answer -> server reports wasCorrect true", (res1.data as {wasCorrect: boolean}).wasCorrect, true);

    const res2 = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 1, selectedAnswer: 0,
    });
    assertEq("wrong answer -> +0 xp", (res2.data as {xpAwarded: number}).xpAwarded, 0);
    assertEq("wrong answer -> server reports wasCorrect false", (res2.data as {wasCorrect: boolean}).wasCorrect, false);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("quiz_attempts incremented twice", snap.get("quiz_attempts"), 2);
    assertEq("quizzes award no points at all", snap.get("points"), 0);
    assertEq("xp total after 1 correct answer", snap.get("xp"), 10);
    assertEq("level unchanged by a small xp gain", snap.get("level"), 1);

    const events = await getLedgerEvents(user.uid);
    assertEq("ledger has exactly 2 QUIZ events", events.length, 2);
    assertEq("ledger events are all source QUIZ", events.every((e) => e.source === "QUIZ"), true);
    const correctEvent = events.find((e) => (e.metadata as any)?.wasCorrect === true);
    assertEq("correct-answer ledger entry xpAwarded 10", correctEvent?.xpAwarded, 10);
    assertEq("correct-answer ledger entry basePoints 0", correctEvent?.basePoints, 0);
    assertEq("correct-answer ledger entry finalPoints 0", correctEvent?.finalPoints, 0);
    assertEq("correct-answer ledger entry records level at event", correctEvent?.levelAtEvent, 1);
    assertEq("correct-answer ledger entry not multiplier eligible", correctEvent?.multiplierEligible, false);
    assertEq("correct-answer ledger entry records the submitted answer", (correctEvent?.metadata as any)?.selectedAnswer, 1);
  }

  // --- the level curve is published for the client to read ---
  {
    const syncKey = httpsCallable(clientFunctions, "syncQuizAnswerKey");
    await syncKey({});

    const curveSnap = await db.collection("config").doc("levelCurve").get();
    assertEq("level curve doc is published", curveSnap.exists, true);
    assertEq("published curve carries maxLevel", curveSnap.get("maxLevel"), 30);
    assertEq(
      "published thresholds match the server curve",
      (curveSnap.get("thresholds") as number[]).length,
      29
    );
    assertEq(
      "published thresholds are strictly increasing",
      (curveSnap.get("thresholds") as number[]).every((t, i, a) => i === 0 || t > a[i - 1]),
      true
    );
  }

  await seedAnswerKey();

  // --- xp accumulates into a real level-up ---
  {
    const user = await makeUser("quizlevel");
    // Seeded just below the level-2 threshold (50 xp).
    await seedUserDoc(user.uid, "QUIZLEVEL", {xp: 45, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("crossing the threshold reports a level-up", (res.data as {leveledUp: boolean}).leveledUp, true);
    assertEq("crossing the threshold reports the new level", (res.data as {level: number}).level, 2);
    assertEq("response carries the running xp total", (res.data as {totalXp: number}).totalXp, 55);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("level persisted to the user document", snap.get("level"), 2);
    assertEq("xp persisted to the user document", snap.get("xp"), 55);

    // Found by source, NOT events[0]. getLedgerEvents does an unordered get(),
    // so Firestore returns documents in ID order - and this claim writes two:
    // the QUIZ entry under a random auto-id, and the milestone under
    // `levelup:2`. Whether the auto-id sorts before "levelup:2" is a coin
    // flip, so indexing here asserted against the milestone entry (whose
    // levelAtEvent is the level reached, not the level before) on roughly
    // half of all runs.
    const events = await getLedgerEvents(user.uid);
    const quizEvent = events.find((e) => e.source === "QUIZ");
    assertEq("ledger records the level before the event", quizEvent?.levelAtEvent, 1);
    assertEq("ledger records the level after the event", quizEvent?.levelAfterEvent, 2);
  }

  // --- reaching a milestone level LOCKS a one-time Points bonus ---
  //
  // This block used to assert that the bonus landed in the balance on the
  // spot. It no longer does, and that is the whole change: crossing the level
  // EARNS the stars, watching a rewarded ad RELEASES them. What is asserted
  // here is the earning half - the promise is written, the queue names the
  // level, and the balance has not moved.
  {
    const user = await makeUser("milestone");
    // Level 5 needs 261 xp; seed just below so one correct answer crosses it.
    await seedUserDoc(user.uid, "MILESTONE", {xp: 255, level: 4});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const data = res.data as {
      level: number; milestonePoints: number; milestoneLevels: number[]; totalPoints: number;
    };
    assertEq("crossing into level 5 reports the milestone", data.milestoneLevels, [5]);
    assertEq("milestone bonus points reported", data.milestonePoints, LEVEL_UP_POINTS[5]);
    // The figure above is what was LOCKED. The balance it reports alongside
    // must not include it, or the client would draw stars nobody has.
    assertEq("the reported balance excludes the locked bonus", data.totalPoints, 0);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("milestone reached level 5", snap.get("level"), 5);
    assertEq("the milestone bonus is NOT credited yet", snap.get("points"), 0);
    assertEq("the level is queued for claiming", snap.get("pendingLevelRewards"), [5]);

    const events = await getLedgerEvents(user.uid);
    const milestoneEvent = events.find((e) => e.source === "LEVEL_UP");
    assertEq("a LEVEL_UP ledger entry was written", milestoneEvent !== undefined, true);
    assertEq("milestone ledger id is keyed by level", milestoneEvent?.id, "levelup:5");
    assertEq("milestone ledger records the points it will pay",
      milestoneEvent?.finalPoints, LEVEL_UP_POINTS[5]);
    assertEq("milestone ledger awards no xp", milestoneEvent?.xpAwarded, 0);
    assertEq("milestone ledger is locked, not applied",
      milestoneEvent?.status, "locked");
    // The Stars activity list queries on this. A locked promise is not a
    // movement and must not appear there until it is claimed.
    assertEq("a locked milestone is not a star movement",
      milestoneEvent?.affectsPoints, false);

    // Earning more XP at the same level must not queue or lock it again.
    await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("further xp does not credit the locked bonus", afterSnap.get("points"), 0);
    assertEq("further xp does not queue the level twice",
      afterSnap.get("pendingLevelRewards"), [5]);
    assertEq(
      "still exactly one LEVEL_UP ledger entry",
      (await getLedgerEvents(user.uid)).filter((e) => e.source === "LEVEL_UP").length,
      1
    );
  }

  // --- claiming a locked level reward -------------------------------------
  {
    const user = await makeUser("levelclaim");
    await seedUserDoc(user.uid, "LEVELCLAIM", {xp: 255, level: 4});
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimLevelReward = httpsCallable(clientFunctions, "claimLevelReward");

    await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    const res = await claimLevelReward({});
    const data = res.data as {
      claimed: boolean; level: number; pointsAwarded: number;
      totalPoints: number; pendingLevels: number[];
    };
    assertEq("the claim reports success", data.claimed, true);
    assertEq("it released the level that was queued", data.level, 5);
    assertEq("it paid the amount the ledger had pinned",
      data.pointsAwarded, LEVEL_UP_POINTS[5]);
    assertEq("it reports the new balance", data.totalPoints, LEVEL_UP_POINTS[5]);
    assertEq("nothing is left in the queue", data.pendingLevels, []);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the stars really reached the balance",
      snap.get("points"), LEVEL_UP_POINTS[5]);
    assertEq("the queue is empty on the document too",
      snap.get("pendingLevelRewards"), []);

    const event = (await getLedgerEvents(user.uid)).find((e) => e.source === "LEVEL_UP");
    assertEq("the ledger entry is applied now", event?.status, "applied");
    assertEq("...and counts as a star movement", event?.affectsPoints, true);
    // The moment it was EARNED is kept, so the audit trail still says when
    // the level was crossed even though createdAt now dates the payment.
    assertEq("the lock time was preserved", event?.lockedAt !== undefined, true);

    // A second call has nothing to give. Not an error - a stale screen or a
    // double tap - so it answers rather than throwing, and pays nothing.
    const again = await claimLevelReward({});
    const againData = again.data as {claimed: boolean; reason: string};
    assertEq("a second claim releases nothing", againData.claimed, false);
    assertEq("...and says why", againData.reason, "nothing_pending");
    assertEq(
      "a second claim does not pay twice",
      (await db.collection("users").doc(user.uid).get()).get("points"),
      LEVEL_UP_POINTS[5]
    );
  }

  // --- levels keep climbing while rewards go unclaimed ---------------------
  //
  // The rule the feature is built around: XP still levels you up immediately,
  // the STARS queue behind ads, and the queue is drained lowest first. So a
  // player who climbs from 1 to 4 without claiming holds three promises and
  // has to work up through 2 and 3 to reach the level-4 one.
  {
    const user = await makeUser("levelqueue");
    // 49 xp, so the first capped game run (60 xp) lands on 109 - past both
    // the level-2 threshold (50) and the level-3 one (109) in one award.
    await seedUserDoc(user.uid, "LEVELQUEUE", {xp: 49, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimLevelReward = httpsCallable(clientFunctions, "claimLevelReward");

    const session1 = await openGameSession("floppy_bird");
    await backdateSession(user.uid, session1, 60_000);
    const first = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 100, sessionId: session1,
    });
    assertEq("one award can cross two levels",
      (first.data as {milestoneLevels: number[]}).milestoneLevels, [2, 3]);

    // 109 -> 169 -> 229, crossing the level-4 threshold of 179 on the second
    // of these. Two runs rather than one because the per-session XP cap is 60
    // and the level-3 to level-4 span is 70.
    for (const _ of [0, 1]) {
      const session = await openGameSession("floppy_bird");
      await backdateSession(user.uid, session, 60_000);
      await claimReward({
        rewardType: "game", gameId: "floppy_bird", score: 100, sessionId: session,
      });
    }

    const climbed = await db.collection("users").doc(user.uid).get();
    assertEq("the level still rises with nothing claimed", climbed.get("level"), 4);
    assertEq("all three levels are queued",
      climbed.get("pendingLevelRewards"), [2, 3, 4]);
    assertEq("and not one star has been paid", climbed.get("points"), 0);

    // The client never names a level; the server always takes the lowest.
    // Passing one here is the check that it is ignored.
    const claim2 = await claimLevelReward({level: 4});
    assertEq("the first claim releases level 2, not the level asked for",
      (claim2.data as {level: number}).level, 2);
    assertEq("...paying the level-2 amount",
      (claim2.data as {pointsAwarded: number}).pointsAwarded, LEVEL_UP_POINTS[2]);
    assertEq("...and leaving the rest queued",
      (claim2.data as {pendingLevels: number[]}).pendingLevels, [3, 4]);

    const claim3 = await claimLevelReward({});
    assertEq("the second claim releases level 3",
      (claim3.data as {level: number}).level, 3);

    const claim4 = await claimLevelReward({});
    assertEq("the third claim releases level 4",
      (claim4.data as {level: number}).level, 4);
    assertEq("...and empties the queue",
      (claim4.data as {pendingLevels: number[]}).pendingLevels, []);

    const settled = await db.collection("users").doc(user.uid).get();
    assertEq(
      "three ads paid exactly the three levels' worth",
      settled.get("points"),
      LEVEL_UP_POINTS[2] + LEVEL_UP_POINTS[3] + LEVEL_UP_POINTS[4]
    );
    assertEq("the queue is empty", settled.get("pendingLevelRewards"), []);

    const levelUps = (await getLedgerEvents(user.uid))
      .filter((e) => e.source === "LEVEL_UP");
    assertEq("one ledger entry per level, no more", levelUps.length, 3);
    assertEq(
      "every one of them is applied",
      levelUps.every((e) => e.status === "applied"),
      true
    );

    const fourth = await claimLevelReward({});
    assertEq("a fourth ad releases nothing",
      (fourth.data as {claimed: boolean}).claimed, false);
  }

  // --- a claim with no account, and a queue with no ledger entry -----------
  {
    const user = await makeUser("levelclaimedge");
    // Queued by hand with no matching levelup: entry - the state a document
    // edited outside this code would be in. The amount must come from the
    // entry, so with none there is nothing safe to pay: the level is dropped
    // rather than guessed at from the table.
    await seedUserDoc(user.uid, "LEVELEDGE", {
      xp: 300, level: 5, points: 12, pendingLevelRewards: [3],
    });
    const claimLevelReward = httpsCallable(clientFunctions, "claimLevelReward");

    const res = await claimLevelReward({});
    const data = res.data as {claimed: boolean; reason: string; pendingLevels: number[]};
    assertEq("a queued level with no locked entry pays nothing", data.claimed, false);
    assertEq("...and says so", data.reason, "already_claimed");
    assertEq("...and is dropped rather than retried forever",
      data.pendingLevels, []);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the balance is untouched", snap.get("points"), 12);
    assertEq("the queue is cleared", snap.get("pendingLevelRewards"), []);
  }

  // --- a drifted level is repaired on the next award ---
  {
    const user = await makeUser("leveldrift");
    // XP worth level 4, but the stored level says 1 - the state you get after
    // retuning the curve, or after editing xp by hand.
    await seedUserDoc(user.uid, "LEVELDRIFT", {xp: 200, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("the response reports the true level", (res.data as {level: number}).level, 4);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the stored level is corrected to match xp", snap.get("level"), 4);
    assertEq("xp is unaffected by the repair", snap.get("xp"), 210);
  }

  // --- EVERY level from 2 up pays -----------------------------------------
  //
  // This block used to assert the opposite: that level 2 was not a milestone
  // and paid nothing. The reward table changed - a player could climb four
  // levels and be given nothing at all for it, which made levelling feel like
  // it did not pay - and the assertion is now that the smallest level-up on
  // the curve still credits its listed amount.
  {
    const user = await makeUser("everylevel");
    await seedUserDoc(user.uid, "EVERYLEVEL", {xp: 45, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const data = res.data as {level: number; milestonePoints: number};
    assertEq("levelled up to 2", data.level, 2);
    assertEq("level 2 locks its listed bonus", data.milestonePoints, LEVEL_UP_POINTS[2]);

    const snap = await db.collection("users").doc(user.uid).get();
    // Locked, not paid - the claim is what moves it. What this block is
    // about is that the SMALLEST level-up on the curve still promises its
    // listed amount rather than nothing at all.
    assertEq("the level-2 bonus is queued", snap.get("pendingLevelRewards"), [2]);
    assertEq("the level-2 bonus is not in the balance yet", snap.get("points"), 0);
    assertEq(
      "one LEVEL_UP ledger entry for the level crossed",
      (await getLedgerEvents(user.uid)).filter((e) => e.source === "LEVEL_UP").length,
      1
    );

    const claimed = await httpsCallable(clientFunctions, "claimLevelReward")({});
    assertEq("...and one ad releases it",
      (claimed.data as {pointsAwarded: number}).pointsAwarded, LEVEL_UP_POINTS[2]);
    assertEq(
      "the level-2 bonus reaches the balance",
      (await db.collection("users").doc(user.uid).get()).get("points"),
      LEVEL_UP_POINTS[2]
    );
  }

  // --- points buff: grant is admin-only, and never leaks onto XP sources ---
  {
    const user = await makeUser("buff");
    await seedUserDoc(user.uid, "BUFFUSER");
    const grantBuff = httpsCallable(clientFunctions, "grantPointsBuff");

    await assertThrows(
      "a normal user cannot grant themselves a buff",
      () => grantBuff({uid: user.uid, multiplier: 3, durationMs: 60_000}),
      "permission-denied"
    );

    const buffSnap = await db.collection("users").doc(user.uid).get();
    assertEq("no buff was stored by the rejected grant", buffSnap.get("activeBuff"), undefined);

    // Apply a buff directly (as a server-verified source would) and confirm
    // an XP-only source is completely unaffected by it.
    await db.collection("users").doc(user.uid).update({
      activeBuff: {
        multiplier: 3,
        expiresAt: Date.now() + 60 * 60 * 1000,
        grantedAt: Date.now(),
        source: "ADMIN_GRANT",
      },
    });

    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("a 3x buff does not turn quiz xp into points", (res.data as {pointsAwarded: number}).pointsAwarded, 0);
    assertEq("a 3x buff does not multiply quiz xp", (res.data as {xpAwarded: number}).xpAwarded, 10);

    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("points still zero with an active buff on an XP-only source", afterSnap.get("points"), 0);
    assertEq("xp gained exactly the base amount", afterSnap.get("xp"), 10);

    const events = await getLedgerEvents(user.uid);
    assertEq(
      "the quiz ledger entry records multiplierApplied 1 despite the active buff",
      events[0].multiplierApplied,
      1
    );
  }

  // --- points buff: an expired buff is inert ---
  {
    const user = await makeUser("buffexpired");
    await seedUserDoc(user.uid, "BUFFEXPIRED", {
      activeBuff: {
        multiplier: 3,
        expiresAt: Date.now() - 1000,
        grantedAt: Date.now() - 60_000,
        source: "ADMIN_GRANT",
      },
    });

    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("an expired buff awards no points", (res.data as {pointsAwarded: number}).pointsAwarded, 0);

    const events = await getLedgerEvents(user.uid);
    assertEq("an expired buff records multiplierApplied 1", events[0].multiplierApplied, 1);
  }

  // --- referral: the level milestone pays at or above level 10 ------------
  // AT OR ABOVE, not on the exact crossing. The crossing can happen inside
  // claimDailyStreak or claimDailyGoalBonus, neither of which runs this
  // check, and a reward keyed to it would then be lost for good. See
  // readReferrerForLevelUnlock.
  {
    // The referrer's award carries no XP at all now, so their balance is the
    // referral reward and nothing else - no level of their own to disentangle
    // from the assertion.
    const referrer = await makeUser("refpayee");
    await seedUserDoc(referrer.uid, "PAYME1", {points: 0, xp: 0, level: 1});

    // Level 10 is 954 xp on the published curve, so one quiz (10 xp) crosses
    // it from here.
    const referee = await makeUser("refcrosser");
    await seedUserDoc(referee.uid, "CROSS1", {points: 0, xp: 950, level: 9});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "PAYME1"});

    const afterSubmit = await db.collection("users").doc(referee.uid).get();
    assertEq("entering a code pays the referee nothing", afterSubmit.get("points"), 0);
    assertEq("and awards them no xp", afterSubmit.get("xp"), 950);
    assertEq(
      "nothing moved, so the referee has no ledger entry for it",
      (await getLedgerEvents(referee.uid)).length,
      0
    );
    assertEq(
      "the referrer is not paid at submit time",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );

    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    const paid = await db.collection("users").doc(referrer.uid).get();
    assertEq("the referrer is paid once the referee reaches level 10", paid.get("points"), 50);
    assertEq("stars only - the referrer gains no xp", paid.get("xp"), 0);
    assertEq(
      "the referee is flagged so it cannot pay twice",
      (await db.collection("users").doc(referee.uid).get()).get("referralLevelRewardPaid"),
      true
    );

    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 2, selectedAnswer: 2,
    });
    assertEq(
      "a later claim does not pay the level milestone twice",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      50
    );
  }

  // --- referral: the window to enter a code closes at level 10 -------------
  {
    const referrer = await makeUser("reflate");
    await seedUserDoc(referrer.uid, "LATE01", {points: 0, xp: 0, level: 1});

    // Already past level 10 before entering anything. A code here is not an
    // invitation that brought somebody to the app.
    const referee = await makeUser("reflatecode");
    await seedUserDoc(referee.uid, "LATE02", {points: 0, xp: 1000, level: 10});

    const res = await httpsCallable(clientFunctions, "submitReferral")({referralCode: "LATE01"});
    assertEq(
      "a code entered at level 10 is refused",
      (res.data as {status: string}).status,
      "window_closed"
    );

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("the referee is linked to nobody", refereeSnap.get("referredBy") ?? null, null);
    assertEq("and their code is still unspent", refereeSnap.get("hasUsedReferral"), false);

    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq(
      "so no later claim can pay the referrer",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );
  }

  // --- referral: below level 10, nothing is owed yet ---
  {
    const referrer = await makeUser("refearly");
    await seedUserDoc(referrer.uid, "EARLY1", {points: 0, xp: 0, level: 1});

    const referee = await makeUser("refearlycode");
    await seedUserDoc(referee.uid, "EARLY2", {points: 0, xp: 0, level: 1});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "EARLY1"});
    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    // The referee is on 10 xp - level 1, nowhere near 954.
    assertEq(
      "a referee under level 10 pays the referrer nothing",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );
    assertEq(
      "and is not flagged as settled",
      (await db.collection("users").doc(referee.uid).get()).get("referralLevelRewardPaid"),
      false
    );
  }

  // --- redemption: spending points ---
  {
    await db.collection("redemptionOptions").doc("pubg").set({
      name: "PUBG Mobile",
      code: "UC",
      enabled: true,
      servers: ["Global", "Korea"],
      packs: {
        uc_1000: {amount: "1000 UC", pointsCost: 1000},
        uc_small: {amount: "60 UC", pointsCost: 100, firstRedeemCost: 50},
        // Reachable through the offer and nowhere else.
        uc_taster: {
          amount: "30 UC", pointsCost: 90, firstRedeemCost: 40,
          firstRedeemOnly: true,
        },
      },
    });
    await db.collection("redemptionOptions").doc("locked").set({
      name: "High tier game",
      code: "HT",
      enabled: true,
      minLevel: 10,
      packs: {p: {amount: "100 Coins", pointsCost: 100}},
    });
    await db.collection("redemptionOptions").doc("disabled").set({
      name: "Retired game",
      code: "RT",
      enabled: false,
      packs: {p: {amount: "100 Coins", pointsCost: 100}},
    });

    const user = await makeUser("redeem");
    await seedUserDoc(user.uid, "REDEEMER", {points: 1500, xp: 300, level: 5});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const res = await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "5218840977", server: "Global",
    });
    const data = res.data as {pointsSpent: number; remainingPoints: number; redemptionId: string};
    assertEq("redemption spends the option's cost", data.pointsSpent, 1000);
    assertEq("remaining balance reported", data.remainingPoints, 500);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("points were debited", snap.get("points"), 500);
    // The whole point of separating the currencies:
    assertEq("spending points did NOT touch xp", snap.get("xp"), 300);
    assertEq("spending points did NOT touch level", snap.get("level"), 5);

    const redemptionSnap = await db
      .collection("redemptions").doc(data.redemptionId).get();
    assertEq("a redemption record was created", redemptionSnap.exists, true);
    assertEq("the redemption records its owner", redemptionSnap.get("uid"), user.uid);
    assertEq("redemption starts pending", redemptionSnap.get("status"), "pending");
    assertEq("redemption stores the player id", redemptionSnap.get("playerId"), "5218840977");
    assertEq("redemption stores the pack", redemptionSnap.get("packId"), "uc_1000");
    assertEq("redemption records the cost charged", redemptionSnap.get("pointsCost"), 1000);

    // The anti-farming claim, written in the same transaction as the debit.
    const linkSnap = await db.collection("playerLinks").doc("pubg__5218840977").get();
    assertEq("the player id was linked to the account", linkSnap.get("uid"), user.uid);

    const events = await getLedgerEvents(user.uid);
    const spendEvent = events.find((e) => e.source === "REDEMPTION");
    assertEq("a REDEMPTION ledger entry was written", spendEvent !== undefined, true);
    assertEq("the ledger records a negative amount", spendEvent?.finalPoints, -1000);
    assertEq("the ledger records no xp change", spendEvent?.xpAwarded, 0);

    await assertThrows(
      "redeeming more than the balance is rejected",
      () => redeem({
        optionId: "pubg", packId: "uc_1000", playerId: "5218840977", server: "Global",
      }),
      "failed-precondition"
    );
    await assertThrows(
      "a game above the user's level is rejected",
      () => redeem({optionId: "locked", packId: "p", playerId: "5218840977"}),
      "failed-precondition"
    );
    await assertThrows(
      "a disabled game is rejected",
      () => redeem({optionId: "disabled", packId: "p", playerId: "5218840977"}),
      "failed-precondition"
    );
    await assertThrows(
      "an unknown game is rejected",
      () => redeem({optionId: "does-not-exist", packId: "p", playerId: "5218840977"}),
      "invalid-argument"
    );
    await assertThrows(
      "an unknown pack is rejected",
      () => redeem({optionId: "pubg", packId: "nope", playerId: "5218840977", server: "Global"}),
      "invalid-argument"
    );

    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("no rejected redemption changed the balance", afterSnap.get("points"), 500);
  }

  // --- referral: the redeem milestone, and what does not count ------------
  {
    const referrer = await makeUser("refredeem");
    await seedUserDoc(referrer.uid, "REDEEM1", {points: 0, xp: 0, level: 1});

    const referee = await makeUser("refredeemer");
    await seedUserDoc(referee.uid, "REDEEM2", {points: 3000, xp: 0, level: 1});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "REDEEM1"});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    // The DISCOUNTED first redeem does not count. It is sold at a loss to get
    // somebody through the flow once, so paying an acquisition bonus on top
    // would be paying twice for the same event - and being the cheapest order
    // in the app it is exactly what a farmed account would reach for.
    await redeem({
      optionId: "pubg", packId: "uc_small", playerId: "7100000001",
      server: "Global", useFirstRedeem: true,
    });
    assertEq(
      "the discounted first redeem pays the referrer nothing",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );
    assertEq(
      "and leaves the milestone unsettled",
      (await db.collection("users").doc(referee.uid).get()).get("referralRedeemRewardPaid") ?? false,
      false
    );

    // A full-price order does.
    await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "7100000001", server: "Global",
    });

    const paid = await db.collection("users").doc(referrer.uid).get();
    assertEq("a full-price order pays the redeem milestone", paid.get("points"), 150);
    assertEq("stars only - no xp on this one either", paid.get("xp"), 0);
    assertEq(
      "the referee is flagged so it cannot pay twice",
      (await db.collection("users").doc(referee.uid).get()).get("referralRedeemRewardPaid"),
      true
    );

    const events = await getLedgerEvents(referrer.uid);
    assertEq("the referrer has one referral entry", events.length, 1);
    assertEq(
      "named for the milestone that paid it",
      events[0].id,
      `referral_redeem:${referee.uid}`
    );
    assertEq("carrying the redeem amount", events[0].basePoints, 150);

    // A second full-price order must not pay again.
    await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "7100000001", server: "Global",
    });
    assertEq(
      "a second order does not pay the redeem milestone again",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      150
    );
  }

  // --- redemption: a delivery needs somewhere to go ---
  {
    const user = await makeUser("redeemnoid");
    await seedUserDoc(user.uid, "NOID", {points: 5000});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    await assertThrows(
      "redemption without a player id is rejected",
      () => redeem({optionId: "pubg", packId: "uc_1000", server: "Global"}),
      "invalid-argument"
    );
    await assertThrows(
      "redemption without a server is rejected when the game has servers",
      () => redeem({optionId: "pubg", packId: "uc_1000", playerId: "5218840977"}),
      "invalid-argument"
    );
    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("balance untouched when delivery details are missing", snap.get("points"), 5000);
  }

  // --- redemption: a UID another account used is open at full price ---
  //
  // The hard claim this replaces refused any second account outright. It
  // caught farming, but it also caught one person topping up their own game
  // account from a second sign-in and told them the UID belonged to a
  // stranger. Full price is paid for, so there is nothing to farm; the rule
  // that survives guards the discount only, and is tested below.
  {
    const second = await makeUser("uidsecond");
    await seedUserDoc(second.uid, "SECOND", {points: 99999, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    // 5218840977 was redeemed into by the "redeem" user above.
    const ok = await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "5218840977", server: "Global",
    });
    assertEq(
      "a second account may redeem into the same player id at full price",
      (ok.data as {pointsSpent: number}).pointsSpent,
      1000
    );

    const link = await db.collection("playerLinks").doc("pubg__5218840977").get();
    assertEq(
      "the link now names the most recent account to use the id",
      link.get("uid"),
      second.uid
    );
  }

  // --- redemption: the discounted first redeem, once per account ---
  {
    const user = await makeUser("firstredeem");
    await seedUserDoc(user.uid, "FIRST", {points: 5000, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const res = await redeem({
      optionId: "pubg", packId: "uc_small", playerId: "8000000001",
      server: "Global", useFirstRedeem: true,
    });
    assertEq(
      "the first redeem charges the discounted price",
      (res.data as {pointsSpent: number}).pointsSpent,
      50
    );

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the discount is marked spent", snap.get("hasUsedFirstRedeem"), true);

    await assertThrows(
      "the discount cannot be used twice",
      () => redeem({
        optionId: "pubg", packId: "uc_small", playerId: "8000000001",
        server: "Global", useFirstRedeem: true,
      }),
      "failed-precondition"
    );

    // NO LEVEL GATE. The offer exists to prove the payout works, so holding
    // it back until level 10 held it back until well after a new user had
    // already decided whether to believe us.
    const junior = await makeUser("firstjunior");
    await seedUserDoc(junior.uid, "JUNIOR", {points: 5000, level: 1});
    const juniorRedeem = httpsCallable(clientFunctions, "redeemReward");
    const juniorRes = await juniorRedeem({
      optionId: "pubg", packId: "uc_small", playerId: "9000000001",
      server: "Global", useFirstRedeem: true,
    });
    assertEq(
      "a level 1 account can take the discount",
      (juniorRes.data as {pointsSpent: number}).pointsSpent,
      50
    );
  }

  // --- redemption: one discounted pack per GAME ACCOUNT, not per sign-in ---
  //
  // The farming case the removed UID claim used to cover, narrowed to the
  // only thing being given away: a second Gmail redeeming the cheap first
  // pack into the same game account.
  {
    const farmer = await makeUser("firstfarmer");
    await seedUserDoc(farmer.uid, "FARMER", {points: 5000, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    // 9000000001 already had a discounted pack delivered into it, by the
    // "firstjunior" account above.
    await assertThrows(
      "a second account cannot take the discount on the same player id",
      () => redeem({
        optionId: "pubg", packId: "uc_small", playerId: "9000000001",
        server: "Global", useFirstRedeem: true,
      }),
      "failed-precondition"
    );

    const snap = await db.collection("users").doc(farmer.uid).get();
    assertEq("a refused discount never debits the balance", snap.get("points"), 5000);
    // The app retires the card on this, so the offer is never dangled again
    // in front of somebody who can only ever be refused.
    assertEq(
      "the offer is marked unavailable for that account",
      snap.get("firstRedeemUnavailable"),
      true
    );
    assertEq(
      "but the account's own discount is NOT recorded as used",
      snap.get("hasUsedFirstRedeem") === true,
      false
    );

    // Full price into the same id is still fine - only the discount is gone.
    const ok = await redeem({
      optionId: "pubg", packId: "uc_small", playerId: "9000000001", server: "Global",
    });
    assertEq(
      "the same id is still redeemable at full price",
      (ok.data as {pointsSpent: number}).pointsSpent,
      100
    );
  }

  // --- redemption: rejecting a FIRST REDEEM returns the discount too ------
  // Goes through the real resolveRedemption callable rather than simulating
  // the refund with the Admin SDK. The refund block above does simulate it,
  // which is why it never noticed that the once-per-account discount stayed
  // burned after a rejection - the user got their stars back and silently
  // lost the offer they had spent them on.
  {
    const user = await makeUser("firstreject");
    await seedUserDoc(user.uid, "FIRSTREJ", {points: 5000, xp: 0, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const res = await redeem({
      optionId: "pubg", packId: "uc_small", playerId: "5400000001",
      server: "Global", useFirstRedeem: true,
    });
    const redemptionId = (res.data as {redemptionId: string}).redemptionId;

    const userRef = db.collection("users").doc(user.uid);
    const spent = await userRef.get();
    assertEq("the discount is burned when the order is placed",
      spent.get("hasUsedFirstRedeem"), true);
    assertEq("the discounted price was charged", spent.get("points"), 4950);

    // Become an admin the way the emulator allows: set the claim directly,
    // then force the client to pick up a fresh token.
    await admin.auth().setCustomUserClaims(user.uid, {admin: true});
    await clientAuth.currentUser?.getIdToken(true);

    await httpsCallable(clientFunctions, "resolveRedemption")({
      redemptionId, status: "rejected", reason: "wrong id",
    });

    const after = await userRef.get();
    assertEq("a rejected redemption returns the stars", after.get("points"), 5000);
    assertEq("a rejected FIRST redeem also returns the discount",
      after.get("hasUsedFirstRedeem"), false);

    // The link record itself stays - it is how an order is traced by hand -
    // but the per-UID discount mark has to come back WITH the account flag.
    // Restoring only one of the two hands the user an offer that their very
    // next attempt refuses, which then retires the card for good.
    const link = await db.collection("playerLinks").doc("pubg__5400000001").get();
    assertEq("the player id record survives a rejection", link.exists, true);
    assertEq("the per-uid discount mark is released with it",
      link.get("firstRedeemUsed"), false);

    await admin.auth().setCustomUserClaims(user.uid, {admin: false});
  }

  // --- redemption: an admin can reject WITHOUT giving the discount back ----
  //
  // The other half of the admin's choice. A mistyped UID deserves the offer
  // back; an account caught farming it does not, and no rule could tell the
  // two apart from the server's side.
  {
    const user = await makeUser("firstrejectkeep");
    await seedUserDoc(user.uid, "FIRSTKEEP", {points: 5000, xp: 0, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const res = await redeem({
      optionId: "pubg", packId: "uc_small", playerId: "5400000002",
      server: "Global", useFirstRedeem: true,
    });
    const redemptionId = (res.data as {redemptionId: string}).redemptionId;

    await admin.auth().setCustomUserClaims(user.uid, {admin: true});
    await clientAuth.currentUser?.getIdToken(true);

    await httpsCallable(clientFunctions, "resolveRedemption")({
      redemptionId, status: "rejected", reason: "farming", restoreFirstRedeem: false,
    });

    const after = await db.collection("users").doc(user.uid).get();
    // The stars always come back. A failed payout must never cost somebody
    // their balance, whatever they were caught doing.
    assertEq("the stars are refunded even when the discount is kept",
      after.get("points"), 5000);
    assertEq("the discount stays spent when the admin says so",
      after.get("hasUsedFirstRedeem"), true);

    const link = await db.collection("playerLinks").doc("pubg__5400000002").get();
    assertEq("and the per-uid mark stays with it",
      link.get("firstRedeemUsed"), true);

    await admin.auth().setCustomUserClaims(user.uid, {admin: false});
  }

  // --- redemption: concurrent requests must not overdraw ---
  {
    const user = await makeUser("redeemrace");
    // Enough for exactly ONE redemption.
    await seedUserDoc(user.uid, "RACER", {points: 1000});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const results = await Promise.allSettled([
      redeem({
        optionId: "pubg", packId: "uc_1000", playerId: "6100000001", server: "Global",
      }),
      redeem({
        optionId: "pubg", packId: "uc_1000", playerId: "6100000001", server: "Global",
      }),
    ]);
    const succeeded = results.filter((r) => r.status === "fulfilled");
    assertEq("exactly one of two concurrent redemptions succeeds", succeeded.length, 1);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("balance never goes negative", snap.get("points"), 0);

    const redemptions = await db
      .collection("redemptions").where("uid", "==", user.uid).get();
    assertEq("only one redemption record exists", redemptions.size, 1);

    const spendEvents = (await getLedgerEvents(user.uid)).filter((e) => e.source === "REDEMPTION");
    assertEq("only one spend ledger entry exists", spendEvents.length, 1);
  }

  // --- redemption: rejecting refunds the points ---
  {
    const user = await makeUser("redeemrefund");
    await seedUserDoc(user.uid, "REFUNDEE", {points: 1200, xp: 300, level: 5});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    const res = await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "6600000001", server: "Global",
    });
    const redemptionId = (res.data as {redemptionId: string}).redemptionId;

    const beforeSnap = await db.collection("users").doc(user.uid).get();
    assertEq("points debited before resolution", beforeSnap.get("points"), 200);

    // resolveRedemption is admin-only; a normal user must not be able to
    // approve or refund their own payout.
    const resolve = httpsCallable(clientFunctions, "resolveRedemption");
    await assertThrows(
      "a normal user cannot resolve their own redemption",
      () => resolve({redemptionId, status: "rejected"}),
      "permission-denied"
    );

    // Perform the refund the way an admin would (Admin SDK bypasses the
    // callable), then assert the ledger tells the whole story.
    const userRef = db.collection("users").doc(user.uid);
    const redemptionRef = db.collection("redemptions").doc(redemptionId);
    const original = await redemptionRef.get();

    await db.runTransaction(async (tx) => {
      tx.update(userRef, {points: admin.firestore.FieldValue.increment(1000)});
      tx.update(redemptionRef, {status: "rejected", refundedPoints: 1000});
      tx.update(
        userRef.collection("rewardEvents").doc(original.get("ledgerEventId")),
        {status: "reversed"}
      );
    });

    const afterSnap = await userRef.get();
    assertEq("a rejected redemption returns the points", afterSnap.get("points"), 1200);
    assertEq("a refund does not touch xp", afterSnap.get("xp"), 300);

    const reversed = await userRef.collection("rewardEvents").doc(original.get("ledgerEventId")).get();
    assertEq("the original spend is marked reversed", reversed.get("status"), "reversed");
  }

  // --- completeSignup: the server now owns account creation ---
  {
    const user = await makeUser("signup");
    const complete = httpsCallable(clientFunctions, "completeSignup");

    const res = await complete({displayName: "Fresh User", androidId: "device-fresh-1"});
    const data = res.data as {created: boolean; referralCode: string};
    assertEq("a new account is created", data.created, true);
    assertEq("a referral code is issued", data.referralCode.length, 6);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("starting points are zero", snap.get("points"), 0);
    assertEq("starting xp is zero", snap.get("xp"), 0);
    assertEq("starting level is 1", snap.get("level"), 1);
    assertEq("first device use can still claim a referral", snap.get("hasUsedReferral"), false);
    assertEq("email comes from the auth token", snap.get("email"), user.email);
    assertEq("quiz attempts are initialised", snap.get("quiz_attempts"), 0);

    // Idempotent: a retry after a dropped response must not reset the account.
    await db.collection("users").doc(user.uid).update({points: 500, xp: 250});
    const again = await complete({displayName: "Fresh User", androidId: "device-fresh-1"});
    assertEq("a repeat call does not create again", (again.data as {created: boolean}).created, false);

    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("a repeat call does not wipe the balance", afterSnap.get("points"), 500);
    assertEq("a repeat call does not wipe xp", afterSnap.get("xp"), 250);
    assertEq(
      "a repeat call keeps the original referral code",
      (again.data as {referralCode: string}).referralCode,
      data.referralCode
    );
  }

  // --- completeSignup: the device check is now server-enforced ---
  {
    // A second account from the same device cannot claim a referral bonus.
    // This used to be a client-side query that a modified app could skip.
    const second = await makeUser("signupdevice");
    const complete = httpsCallable(clientFunctions, "completeSignup");
    await complete({displayName: "Second Account", androidId: "device-fresh-1"});

    const snap = await db.collection("users").doc(second.uid).get();
    assertEq("a repeat device is flagged as having used its referral", snap.get("hasUsedReferral"), true);
  }

  // --- completeSignup: referral codes are unique ---
  {
    const complete = httpsCallable(clientFunctions, "completeSignup");
    const codes = new Set<string>();

    for (let i = 0; i < 5; i++) {
      await makeUser(`signupcode${i}`);
      const res = await complete({displayName: `User ${i}`, androidId: `device-code-${i}`});
      codes.add((res.data as {referralCode: string}).referralCode);
    }
    assertEq("every issued referral code is distinct", codes.size, 5);
  }

  // --- an offer-only pack is not for sale ------------------------------
  //
  // The taster exists so the ordinary minimum can sit above it. The pack id
  // comes from the client, so the rule has to hold against a request that
  // simply names it - which is what this sends.
  {
    const user = await makeUser("tasteronly");
    await seedUserDoc(user.uid, "TASTER", {points: 5000, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    await assertThrows(
      "an offer-only pack is refused at list price",
      () => redeem({
        optionId: "pubg", packId: "uc_taster", playerId: "6200000001",
        server: "Global",
      }),
      "invalid-argument"
    );
    const untouched = await db.collection("users").doc(user.uid).get();
    assertEq("a refused taster never debits the balance",
      untouched.get("points"), 5000);

    // Through the offer it goes through, at the discounted price.
    const ok = await redeem({
      optionId: "pubg", packId: "uc_taster", playerId: "6200000001",
      server: "Global", useFirstRedeem: true,
    });
    assertEq(
      "the same pack is reachable through the offer",
      (ok.data as {pointsSpent: number}).pointsSpent,
      40
    );
  }

  // --- the device signal the admin tool shows --------------------------
  //
  // Two accounts on one phone, one of them ordering. The point of the test is
  // that the count is REPORTED and nothing acts on it: the order goes through
  // exactly as it would from a device nobody had seen before, and the only
  // difference is a number the admin tool can draw.
  {
    const sharedDevice = "shared-device-aaaa1111";

    const first = await makeUser("devicefirst");
    await seedUserDoc(first.uid, "DEV1", {androidId: sharedDevice});

    const second = await makeUser("devicesecond");
    await seedUserDoc(second.uid, "DEV2", {
      androidId: sharedDevice, points: 5000, level: 50,
    });

    // The second account orders. Nothing about the shared device stops it.
    const redeem = httpsCallable(clientFunctions, "redeemReward");
    const res = await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "6100000001", server: "Global",
    });
    assertEq(
      "a shared device does not block the order",
      (res.data as {pointsSpent: number}).pointsSpent,
      1000
    );

    const orderId = (res.data as {redemptionId: string}).redemptionId;
    const order = await db.collection("redemptions").doc(orderId).get();
    assertEq("the order records the device it came from",
      order.get("androidId"), sharedDevice);
    assertEq("the order is pending like any other",
      order.get("status"), "pending");

    await admin.auth().setCustomUserClaims(second.uid, {admin: true});
    await clientAuth.currentUser?.getIdToken(true);

    const listed = await httpsCallable(clientFunctions, "listRedemptions")({
      status: "pending",
    });
    const rows = (listed.data as {
      redemptions: Array<{
        id: string; androidId: string; deviceAccountCount: number;
      }>;
    }).redemptions;

    const row = rows.find((r) => r.id === orderId);
    assertEq("the order is listed for the admin", row !== undefined, true);
    assertEq("...with the device id", row?.androidId, sharedDevice);
    assertEq("...and how many accounts share it", row?.deviceAccountCount, 2);

    // An order from a device nobody else has used reads as one account, not
    // as a warning - the badge has to be able to say "nothing to see here".
    const lone = await makeUser("devicelone");
    await seedUserDoc(lone.uid, "DEV3", {
      androidId: "lone-device-bbbb2222", points: 5000, level: 50,
    });
    const loneRes = await httpsCallable(clientFunctions, "redeemReward")({
      optionId: "pubg", packId: "uc_1000", playerId: "6100000002", server: "Global",
    });
    const loneId = (loneRes.data as {redemptionId: string}).redemptionId;

    await admin.auth().setCustomUserClaims(lone.uid, {admin: true});
    await clientAuth.currentUser?.getIdToken(true);
    const listedAgain = await httpsCallable(clientFunctions, "listRedemptions")({
      status: "pending",
    });
    const loneRow = (listedAgain.data as {
      redemptions: Array<{id: string; deviceAccountCount: number}>;
    }).redemptions.find((r) => r.id === loneId);
    assertEq("a device with one account reports one", loneRow?.deviceAccountCount, 1);

    await admin.auth().setCustomUserClaims(second.uid, {admin: false});
    await admin.auth().setCustomUserClaims(lone.uid, {admin: false});
  }

  // --- admin surface is locked to admins ---
  {
    const user = await makeUser("notadmin");
    await seedUserDoc(user.uid, "NOTADMIN");

    await assertThrows(
      "a normal user cannot list all redemptions",
      () => httpsCallable(clientFunctions, "listRedemptions")({status: "pending"}),
      "permission-denied"
    );
    await assertThrows(
      "a normal user cannot resolve a redemption",
      () => httpsCallable(clientFunctions, "resolveRedemption")({
        redemptionId: "anything", status: "approved",
      }),
      "permission-denied"
    );
    await assertThrows(
      "a normal user cannot grant a points buff",
      () => httpsCallable(clientFunctions, "grantPointsBuff")({
        uid: user.uid, multiplier: 3, durationMs: 60_000,
      }),
      "permission-denied"
    );
    await assertThrows(
      "an unlisted email cannot bootstrap itself to admin",
      () => httpsCallable(clientFunctions, "bootstrapAdmin")({}),
      "permission-denied"
    );
  }

  // --- THE tamper test: a forged correctness claim must not pay out ---
  {
    const user = await makeUser("quiztamper");
    await seedUserDoc(user.uid, "QUIZTAMPER");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Answer wrongly, while also asserting wasCorrect:true the way the old
    // client-trusting contract allowed. The extra field must be ignored.
    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0,
      selectedAnswer: 3, wasCorrect: true,
    });
    assertEq("forged wasCorrect:true with a wrong answer -> 0 xp", (res.data as {xpAwarded: number}).xpAwarded, 0);
    assertEq("forged wasCorrect:true -> server still grades it false", (res.data as {wasCorrect: boolean}).wasCorrect, false);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("forged claim awarded no xp at all", snap.get("xp"), 0);
    assertEq("forged claim awarded no points at all", snap.get("points"), 0);
  }

  // --- quiz: cross-category id collision is graded against the right key ---
  {
    const user = await makeUser("quizcategory");
    await seedUserDoc(user.uid, "QUIZCAT");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Sports:1 q0 correct is 0; Animals:1 q0 correct is 1. Same quizId "1".
    const res = await claimReward({
      rewardType: "quiz", category: "Sports", quizId: "1", questionIndex: 0, selectedAnswer: 0,
    });
    assertEq("Sports:1 graded against Sports answers, not Animals'", (res.data as {wasCorrect: boolean}).wasCorrect, true);

    const res2 = await claimReward({
      rewardType: "quiz", category: "Sports", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    assertEq("Animals' correct answer is wrong for Sports", (res2.data as {wasCorrect: boolean}).wasCorrect, false);
  }

  // --- quiz: unverifiable questions are refused, not guessed ---
  {
    const user = await makeUser("quizunknown");
    await seedUserDoc(user.uid, "QUIZUNK");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    await assertThrows(
      "unknown category rejected",
      () => claimReward({rewardType: "quiz", category: "Nope", quizId: "1", questionIndex: 0, selectedAnswer: 0}),
      "invalid-argument"
    );
    await assertThrows(
      "unknown quiz id rejected",
      () => claimReward({rewardType: "quiz", category: "Animals", quizId: "999", questionIndex: 0, selectedAnswer: 0}),
      "invalid-argument"
    );
    await assertThrows(
      "out-of-range question index rejected",
      () => claimReward({rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 99, selectedAnswer: 0}),
      "invalid-argument"
    );
    await assertThrows(
      "missing category rejected",
      () => claimReward({rewardType: "quiz", quizId: "1", questionIndex: 0, selectedAnswer: 0}),
      "invalid-argument"
    );

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("rejected quiz claims never consumed an attempt", snap.get("quiz_attempts"), 0);
  }

  // --- claimReward: quiz daily cap ---
  {
    const user = await makeUser("quizcap");
    // last_reset_time matters as much as the count. claimReward rolls the
    // counters over itself, and treats a missing or stale stamp as a new day
    // - so without a stamp of TODAY this seeds an account whose attempts
    // reset on the first claim, and the test passes whatever the cap does.
    await seedUserDoc(user.uid, "QUIZREF2", {
      quiz_attempts: 10,
      last_reset_time: Timestamp.now(),
    });
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    await assertThrows(
      "quiz over daily cap is rejected",
      () => claimReward({rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1}),
      "failed-precondition"
    );
  }

  // --- claimReward: game, session-gated ---
  {
    const user = await makeUser("game");
    await seedUserDoc(user.uid, "GAMEREF1");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const session1 = await openGameSession("floppy_bird");
    await backdateSession(user.uid, session1, 60_000); // 60s of play
    // Comfortably above GAME_XP_PER_SESSION_CAP, so what is asserted is the
    // cap doing its job rather than the divisor. 60s of play is what makes it
    // claimable: the rate check tests the CAPPED score, so a maxed run needs
    // roughly cap/maxScorePerSecond seconds behind it however high the raw
    // total goes.
    const res = await claimReward({rewardType: "game", gameId: "floppy_bird", score: 100, sessionId: session1});
    assertEq("floppy_bird score 100 -> 60 xp (per-session cap)", (res.data as {xpAwarded: number}).xpAwarded, 60);
    assertEq("games award no points (XP-only source)", (res.data as {pointsAwarded: number}).pointsAwarded, 0);

    const session2 = await openGameSession("tower_game");
    await backdateSession(user.uid, session2, 60_000);
    const res2 = await claimReward({rewardType: "game", gameId: "tower_game", score: 675, sessionId: session2});
    assertEq("tower_game score 675 -> floor(675/25)=27 xp", (res2.data as {xpAwarded: number}).xpAwarded, 27);

    await assertThrows(
      "replaying a consumed session is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 100, sessionId: session1}),
      "failed-precondition"
    );

    await assertThrows(
      "a claim with no session is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 42}),
      "invalid-argument"
    );

    await assertThrows(
      "a claim with a made-up session id is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: "not-a-real-session"}),
      "not-found"
    );

    const events = await getLedgerEvents(user.uid);
    // Filtered by source: a game claim that levels the account up also writes
    // a LEVEL_UP entry now, and counting the whole collection would make this
    // assertion about the reward ladder rather than about rejected claims.
    assertEq(
      "ledger has exactly 2 GAME events (rejections wrote nothing)",
      events.filter((e) => e.source === "GAME").length,
      2
    );
    const floppyEvent = events.find((e) => (e.metadata as any)?.gameId === "floppy_bird");
    assertEq("floppy_bird ledger entry xpAwarded 60", floppyEvent?.xpAwarded, 60);
    assertEq("floppy_bird ledger entry basePoints 0", floppyEvent?.basePoints, 0);
    // The RAW score is what the ledger keeps, not the capped payout. That is
    // deliberate - botting is spotted from the shape of a user's sessions, and
    // a ledger that only recorded the capped figure would hide exactly the
    // outliers worth looking at.
    assertEq("floppy_bird ledger entry metadata.score 100", (floppyEvent?.metadata as any)?.score, 100);
    assertEq("game ledger event id is derived from the session", floppyEvent?.id, `game:${session1}`);
    assertEq("floppy_bird ledger entry not multiplier eligible", floppyEvent?.multiplierEligible, false);
  }

  // --- claimDoubleXp: the rewarded-ad double ------------------------------
  //
  // Weekly XP IS counted: the tournament pot is fixed, so ad-boosted XP
  // redistributes rank without increasing what we pay, which is what makes
  // the offer worth making. The assertions below pin the boundary of that
  // decision - the double feeds the leaderboard, and nothing else. The daily
  // stats (which pay a real goal bonus) and the attempt counters stay put,
  // because a double is one attempt paid twice rather than a second attempt.
  {
    const user = await makeUser("doublexp");
    await seedUserDoc(user.uid, "DOUBLEXP1");
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimDoubleXp = httpsCallable(clientFunctions, "claimDoubleXp");

    await assertThrows(
      "doubling an entry that does not exist is rejected",
      () => claimDoubleXp({eventId: "game:not-a-real-session"}),
      "not-found"
    );
    await assertThrows(
      "doubling with no event id is rejected",
      () => claimDoubleXp({}),
      "invalid-argument"
    );
    // `doc("a/b/c")` is a path, not a name, so a slash would reach outside
    // this user's rewardEvents entirely.
    await assertThrows(
      "an event id containing a path separator is rejected",
      () => claimDoubleXp({eventId: "../../users/someoneelse"}),
      "invalid-argument"
    );

    const session = await openGameSession("floppy_bird");
    await backdateSession(user.uid, session, 60_000);
    const base = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: session,
    });
    const baseData = base.data as {xpAwarded: number; eventId: string};
    // 42, not the per-session cap: floppy_bird divides its score by 1 and
    // GAME_XP_PER_SESSION_CAP is 60, so this run is paid in full. Chosen so
    // the doubled total stays under the cap too - the cap bounds each award,
    // and a run at the ceiling would make the double look capped when it is
    // simply a second award of the same size.
    assertEq("the run pays 42 xp before any ad", baseData.xpAwarded, 42);
    assertEq("the claim returns the ledger id to double against",
      baseData.eventId, `game:${session}`);

    const beforeSnap = await db.collection("users").doc(user.uid).get();
    const weeklyBefore = Number(beforeSnap.get("weeklyXp") || 0);
    const statsBefore = beforeSnap.get("dailyStats");
    const attemptsBefore = beforeSnap.get("game_attempts");

    const dbl = await claimDoubleXp({eventId: baseData.eventId});
    const dblData = dbl.data as {xpAwarded: number; totalXp: number};
    assertEq("the double pays the same again", dblData.xpAwarded, 42);
    assertEq("the double reports the running total", dblData.totalXp, 84);

    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("both halves reach the stored xp", afterSnap.get("xp"), 84);
    assertEq("the double DOES feed the weekly leaderboard",
      afterSnap.get("weeklyXp"), weeklyBefore + 42);
    assertEq("the double does NOT advance the daily goal counters",
      afterSnap.get("dailyStats"), statsBefore);
    assertEq("the double does NOT spend another attempt",
      afterSnap.get("game_attempts"), attemptsBefore);

    const events = await getLedgerEvents(user.uid);
    const doubleEvent = events.find((e) => e.id === `${baseData.eventId}:double`);
    assertEq("the double writes its own ledger entry", doubleEvent !== undefined, true);
    assertEq("the double ledger entry records the bonus xp", doubleEvent?.xpAwarded, 42);
    assertEq("the double ledger entry awards no points", doubleEvent?.finalPoints, 0);
    assertEq("the double ledger entry names the entry it doubled",
      (doubleEvent?.metadata as any)?.doubledFrom, baseData.eventId);
    assertEq(
      "base and bonus are two entries, not one inflated one",
      events.filter((e) => e.source === "GAME").length,
      2
    );

    // Idempotency. The app claims from the ad's reward callback, so a
    // redelivery or an impatient second tap has to land on the same document
    // rather than pay again.
    await assertThrows(
      "the same entry cannot be doubled twice",
      () => claimDoubleXp({eventId: baseData.eventId}),
      "already-exists"
    );
    // A bonus entry carries the same source and shape as the base it came
    // from, so without an explicit guard it would satisfy every other check
    // and double itself, and again, for as long as the window held.
    await assertThrows(
      "a bonus entry cannot itself be doubled",
      () => claimDoubleXp({eventId: `${baseData.eventId}:double`}),
      "invalid-argument"
    );
    const twiceSnap = await db.collection("users").doc(user.uid).get();
    assertEq("the refused second double changed nothing", twiceSnap.get("xp"), 84);
  }

  // --- claimDoubleXp: a week that rolls over mid-offer ---------------------
  //
  // The gap between a claim and its double is exactly one rewarded ad wide,
  // and the week boundary does not care. Reading through weeklyRollover rather
  // than incrementing blindly is what stops the bonus being added to a total
  // that belongs to last week's tournament - which would seed the new week
  // with a standing the player did not earn in it - and what preserves an
  // entered week's closing total for its settlement.
  {
    const user = await makeUser("doublerollover");
    const thisWeek = Math.floor((Math.floor(Date.now() / 86_400_000) + 3) / 7);
    await seedUserDoc(user.uid, "DBLROLL1");
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimDoubleXp = httpsCallable(clientFunctions, "claimDoubleXp");

    const session = await openGameSession("floppy_bird");
    await backdateSession(user.uid, session, 60_000);
    const base = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: session,
    });
    const eventId = (base.data as {eventId: string}).eventId;

    // Rewind the stored week to last week's, as if the boundary passed while
    // the ad was on screen. The player had entered last week's tournament.
    const lastWeek = thisWeek - 1;
    await db.collection("users").doc(user.uid)
      .update({weekKey: lastWeek, weeklyXp: 400, tournamentWeek: lastWeek});

    await claimDoubleXp({eventId});

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("a rolled-over week starts from the bonus alone",
      snap.get("weeklyXp"), 42);
    assertEq("the double stamps the current week", snap.get("weekKey"), thisWeek);
    assertEq("...and carries the entered week's total for its settlement",
      snap.get("lastWeeklyXp"), 400);
    // The lifetime total is untouched by any of this - only the weekly
    // standing resets.
    assertEq("lifetime xp still has both halves", snap.get("xp"), 84);
  }

  // --- claimDoubleXp: quizzes take the same offer -------------------------
  //
  // A quiz attempt is ONE question, so it has exactly one ledger entry - the
  // same shape a game run has, which is why both go through one callable
  // keyed on the entry rather than on anything game-specific.
  {
    const user = await makeUser("doublequiz");
    await seedUserDoc(user.uid, "DOUBLEQ1");
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimDoubleXp = httpsCallable(clientFunctions, "claimDoubleXp");

    const correct = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const correctData = correct.data as {xpAwarded: number; eventId: string; wasCorrect: boolean};
    assertEq("a correct answer pays quiz xp", correctData.xpAwarded, 10);
    assertEq("the quiz claim returns a ledger id", typeof correctData.eventId, "string");

    const weeklyBefore = Number(
      (await db.collection("users").doc(user.uid).get()).get("weeklyXp") || 0
    );

    const dbl = await claimDoubleXp({eventId: correctData.eventId});
    assertEq("the quiz double pays the same again",
      (dbl.data as {xpAwarded: number}).xpAwarded, 10);

    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("both halves of the quiz reach the stored xp", afterSnap.get("xp"), 20);
    assertEq("the quiz double DOES feed the weekly leaderboard",
      afterSnap.get("weeklyXp"), weeklyBefore + 10);
    assertEq("the quiz double does NOT advance the correct-answer goal counter",
      (afterSnap.get("dailyStats") as {correct: number}).correct, 1);
    assertEq("the quiz double does NOT spend another attempt",
      afterSnap.get("quiz_attempts"), 1);

    const doubleEvent = (await getLedgerEvents(user.uid))
      .find((e) => e.id === `${correctData.eventId}:double`);
    assertEq("the quiz double is recorded as a QUIZ event", doubleEvent?.source, "QUIZ");
    assertEq("the quiz double awards no points", doubleEvent?.finalPoints, 0);

    // A wrong answer earns nothing, so there is nothing to double. The client
    // hides the offer in this case, but the server is what decides it.
    const wrong = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 1, selectedAnswer: 0,
    });
    const wrongData = wrong.data as {xpAwarded: number; eventId: string};
    assertEq("a wrong answer pays nothing", wrongData.xpAwarded, 0);
    await assertThrows(
      "a wrong answer cannot be doubled",
      () => claimDoubleXp({eventId: wrongData.eventId}),
      "failed-precondition"
    );
  }

  // --- claimDoubleXp: the double stacks with the XP booster ----------------
  //
  // These are two different mechanisms and they are SUPPOSED to compound. The
  // booster is a time-windowed multiplier on future earnings, granted by an
  // admin; the double is a one-shot rewarded-ad match on one entry that has
  // already been paid. A boosted run pays 2x, and doubling it pays that same
  // boosted figure again.
  //
  // The regression this guards is the per-attempt ceiling. The caps govern
  // the PRE-buff score - gameXpForScore clamps before buildAward multiplies -
  // so a boosted award legitimately exceeds the bare cap. Comparing it to an
  // unscaled ceiling silently paid a boosted player less for their double
  // than the ad promised, which is invisible in the UI and gets worse the
  // bigger the booster.
  {
    const adminUser = await makeUser("doublebuffadmin");
    await seedUserDoc(adminUser.uid, "DBLBUFFAD");
    await admin.auth().setCustomUserClaims(adminUser.uid, {admin: true});

    const user = await makeUser("doublebuff");
    await seedUserDoc(user.uid, "DBLBUFF1");

    // Granted as the admin, then the run is played as the user.
    await signInWithEmailAndPassword(clientAuth, adminUser.email!, "Test1234!");
    const granted = await httpsCallable(clientFunctions, "grantPointsBuff")({
      uid: user.uid, multiplier: 2, durationMs: 10 * 60 * 1000, kind: "xp",
    });
    assertEq("the xp booster was granted", (granted.data as {applied: boolean}).applied, true);

    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimDoubleXp = httpsCallable(clientFunctions, "claimDoubleXp");

    // Score 100 is above GAME_XP_PER_SESSION_CAP, so the run caps at 60 and
    // the booster then doubles it to 120 - a figure deliberately ABOVE the
    // bare ceiling, which is the whole point of this block.
    const session = await openGameSession("floppy_bird");
    await backdateSession(user.uid, session, 60_000);
    const base = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 100, sessionId: session,
    });
    const baseData = base.data as {xpAwarded: number; eventId: string};
    assertEq("the booster doubles the capped run to 120 xp", baseData.xpAwarded, 120);

    const dbl = await claimDoubleXp({eventId: baseData.eventId});
    assertEq("the double matches the BOOSTED figure, not the bare cap",
      (dbl.data as {xpAwarded: number}).xpAwarded, 120);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("booster and double compound to 4x the capped run", snap.get("xp"), 240);

    // The bonus entry must not re-apply the booster on top of the figure that
    // already carried it - that would be 2x again, not a double.
    const doubleEvent = (await getLedgerEvents(user.uid))
      .find((e) => e.id === `${baseData.eventId}:double`);
    assertEq("the bonus entry does not re-apply the booster",
      doubleEvent?.xpMultiplierApplied, 1);
    assertEq("the bonus entry records the boosted amount as its base",
      doubleEvent?.baseXp, 120);
  }

  // --- claimDoubleXp: what it refuses -------------------------------------
  {
    const user = await makeUser("doublexpedge");
    // Seeded just below the level-2 threshold so one correct answer crosses
    // it and writes a LEVEL_UP entry to try doubling.
    await seedUserDoc(user.uid, "DOUBLEXP2", {xp: 45, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const claimDoubleXp = httpsCallable(clientFunctions, "claimDoubleXp");

    // A run worth nothing has nothing to double.
    const zeroSession = await openGameSession("floppy_bird");
    await backdateSession(user.uid, zeroSession, 60_000);
    const zero = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 0, sessionId: zeroSession,
    });
    await assertThrows(
      "a run that earned no xp cannot be doubled",
      () => claimDoubleXp({eventId: (zero.data as {eventId: string}).eventId}),
      "failed-precondition"
    );

    // The window. Without it the base ledger entry is a permanent asset a
    // client could bank and cash in later in bulk.
    const staleSession = await openGameSession("floppy_bird");
    await backdateSession(user.uid, staleSession, 60_000);
    const stale = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: staleSession,
    });
    const staleId = (stale.data as {eventId: string}).eventId;
    await db.collection("users").doc(user.uid)
      .collection("rewardEvents").doc(staleId)
      .update({createdAt: Timestamp.fromMillis(Date.now() - 60 * 60 * 1000)});
    await assertThrows(
      "an entry older than the double window is refused",
      () => claimDoubleXp({eventId: staleId}),
      "failed-precondition"
    );

    // Only play earns a double. A milestone bonus is fixed by design, and an
    // ad must not be able to re-pay one.
    const levelUpEvent = (await getLedgerEvents(user.uid)).find((e) => e.source === "LEVEL_UP");
    assertEq("the account crossed a level, writing a LEVEL_UP entry",
      levelUpEvent !== undefined, true);
    await assertThrows(
      "a level-up bonus cannot be doubled",
      () => claimDoubleXp({eventId: String(levelUpEvent?.id)}),
      "invalid-argument"
    );
  }

  // --- game: implausible results are rejected ---
  {
    const user = await makeUser("gamecheat");
    await seedUserDoc(user.uid, "GAMECHEAT");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // The headline attack: open a session, immediately claim a huge score.
    const instant = await openGameSession("tower_game");
    await assertThrows(
      "claiming instantly (under the minimum session length) is rejected",
      () => claimReward({rewardType: "game", gameId: "tower_game", score: 999_999, sessionId: instant}),
      "invalid-argument"
    );

    const fast = await openGameSession("floppy_bird");
    await backdateSession(user.uid, fast, 10_000); // 10s
    await assertThrows(
      "a score impossible for the elapsed time is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 5_000, sessionId: fast}),
      "invalid-argument"
    );

    await assertThrows(
      "a rejected session is burned and cannot be retried with a lower score",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 5, sessionId: fast}),
      "failed-precondition"
    );

    const stale = await openGameSession("floppy_bird");
    await backdateSession(user.uid, stale, 5 * 60 * 60 * 1000); // 5h
    await assertThrows(
      "a stale session is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 5, sessionId: stale}),
      "invalid-argument"
    );

    const mismatch = await openGameSession("floppy_bird");
    await backdateSession(user.uid, mismatch, 60_000);
    await assertThrows(
      "claiming a different game than the session was opened for is rejected",
      () => claimReward({rewardType: "game", gameId: "tower_game", score: 50, sessionId: mismatch}),
      "invalid-argument"
    );

    await assertThrows(
      "unknown game rejected",
      () => claimReward({rewardType: "game", gameId: "not_a_real_game", score: 5, sessionId: mismatch}),
      "invalid-argument"
    );

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("no xp awarded from any rejected game claim", snap.get("xp"), 0);
    assertEq("no points awarded from any rejected game claim", snap.get("points"), 0);
    const rejectedEvents = await getLedgerEvents(user.uid);
    assertEq("no ledger entries from rejected game claims", rejectedEvents.length, 0);
  }

  // --- game: the daily allowance ---
  {
    const user = await makeUser("gamecap");
    await seedUserDoc(user.uid, "GAMECAP");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Ten runs is the whole day's allowance.
    for (let i = 0; i < MAX_DAILY_GAME_SESSIONS; i++) {
      const session = await openGameSession("floppy_bird");
      await backdateSession(user.uid, session, 60_000);
      await claimReward({rewardType: "game", gameId: "floppy_bird", score: 5, sessionId: session});
    }

    await assertThrows(
      "an eleventh game session cannot even be opened",
      () => httpsCallable(clientFunctions, "startGameSession")({gameId: "floppy_bird"}),
      "failed-precondition"
    );

    const events = await getLedgerEvents(user.uid);
    assertEq(
      "exactly the allowance was paid out",
      events.filter((e) => e.source === "GAME").length,
      MAX_DAILY_GAME_SESSIONS
    );

    // Quizzes are a separate allowance sharing the same day stamp, so using up
    // the games must not have spent any quiz attempts.
    const quizzed = await claimReward({
      rewardType: "quiz",
      category: "Animals",
      quizId: "1",
      questionIndex: 0,
      selectedAnswer: 1,
    });
    assertEq(
      "the quiz allowance is untouched by a spent game allowance",
      (quizzed.data as {attempts: number}).attempts,
      1
    );

  }

  // --- game: another user's session id can't be used ---
  {
    const victim = await makeUser("gamevictim");
    await seedUserDoc(victim.uid, "GAMEVICTIM");
    const victimSession = await openGameSession("floppy_bird");

    const attacker = await makeUser("gameattacker");
    await seedUserDoc(attacker.uid, "GAMEATTACKER");
    await signInWithEmailAndPassword(clientAuth, attacker.email!, "Test1234!");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    await assertThrows(
      "a session belonging to another user is not found for the attacker",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 10, sessionId: victimSession}),
      "not-found"
    );
  }

  // --- submitReferral: happy path + double-submit + invalid code ---
  {
    const referrer = await makeUser("referrer");
    await seedUserDoc(referrer.uid, "REFCODE1");

    const referee = await makeUser("referee");
    await seedUserDoc(referee.uid, "REFCODE1B");
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    const submitReferral = httpsCallable(clientFunctions, "submitReferral");

    const res = await submitReferral({referralCode: "refcode1"}); // lowercase on purpose
    assertEq("valid referral -> success", (res.data as {status: string}).status, "success");

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("the referee is paid nothing", refereeSnap.get("points"), 0);
    assertEq("and gains no xp", refereeSnap.get("xp"), 0);
    assertEq("referee marked hasUsedReferral", refereeSnap.get("hasUsedReferral"), true);
    assertEq("and linked to the referrer", refereeSnap.get("referredBy"), referrer.uid);

    // The link is the only thing written. Nothing moved, so there is nothing
    // for the ledger to record.
    assertEq("no ledger entry is written", (await getLedgerEvents(referee.uid)).length, 0);

    await assertThrows(
      "second submit rejected (already used)",
      () => submitReferral({referralCode: "REFCODE1"}),
      "failed-precondition"
    );
  }

  // --- submitReferral: invalid code on a fresh account ---
  {
    const referee = await makeUser("referee2");
    await seedUserDoc(referee.uid, "REFCODE2");
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");
    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    const res = await submitReferral({referralCode: "DOES_NOT_EXIST"});
    assertEq("invalid code -> invalid_code status", (res.data as {status: string}).status, "invalid_code");
  }

  // --- submitReferral: self-referral rejected ---
  {
    const user = await makeUser("selfref");
    await seedUserDoc(user.uid, "SELFCODE");
    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");
    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    const res = await submitReferral({referralCode: "SELFCODE"});
    assertEq("self-referral -> invalid_code status", (res.data as {status: string}).status, "invalid_code");
  }

  // --- referrer payout unlocks on the referee's LEVEL ---------------------
  {
    const referrer = await makeUser("referrer2");
    await seedUserDoc(referrer.uid, "BOOST100", {points: 0, xp: 0, level: 1});

    // 900 xp is level 9; level 10 is 954. Two 30-xp games straddle it.
    const referee = await makeUser("referee3");
    await seedUserDoc(referee.uid, "REFCODE3", {points: 0, xp: 900, level: 9});
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    await submitReferral({referralCode: "BOOST100"});

    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // 900 -> 930, still level 9. The score sits under the per-session cap on
    // purpose: a capped score would tie this block to the cap's value, and
    // what it is about is the level either side of 954.
    const first = await openGameSession("floppy_bird");
    await backdateSession(referee.uid, first, 60_000);
    await claimReward({rewardType: "game", gameId: "floppy_bird", score: 30, sessionId: first});

    let referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer is NOT paid while the referee is still level 9", referrerSnap.get("points"), 0);

    // 930 -> 960, which is level 10.
    const second = await openGameSession("floppy_bird");
    await backdateSession(referee.uid, second, 60_000);
    await claimReward({rewardType: "game", gameId: "floppy_bird", score: 30, sessionId: second});

    // The payout is part of the same transaction, so it is visible
    // immediately - no polling for an async trigger.
    referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer got +50 once the referee reached level 10", referrerSnap.get("points"), 50);
    assertEq("and no xp with it", referrerSnap.get("xp"), 0);

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("referee is at or past level 10", refereeSnap.get("xp") >= 954, true);
    assertEq("referee marked referralLevelRewardPaid", refereeSnap.get("referralLevelRewardPaid"), true);

    const referrerEvents = await getLedgerEvents(referrer.uid);
    assertEq("referrer ledger has exactly 1 referral event", referrerEvents.length, 1);
    assertEq(
      "referrer ledger event id is deterministic and names the milestone",
      referrerEvents[0].id,
      `referral_level:${referee.uid}`
    );
    assertEq("referrer ledger basePoints 50", referrerEvents[0].basePoints, 50);
    assertEq("referrer ledger xpAwarded 0", referrerEvents[0].xpAwarded, 0);

    // Earning more XP must not pay the level milestone a second time.
    const extra = await openGameSession("floppy_bird");
    await backdateSession(referee.uid, extra, 60_000);
    await claimReward({rewardType: "game", gameId: "floppy_bird", score: 30, sessionId: extra});

    referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer is not paid twice on further xp gains", referrerSnap.get("points"), 50);
    assertEq(
      "referrer still has exactly 1 referral ledger entry",
      (await getLedgerEvents(referrer.uid)).length,
      1
    );
  }

  // --- a referee who never earns Points still unlocks their referrer ---
  {
    // Quizzes and games award no Points at all, so a referee's BALANCE is not
    // a signal of engagement. Levels are, which is what the milestone tests.
    const referrer = await makeUser("referrer4");
    await seedUserDoc(referrer.uid, "QUIZONLY", {points: 0, xp: 0, level: 1});

    // 934 + two quizzes (10 xp each) = 954, exactly level 10.
    const referee = await makeUser("referee5");
    await seedUserDoc(referee.uid, "REFCODE5", {points: 0, xp: 934, level: 9});
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "QUIZONLY"});

    const claimReward = httpsCallable(clientFunctions, "claimReward");
    await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 2, selectedAnswer: 2,
    });

    const refereeEvents = await getLedgerEvents(referee.uid);
    const quizPoints = refereeEvents
      .filter((e) => e.source === "QUIZ")
      .reduce((sum, e) => sum + Number(e.finalPoints || 0), 0);
    assertEq("quizzes awarded the referee no points at all", quizPoints, 0);
    assertEq(
      "and the referee has no referral entry of their own",
      refereeEvents.filter((e) => String(e.source).startsWith("REFERRAL")).length,
      0
    );

    const referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("quiz-only engagement still pays the referrer", referrerSnap.get("points"), 50);
  }

  // --- submitReferral: concurrent duplicate requests don't double-award ---
  {
    const referrer = await makeUser("referrer3");
    await seedUserDoc(referrer.uid, "RACECODE");

    const referee = await makeUser("referee4");
    await seedUserDoc(referee.uid, "REFCODE4");
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    // Simulate a client retry racing the original request.
    const results = await Promise.allSettled([
      submitReferral({referralCode: "RACECODE"}),
      submitReferral({referralCode: "RACECODE"}),
    ]);

    const succeeded = results.filter((r) => r.status === "fulfilled");
    assertEq("concurrent double-submit: exactly one call succeeds", succeeded.length, 1);

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("concurrent double-submit: the link is written once", refereeSnap.get("hasUsedReferral"), true);
    assertEq("concurrent double-submit: linked to the right referrer", refereeSnap.get("referredBy"), referrer.uid);
    assertEq("concurrent double-submit: still no referee payment", refereeSnap.get("points"), 0);
  }

  // --- the day rollover, which no longer has a callable of its own ---------
  //
  // checkAndResetQuizAttempts used to own this and was removed: it stamped
  // last_reset_time while resetting only quiz_attempts, and the two counters
  // share that stamp, so it convinced claimReward that the day had already
  // rolled over and froze game_attempts at yesterday's value. The rollover
  // now happens inside the claim transaction, so the thing worth asserting is
  // that ONE claim on a stale stamp resets BOTH counters.
  {
    const user = await makeUser("rollover");
    await seedUserDoc(user.uid, "ROLLOVER1", {
      quiz_attempts: 10,
      game_attempts: 10,
      last_reset_time: Timestamp.fromDate(new Date("2000-01-01T00:00:00Z")),
    });
    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");

    // A stale stamp must not read as "ten games played today".
    const sessionId = await openGameSession("floppy_bird");
    assertEq(
      "stale stamp -> a game session still opens",
      typeof sessionId === "string" && sessionId.length > 0,
      true
    );
    await backdateSession(user.uid, sessionId, 60_000);

    const claim = httpsCallable(clientFunctions, "claimReward");
    const claimed = await claim({
      rewardType: "game",
      gameId: "floppy_bird",
      score: 12,
      sessionId,
    });
    const data = claimed.data as {gameAttempts: number};
    assertEq("stale stamp -> game attempts restart at 1", data.gameAttempts, 1);

    const utcDayOf = (ms: number) => Math.floor(ms / 86_400_000);
    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("a game claim resets the quiz counter too",
      snap.get("quiz_attempts"), 0);
    assertEq("a game claim stamps today",
      utcDayOf(snap.get("last_reset_time").toMillis()), utcDayOf(Date.now()));
  }

  // --- bonus attempts bought with a rewarded ad ---------------------------
  //
  // The ceiling moves; the used counter does not. Everything below is there
  // because getting one of these wrong is invisible in the UI and expensive
  // in payouts.
  {
    const user = await makeUser("bonusgame");
    await seedUserDoc(user.uid, "BONUSG1");
    const grant = httpsCallable(clientFunctions, "grantBonusAttempt");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Spend the whole ordinary allowance first.
    for (let i = 0; i < MAX_DAILY_GAME_SESSIONS; i++) {
      const session = await openGameSession("floppy_bird");
      await backdateSession(user.uid, session, 60_000);
      await claimReward({
        rewardType: "game", gameId: "floppy_bird", score: 5, sessionId: session,
      });
    }
    await assertThrows(
      "at the cap, no further session opens",
      () => httpsCallable(clientFunctions, "startGameSession")({gameId: "floppy_bird"}),
      "failed-precondition"
    );

    const granted = await grant({activity: "game", adWatched: true});
    const grantData = granted.data as {granted: boolean; allowance: number};
    assertEq("a bonus attempt is granted", grantData.granted, true);
    assertEq("the allowance moves by one",
      grantData.allowance, MAX_DAILY_GAME_SESSIONS + 1);

    // The bought attempt has to pass BOTH gates - startGameSession's
    // pre-check and the claim - or the user paid an ad for nothing.
    const bonusSession = await openGameSession("floppy_bird");
    await backdateSession(user.uid, bonusSession, 60_000);
    const bonusClaim = await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 5, sessionId: bonusSession,
    });
    assertEq("the bought attempt claims as the eleventh run",
      (bonusClaim.data as {gameAttempts: number}).gameAttempts,
      MAX_DAILY_GAME_SESSIONS + 1);

    await assertThrows(
      "the ceiling moved by exactly one, not indefinitely",
      () => httpsCallable(clientFunctions, "startGameSession")({gameId: "floppy_bird"}),
      "failed-precondition"
    );

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the used counter still counts real runs only",
      snap.get("game_attempts"), MAX_DAILY_GAME_SESSIONS + 1);
    assertEq("the bonus is held in its own counter",
      snap.get("bonus_game_attempts"), 1);
    assertEq("the lifetime grant total is recorded",
      snap.get("bonusAttemptsGranted"), 1);
  }

  // --- the bonus cap is the only thing bounding an unverified ad -----------
  {
    const user = await makeUser("bonuscap");
    await seedUserDoc(user.uid, "BONUSC1");
    const grant = httpsCallable(clientFunctions, "grantBonusAttempt");

    for (let i = 1; i <= MAX_DAILY_BONUS_ATTEMPTS; i++) {
      const res = await grant({activity: "quiz", adWatched: true});
      assertEq(`quiz bonus ${i} of ${MAX_DAILY_BONUS_ATTEMPTS} is granted`,
        (res.data as {bonusAttempts: number}).bonusAttempts, i);
    }

    const refused = await grant({activity: "quiz", adWatched: true});
    const refusedData = refused.data as {granted: boolean; reason: string};
    assertEq("one past the cap is refused", refusedData.granted, false);
    assertEq("...and says why, because the ad has already played",
      refusedData.reason, "daily_bonus_limit");

    // Refusing must not have quietly charged the user anyway.
    const capped = await db.collection("users").doc(user.uid).get();
    assertEq("a refused grant does not move the counter",
      capped.get("bonus_quiz_attempts"), MAX_DAILY_BONUS_ATTEMPTS);

    const gameGrant = await grant({activity: "game", adWatched: true});
    assertEq("games are capped independently of quizzes",
      (gameGrant.data as {granted: boolean}).granted, true);

    await assertThrows("a grant that claims no ad is refused",
      () => grant({activity: "game", adWatched: false}), "invalid-argument");
    await assertThrows("an unknown activity is refused",
      () => grant({activity: "dance", adWatched: true}), "invalid-argument");
  }

  // --- a grant on a stale day --------------------------------------------
  //
  // The counters and both bonus counters ride ONE day stamp, so a grant that
  // re-stamps the day without clearing the rest would either hand out a free
  // allowance every morning or - worse - sell an attempt into a day that
  // still believes yesterday's ten were spent.
  {
    const user = await makeUser("bonusroll");
    await seedUserDoc(user.uid, "BONUSR1", {
      quiz_attempts: 10,
      game_attempts: 10,
      bonus_quiz_attempts: MAX_DAILY_BONUS_ATTEMPTS,
      bonus_game_attempts: MAX_DAILY_BONUS_ATTEMPTS,
      last_reset_time: Timestamp.fromDate(new Date("2000-01-01T00:00:00Z")),
    });
    const grant = httpsCallable(clientFunctions, "grantBonusAttempt");

    const granted = await grant({activity: "game", adWatched: true});
    const data = granted.data as {
      granted: boolean; bonusAttempts: number; attemptsUsed: number; allowance: number;
    };
    assertEq("a stale day does not read as a spent bonus cap", data.granted, true);
    assertEq("yesterday's bonuses do not count against today",
      data.bonusAttempts, 1);
    assertEq("today starts with nothing used", data.attemptsUsed, 0);
    assertEq("the allowance is a fresh day plus the new bonus",
      data.allowance, MAX_DAILY_GAME_SESSIONS + 1);

    const utcDayOf = (ms: number) => Math.floor(ms / 86_400_000);
    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("the grant clears yesterday's game attempts",
      snap.get("game_attempts"), 0);
    assertEq("...and yesterday's quiz attempts", snap.get("quiz_attempts"), 0);
    assertEq("...and the other activity's stale bonus",
      snap.get("bonus_quiz_attempts"), 0);
    assertEq("...and leaves the granted one at one",
      snap.get("bonus_game_attempts"), 1);
    assertEq("the grant stamps today",
      utcDayOf(snap.get("last_reset_time").toMillis()), utcDayOf(Date.now()));
  }

  // --- enterTournament: paid entry ----------------------------------------
  //
  // The fee is the deployed fallback of 20. The entry window is opened to the
  // whole week, because the default of three days would make every entry test
  // below fail whenever the suite happens to run from Thursday on. The lock
  // itself is covered by the unit tests; this must be written before the first
  // call that reads the config, which caches it for a minute.
  await db.collection("config").doc("tournament").set({entryWindowDays: 7});
  {
    const user = await makeUser("tournament");
    const thisWeek = Math.floor((Math.floor(Date.now() / 86_400_000) + 3) / 7);
    const lastWeek = thisWeek - 1;
    // In last week's tournament, unsettled, with enough stars for one entry.
    await seedUserDoc(user.uid, "TOURNEY1", {
      points: 30, weekKey: lastWeek, weeklyXp: 500, tournamentWeek: lastWeek,
    });
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    const getLeaderboard = httpsCallable(clientFunctions, "getLeaderboard");
    const enterTournament = httpsCallable(clientFunctions, "enterTournament");

    const before = (await getLeaderboard({})).data as {
      entered: boolean; entryFee: number; myXp: number; expectedRank: number;
    };
    assertEq("the board reports the entry fee", before.entryFee, 20);
    const window = before as unknown as {entriesOpen: boolean; entriesCloseAt: number};
    assertEq("a seven-day window is open", window.entriesOpen, true);
    assertEq("...and closes when the week does",
      window.entriesCloseAt, ((thisWeek + 1) * 7 - 3) * 86_400_000);
    const bands = (before as unknown as {prizeBands: Array<{fromRank: number; toRank: number; points: number}>})
      .prizeBands;
    assertEq("the board sends the whole prize table, entrants or not",
      bands, [
        {fromRank: 1, toRank: 1, points: 350},
        {fromRank: 2, toRank: 3, points: 200},
        {fromRank: 4, toRank: 10, points: 100},
        {fromRank: 11, toRank: 30, points: 50},
      ]);
    assertEq("a player who has not paid is not entered", before.entered, false);
    assertEq("...and has no xp this week yet", before.myXp, 0);
    assertEq("...so no rank to offer", before.expectedRank, 0);

    // Everybody's weekly XP accrues. Entry decides who is ranked and paid.
    const unpaidSession = await openGameSession("floppy_bird");
    await backdateSession(user.uid, unpaidSession, 60_000);
    await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 30, sessionId: unpaidSession,
    });
    const unpaidSnap = await db.collection("users").doc(user.uid).get();
    assertEq("a non-entrant's play earns lifetime xp", unpaidSnap.get("xp"), 30);
    assertEq("...and weekly xp", unpaidSnap.get("weeklyXp"), 30);
    assertEq("...on the running week", unpaidSnap.get("weekKey"), thisWeek);
    assertEq("last week, which was entered, is carried for its settlement",
      unpaidSnap.get("lastWeeklyXp"), 500);

    const unpaidBoard = (await getLeaderboard({full: true})).data as {
      entered: boolean; myXp: number; myRank: number; expectedRank: number;
      entries: Array<{isMe: boolean}>;
    };
    assertEq("a non-entrant sees their weekly xp", unpaidBoard.myXp, 30);
    assertEq("...is not ranked", unpaidBoard.myRank, 0);
    assertEq("...is offered the rank entering would give them",
      unpaidBoard.expectedRank > 0, true);
    assertEq("...and is not on the board",
      unpaidBoard.entries.some((entry) => entry.isMe), false);

    await assertThrows(
      "entering without naming the fee is refused",
      () => enterTournament({}),
      "invalid-argument"
    );
    await assertThrows(
      "entering at a fee other than the live one is refused",
      () => enterTournament({expectedFee: 10}),
      "failed-precondition"
    );
    const refusedSnap = await db.collection("users").doc(user.uid).get();
    assertEq("a refused entry charges nothing", refusedSnap.get("points"), 30);

    const entered = (await enterTournament({expectedFee: 20})).data as {
      weekKey: number; feePaid: number; remainingPoints: number;
    };
    assertEq("entry charges the fee", entered.feePaid, 20);
    assertEq("entry reports the balance left", entered.remainingPoints, 10);
    assertEq("entry joins the running week", entered.weekKey, thisWeek);

    const enteredSnap = await db.collection("users").doc(user.uid).get();
    assertEq("the stars are debited", enteredSnap.get("points"), 10);
    assertEq("the player is marked entered for this week",
      enteredSnap.get("tournamentWeek"), thisWeek);
    assertEq("xp already earned this week counts", enteredSnap.get("weeklyXp"), 30);
    assertEq("last week's carried total is untouched",
      enteredSnap.get("lastWeeklyXp"), 500);
    assertEq("entering awards no xp", enteredSnap.get("xp"), 30);

    const entryEvent = await db.collection("users").doc(user.uid)
      .collection("rewardEvents").doc(`tournament:${thisWeek}`).get();
    assertEq("the entry has a ledger entry", entryEvent.exists, true);
    assertEq("...recorded as a tournament entry", entryEvent.get("source"), "TOURNAMENT_ENTRY");
    assertEq("...as a debit", entryEvent.get("finalPoints"), -20);
    assertEq("...that shows in the stars activity", entryEvent.get("affectsPoints"), true);

    await assertThrows(
      "a second entry in the same week is refused",
      () => enterTournament({expectedFee: 20}),
      "failed-precondition"
    );
    const twiceSnap = await db.collection("users").doc(user.uid).get();
    assertEq("the refused second entry charges nothing", twiceSnap.get("points"), 10);

    const paidSession = await openGameSession("floppy_bird");
    await backdateSession(user.uid, paidSession, 60_000);
    await claimReward({
      rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: paidSession,
    });
    const scoredSnap = await db.collection("users").doc(user.uid).get();
    assertEq("an entrant's play keeps adding to the week", scoredSnap.get("weeklyXp"), 72);

    // The board was cached a moment ago without this player, and the cache
    // lives a minute. The caller's own row must not wait for it.
    const after = (await getLeaderboard({full: true})).data as {
      entered: boolean; myXp: number; myRank: number; expectedRank: number;
      entries: Array<{rank: number; xp: number; isMe: boolean}>;
    };
    assertEq("the board now reports the player entered", after.entered, true);
    assertEq("...with their weekly xp", after.myXp, 72);
    assertEq("...and a real rank", after.myRank > 0, true);
    assertEq("...and no hypothetical one", after.expectedRank, 0);
    const myRow = after.entries.find((entry) => entry.isMe);
    assertEq("a new entrant is on the board at once, cache or not", myRow !== undefined, true);
    assertEq("...with their fresh xp", myRow?.xp, 72);
    assertEq("...at the rank the card reports", myRow?.rank, after.myRank);
  }

  // --- enterTournament: entering before playing this week -----------------
  //
  // The board filters on weekKey as well as on entry, so an entrant who has
  // not played yet this week must be moved onto it - or last week's figure
  // would sit under this week's entry.
  {
    const user = await makeUser("tournamentfresh");
    const thisWeek = Math.floor((Math.floor(Date.now() / 86_400_000) + 3) / 7);
    // Played last week without entering it.
    await seedUserDoc(user.uid, "TOURNEY3", {
      points: 20, weekKey: thisWeek - 1, weeklyXp: 800,
    });
    const enterTournament = httpsCallable(clientFunctions, "enterTournament");

    await enterTournament({expectedFee: 20});
    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("entry moves a stale week onto the running one", snap.get("weekKey"), thisWeek);
    assertEq("...at zero, not last week's figure", snap.get("weeklyXp"), 0);
    assertEq("...and an unentered week is not carried", snap.get("lastWeeklyXp"), undefined);
    assertEq("exactly the fee can be spent", snap.get("points"), 0);
  }

  // --- enterTournament: not enough stars ----------------------------------
  {
    const user = await makeUser("tournamentpoor");
    await seedUserDoc(user.uid, "TOURNEY2", {points: 19});
    const enterTournament = httpsCallable(clientFunctions, "enterTournament");

    await assertThrows(
      "a player one star short cannot enter",
      () => enterTournament({expectedFee: 20}),
      "failed-precondition"
    );
    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("a refused entry leaves the balance alone", snap.get("points"), 19);
    assertEq("...and does not enter the player", snap.get("tournamentWeek"), undefined);
  }

  // --- weekly leaderboard settlement --------------------------------------
  //
  // SETTLED INTO A WEEK OF ITS OWN, a long way back, rather than into "last
  // week". Other blocks in this file leave users stamped with real week keys -
  // the double-XP test rewinds one to last week and then claims, which is a
  // legitimate entrant in last week's board - so asserting an exact winner
  // count against the shared fixture set made this test depend on every other
  // test that had ever touched a weekly counter. An isolated week means the
  // four users below ARE the board.
  {
    const carried = await makeUser("boardcarried");
    const winner = await makeUser("boardfirst");
    const runnerUp = await makeUser("boardsecond");
    const idle = await makeUser("boardidle");
    const freeloader = await makeUser("boardfreeloader");

    const thisWeek = Math.floor((Math.floor(Date.now() / 86_400_000) + 3) / 7);
    const settledWeek = thisWeek - 100;

    // THE REPORTED BUG, as a fixture. This player topped the settled week and
    // then played again after the boundary, so their live counters have moved
    // on to the current week and a query for the settled one cannot see them.
    // Before the rollover carried the closing total across, they were paid
    // nothing and the runner-up took first prize.
    //
    // Still marked as entering the settled week, which is also what proves the
    // live half filters on weekKey: without it, their 5 XP from this week
    // would be read as a settled-week score.
    await seedUserDoc(carried.uid, "BOARD0", {
      weekKey: thisWeek,
      weeklyXp: 5,
      tournamentWeek: settledWeek,
      lastWeekKey: settledWeek,
      lastWeeklyXp: 900,
    });
    // Entrants who have not opened the app since the week ended. Their live
    // counters still name the settled week.
    await seedUserDoc(winner.uid, "BOARD1",
      {weekKey: settledWeek, weeklyXp: 400, tournamentWeek: settledWeek});
    await seedUserDoc(runnerUp.uid, "BOARD2",
      {weekKey: settledWeek, weeklyXp: 250, tournamentWeek: settledWeek});
    await seedUserDoc(idle.uid, "BOARD3",
      {weekKey: settledWeek, weeklyXp: 0, tournamentWeek: settledWeek});
    // The top score of the week, from somebody who never paid to enter.
    await seedUserDoc(freeloader.uid, "BOARD5", {weekKey: settledWeek, weeklyXp: 5000});

    await admin.auth().setCustomUserClaims(winner.uid, {admin: true});
    await signInWithEmailAndPassword(clientAuth, winner.email!, "Test1234!");
    await clientAuth.currentUser!.getIdToken(true);

    const settle = httpsCallable(clientFunctions, "settleLeaderboardNow");

    const first = await settle({weekKey: settledWeek});
    const firstData = first.data as {paid: number; pointsPaid: number};
    assertEq("settlement pays every player who scored", firstData.paid, 3);
    assertEq("settlement pays the first three prizes",
      firstData.pointsPaid, 350 + 200 + 200);

    const carriedSnap = await db.collection("users").doc(carried.uid).get();
    assertEq("a player who played after the rollover still takes first prize",
      carriedSnap.get("points"), 350);
    const winnerSnap = await db.collection("users").doc(winner.uid).get();
    assertEq("the live board is paid behind them", winnerSnap.get("points"), 200);
    const idleSnap = await db.collection("users").doc(idle.uid).get();
    assertEq("a player with no XP is not paid", idleSnap.get("points") || 0, 0);
    const freeloaderSnap = await db.collection("users").doc(freeloader.uid).get();
    assertEq("the week's top score is not paid without an entry",
      freeloaderSnap.get("points") || 0, 0);

    // The prize is announced, not just credited - see FIELD_LAST_LEADERBOARD_PRIZE.
    const prize = carriedSnap.get("lastLeaderboardPrize");
    assertEq("the win is recorded for the app to announce", prize?.rank, 1);
    assertEq("...with the XP that earned it", prize?.weeklyXp, 900);
    assertEq("...and the week it was won in", prize?.weekKey, settledWeek);

    // The guarantee that matters: a scheduler retry must not pay again.
    const second = await settle({weekKey: settledWeek});
    const secondData = second.data as {alreadySettled: boolean; pointsPaid: number};
    assertEq("a re-run reports the week already settled",
      secondData.alreadySettled, true);
    assertEq("a re-run pays nothing further", secondData.pointsPaid, 0);

    const carriedAfter = await db.collection("users").doc(carried.uid).get();
    assertEq("first place is still credited exactly once",
      carriedAfter.get("points"), 350);

    const ledger = await db.collection("users").doc(carried.uid)
      .collection("rewardEvents").doc(`leaderboard:${settledWeek}`).get();
    assertEq("the prize has a ledger entry", ledger.exists, true);
    assertEq("the ledger records the rank", ledger.get("metadata").rank, 1);

    // The board was frozen before a penny moved, so a retry pays the ranking
    // the first attempt decided rather than re-reading a moved collection.
    const marker = await db.collection("leaderboardSettlements")
      .doc(String(settledWeek)).get();
    assertEq("the settlement is marked complete", marker.get("status"), "complete");
    assertEq("...and records the board it paid",
      (marker.get("entries") as unknown[]).length, 3);

    // A week still in progress must not be settled early.
    await assertThrows(
      "settling the current week is refused",
      () => settle({weekKey: thisWeek}),
      "failed-precondition"
    );
  }

  // --- tie order: the live query and the settlement agree -----------------
  //
  // compareStanding claims Firestore breaks equal weeklyXp on document id
  // DESCENDING. Checked against the real query shape here, and then through
  // a settlement, because a tie across a band edge is where the two orders
  // disagreeing would pay the wrong player.
  {
    const thisWeek = Math.floor((Math.floor(Date.now() / 86_400_000) + 3) / 7);
    const tieWeek = thisWeek - 200;
    const first = await makeUser("tiefirst");
    const second = await makeUser("tiesecond");
    for (const [user, code] of [[first, "TIE1"], [second, "TIE2"]] as const) {
      await seedUserDoc(user.uid, code,
        {weekKey: tieWeek, weeklyXp: 500, tournamentWeek: tieWeek});
    }

    const live = await db.collection("users")
      .where("tournamentWeek", "==", tieWeek)
      .where("weekKey", "==", tieWeek)
      .orderBy("weeklyXp", "desc")
      .get();
    const liveOrder = live.docs.map((doc) => doc.id);
    const expected = [first.uid, second.uid].sort((a, b) => (a < b ? 1 : -1));
    assertEq("Firestore orders a tie by uid descending", liveOrder, expected);

    await admin.auth().setCustomUserClaims(first.uid, {admin: true});
    await signInWithEmailAndPassword(clientAuth, first.email!, "Test1234!");
    await clientAuth.currentUser!.getIdToken(true);
    await httpsCallable(clientFunctions, "settleLeaderboardNow")({weekKey: tieWeek});

    const topSnap = await db.collection("users").doc(liveOrder[0]).get();
    const nextSnap = await db.collection("users").doc(liveOrder[1]).get();
    assertEq("settlement pays first prize to the player the board lists first",
      topSnap.get("points"), 350);
    assertEq("...and second prize to the one below", nextSnap.get("points"), 200);
  }

  // --- settlement is admin-only -------------------------------------------
  {
    const user = await makeUser("boardnonadmin");
    await seedUserDoc(user.uid, "BOARD4");
    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");
    const settle = httpsCallable(clientFunctions, "settleLeaderboardNow");
    await assertThrows(
      "a non-admin cannot settle the leaderboard",
      () => settle({}),
      "permission-denied"
    );
  }

  console.log(`\n=== ${passed} passed, ${failed} failed ===`);
  await deleteApp(clientApp);
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((e) => {
  console.error("Smoke test crashed:", e);
  process.exit(1);
});
