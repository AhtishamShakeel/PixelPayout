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
  signOut,
  type User,
} from "firebase/auth";
import {getFunctions, connectFunctionsEmulator, httpsCallable} from "firebase/functions";

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
    referralRewardClaimed: false,
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

    const events = await getLedgerEvents(user.uid);
    assertEq("ledger records the level before the event", events[0].levelAtEvent, 1);
    assertEq("ledger records the level after the event", events[0].levelAfterEvent, 2);
  }

  // --- reaching a milestone level pays a one-time Points bonus ---
  {
    const user = await makeUser("milestone");
    // Level 5 needs 261 xp; seed just below so one correct answer crosses it.
    await seedUserDoc(user.uid, "MILESTONE", {xp: 255, level: 4});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const data = res.data as {level: number; milestonePoints: number; milestoneLevels: number[]};
    assertEq("crossing into level 5 reports the milestone", data.milestoneLevels, [5]);
    assertEq("milestone bonus points reported", data.milestonePoints, 25);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("milestone reached level 5", snap.get("level"), 5);
    // The quiz itself awards no points - every point here is the milestone.
    assertEq("milestone bonus credited to the points balance", snap.get("points"), 25);

    const events = await getLedgerEvents(user.uid);
    const milestoneEvent = events.find((e) => e.source === "LEVEL_UP");
    assertEq("a LEVEL_UP ledger entry was written", milestoneEvent !== undefined, true);
    assertEq("milestone ledger id is keyed by level", milestoneEvent?.id, "levelup:5");
    assertEq("milestone ledger records the points", milestoneEvent?.finalPoints, 25);
    assertEq("milestone ledger awards no xp", milestoneEvent?.xpAwarded, 0);

    // Earning more XP at the same level must not pay the milestone again.
    await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const afterSnap = await db.collection("users").doc(user.uid).get();
    assertEq("milestone does not pay again on further xp", afterSnap.get("points"), 25);
    assertEq(
      "still exactly one LEVEL_UP ledger entry",
      (await getLedgerEvents(user.uid)).filter((e) => e.source === "LEVEL_UP").length,
      1
    );
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

  // --- a non-milestone level-up pays no Points ---
  {
    const user = await makeUser("nomilestone");
    await seedUserDoc(user.uid, "NOMILESTONE", {xp: 45, level: 1});
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    const res = await claimReward({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });
    const data = res.data as {level: number; milestonePoints: number};
    assertEq("levelled up to 2", data.level, 2);
    assertEq("level 2 is not a milestone, so no bonus", data.milestonePoints, 0);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("no points from a non-milestone level-up", snap.get("points"), 0);
    assertEq(
      "no LEVEL_UP ledger entry for a non-milestone level",
      (await getLedgerEvents(user.uid)).filter((e) => e.source === "LEVEL_UP").length,
      0
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

  // --- referral: the referrer is paid even when the crossing was invisible -
  // The bug this covers: readReferrerForUnlock used to fire only on the exact
  // transition past REFERRAL_UNLOCK_XP. submitReferral awards the referee 25
  // XP without running that check, so a referee who entered a code between 75
  // and 99 XP crossed the threshold there - and every later claim saw "already
  // above" and paid nobody. The referee kept their bonus, the referrer got
  // nothing, permanently.
  {
    const referrer = await makeUser("refpayee");
    await seedUserDoc(referrer.uid, "PAYME1", {points: 0, xp: 0, level: 1});

    // A referee sitting just under the threshold, so the referral's own XP
    // carries them over it.
    const referee = await makeUser("refcrosser");
    await seedUserDoc(referee.uid, "CROSS1", {points: 0, xp: 90, level: 2});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "PAYME1"});

    const refereeAfterSubmit = await db.collection("users").doc(referee.uid).get();
    assertEq("the referee is paid immediately", refereeAfterSubmit.get("points"), 50);
    assertEq(
      "and the referral's own xp carried them past the threshold",
      refereeAfterSubmit.get("xp") >= 100,
      true
    );
    assertEq(
      "the referrer is not paid at submit time",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );

    // Any later XP-awarding claim should now settle it.
    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    const paid = await db.collection("users").doc(referrer.uid).get();
    assertEq("the referrer is paid on the next claim", paid.get("points"), 100);
    assertEq("and gets the referrer xp too", paid.get("xp"), 50);
    assertEq(
      "the referee is flagged so it cannot pay twice",
      (await db.collection("users").doc(referee.uid).get()).get("referralRewardClaimed"),
      true
    );

    // A second claim must not pay again.
    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 2, selectedAnswer: 2,
    });
    assertEq(
      "a later claim does not pay the referrer twice",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      100
    );
  }

  // --- referral: a referee already well past the threshold still pays out ---
  {
    const referrer = await makeUser("reflate");
    await seedUserDoc(referrer.uid, "LATE01", {points: 0, xp: 0, level: 1});

    const referee = await makeUser("reflatecode");
    await seedUserDoc(referee.uid, "LATE02", {points: 0, xp: 500, level: 6});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "LATE01"});
    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    assertEq(
      "entering a code long after passing the threshold still pays the referrer",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      100
    );
  }

  // --- referral: below the threshold, nothing is owed yet ---
  {
    const referrer = await makeUser("refearly");
    await seedUserDoc(referrer.uid, "EARLY1", {points: 0, xp: 0, level: 1});

    const referee = await makeUser("refearlycode");
    await seedUserDoc(referee.uid, "EARLY2", {points: 0, xp: 0, level: 1});

    await httpsCallable(clientFunctions, "submitReferral")({referralCode: "EARLY1"});
    await httpsCallable(clientFunctions, "claimReward")({
      rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
    });

    // Referee is on 25 + 10 = 35 XP, well short of 100.
    assertEq(
      "a referee under the threshold pays the referrer nothing",
      (await db.collection("users").doc(referrer.uid).get()).get("points"),
      0
    );
    assertEq(
      "and is not flagged as settled",
      (await db.collection("users").doc(referee.uid).get()).get("referralRewardClaimed"),
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

  // --- redemption: one game UID belongs to one account, forever ---
  {
    const intruder = await makeUser("uidthief");
    await seedUserDoc(intruder.uid, "THIEF", {points: 99999, level: 50});
    const redeem = httpsCallable(clientFunctions, "redeemReward");

    // 5218840977 was claimed by the "redeem" user above.
    await assertThrows(
      "a player id already linked to another account is refused",
      () => redeem({
        optionId: "pubg", packId: "uc_1000", playerId: "5218840977", server: "Global",
      }),
      "failed-precondition"
    );
    const snap = await db.collection("users").doc(intruder.uid).get();
    assertEq("a refused link never debits the balance", snap.get("points"), 99999);

    // Their own, unclaimed id goes through - the rule is one ID per account,
    // not one account per game.
    const ok = await redeem({
      optionId: "pubg", packId: "uc_1000", playerId: "7000000001", server: "Global",
    });
    assertEq(
      "an unclaimed id is accepted",
      (ok.data as {pointsSpent: number}).pointsSpent,
      1000
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

    // Below the level gate it is refused outright.
    const junior = await makeUser("firstjunior");
    await seedUserDoc(junior.uid, "JUNIOR", {points: 5000, level: 1});
    const juniorRedeem = httpsCallable(clientFunctions, "redeemReward");
    await assertThrows(
      "the discount is gated on level",
      () => juniorRedeem({
        optionId: "pubg", packId: "uc_small", playerId: "9000000001",
        server: "Global", useFirstRedeem: true,
      }),
      "failed-precondition"
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

    // The claim on the player id is deliberately kept: a rejection says the
    // order was not fulfilled, not that the account never used that id.
    const link = await db.collection("playerLinks").doc("pubg__5400000001").get();
    assertEq("the player id stays linked after a rejection", link.exists, true);

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
    await seedUserDoc(user.uid, "QUIZREF2", {quiz_attempts: 10});
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
    const res = await claimReward({rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: session1});
    assertEq("floppy_bird score 42 -> 30 xp (per-session cap)", (res.data as {xpAwarded: number}).xpAwarded, 30);
    assertEq("games award no points (XP-only source)", (res.data as {pointsAwarded: number}).pointsAwarded, 0);

    const session2 = await openGameSession("game_2048");
    await backdateSession(user.uid, session2, 60_000);
    const res2 = await claimReward({rewardType: "game", gameId: "game_2048", score: 555, sessionId: session2});
    assertEq("game_2048 score 555 -> floor(555/20)=27 xp", (res2.data as {xpAwarded: number}).xpAwarded, 27);

    await assertThrows(
      "replaying a consumed session is rejected",
      () => claimReward({rewardType: "game", gameId: "floppy_bird", score: 42, sessionId: session1}),
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
    assertEq("ledger has exactly 2 GAME events (rejections wrote nothing)", events.length, 2);
    const floppyEvent = events.find((e) => (e.metadata as any)?.gameId === "floppy_bird");
    assertEq("floppy_bird ledger entry xpAwarded 30", floppyEvent?.xpAwarded, 30);
    assertEq("floppy_bird ledger entry basePoints 0", floppyEvent?.basePoints, 0);
    assertEq("floppy_bird ledger entry metadata.score 42", (floppyEvent?.metadata as any)?.score, 42);
    assertEq("game ledger event id is derived from the session", floppyEvent?.id, `game:${session1}`);
    assertEq("floppy_bird ledger entry not multiplier eligible", floppyEvent?.multiplierEligible, false);
  }

  // --- game: implausible results are rejected ---
  {
    const user = await makeUser("gamecheat");
    await seedUserDoc(user.uid, "GAMECHEAT");
    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // The headline attack: open a session, immediately claim a huge score.
    const instant = await openGameSession("game_2048");
    await assertThrows(
      "claiming instantly (under the minimum session length) is rejected",
      () => claimReward({rewardType: "game", gameId: "game_2048", score: 999_999, sessionId: instant}),
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
      () => claimReward({rewardType: "game", gameId: "game_2048", score: 50, sessionId: mismatch}),
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
    const events = await getLedgerEvents(user.uid);
    assertEq("no ledger entries from rejected game claims", events.length, 0);
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
    assertEq("referee got +50 points", refereeSnap.get("points"), 50);
    assertEq("referee also got referral xp", refereeSnap.get("xp"), 25);
    assertEq("referee marked hasUsedReferral", refereeSnap.get("hasUsedReferral"), true);

    const refEvents = await getLedgerEvents(referee.uid);
    assertEq("referee ledger has exactly 1 REFERRAL_REFEREE event", refEvents.length, 1);
    assertEq("referee ledger event id is deterministic", refEvents[0].id, `referral_referee:${referee.uid}`);
    assertEq("referee ledger basePoints 50", refEvents[0].basePoints, 50);
    assertEq("referee ledger metadata.referrerId correct", (refEvents[0].metadata as any)?.referrerId, referrer.uid);

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

  // --- referrer payout now unlocks on the referee's XP, not their points ---
  {
    const referrer = await makeUser("referrer2");
    await seedUserDoc(referrer.uid, "BOOST100", {points: 0});

    const referee = await makeUser("referee3");
    await seedUserDoc(referee.uid, "REFCODE3");
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    await submitReferral({referralCode: "BOOST100"}); // referee: 25 xp, referredBy set

    const claimReward = httpsCallable(clientFunctions, "claimReward");

    // Two capped game sessions: 25 + 30 = 55 xp, still short of the 100 threshold.
    for (const _ of [1, 2]) {
      const s = await openGameSession("floppy_bird");
      await backdateSession(referee.uid, s, 60_000);
      await claimReward({rewardType: "game", gameId: "floppy_bird", score: 60, sessionId: s});
    }

    let referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer is NOT paid before the referee crosses the xp threshold", referrerSnap.get("points"), 0);

    // One more session takes the referee to 115 xp, past the threshold.
    const finalSession = await openGameSession("floppy_bird");
    await backdateSession(referee.uid, finalSession, 60_000);
    await claimReward({rewardType: "game", gameId: "floppy_bird", score: 60, sessionId: finalSession});

    // The payout is now part of the same transaction, so it is visible
    // immediately - no polling for an async trigger.
    referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer got +100 points once referee crossed the xp threshold", referrerSnap.get("points"), 100);
    assertEq("referrer also got referral xp", referrerSnap.get("xp"), 50);

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("referee crossed the xp threshold", refereeSnap.get("xp") >= 100, true);
    assertEq("referee marked referralRewardClaimed", refereeSnap.get("referralRewardClaimed"), true);

    const referrerEvents = await getLedgerEvents(referrer.uid);
    assertEq("referrer ledger has exactly 1 REFERRAL_REFERRER event", referrerEvents.length, 1);
    assertEq("referrer ledger event id is deterministic", referrerEvents[0].id, `referral_referrer:${referee.uid}`);
    assertEq("referrer ledger basePoints 100", referrerEvents[0].basePoints, 100);
    assertEq("referrer ledger xpAwarded 50", referrerEvents[0].xpAwarded, 50);

    // Earning more XP must not pay the referrer a second time.
    const extraSession = await openGameSession("floppy_bird");
    await backdateSession(referee.uid, extraSession, 60_000);
    await claimReward({rewardType: "game", gameId: "floppy_bird", score: 60, sessionId: extraSession});

    referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("referrer is not paid twice on further xp gains", referrerSnap.get("points"), 100);
    assertEq(
      "referrer still has exactly 1 referral ledger entry",
      (await getLedgerEvents(referrer.uid)).length,
      1
    );
  }

  // --- a referee who never earns Points still unlocks their referrer ---
  {
    // This is the scenario the old points-threshold trigger would have broken:
    // quizzes/games award no Points, so a quiz-only user's points balance
    // never moves past the 50 from the referral itself.
    const referrer = await makeUser("referrer4");
    await seedUserDoc(referrer.uid, "QUIZONLY");

    const referee = await makeUser("referee5");
    await seedUserDoc(referee.uid, "REFCODE5");
    await signInWithEmailAndPassword(clientAuth, referee.email!, "Test1234!");

    const submitReferral = httpsCallable(clientFunctions, "submitReferral");
    await submitReferral({referralCode: "QUIZONLY"}); // 25 xp

    // 8 correct quiz answers = 80 xp -> 105 total, past the threshold.
    const claimReward = httpsCallable(clientFunctions, "claimReward");
    for (let i = 0; i < 8; i++) {
      await claimReward({
        rewardType: "quiz", category: "Animals", quizId: "1", questionIndex: 0, selectedAnswer: 1,
      });
    }

    const refereeSnap = await db.collection("users").doc(referee.uid).get();
    assertEq("quiz-only referee earned no points beyond the referral itself", refereeSnap.get("points"), 50);

    const referrerSnap = await db.collection("users").doc(referrer.uid).get();
    assertEq("quiz-only engagement still pays the referrer", referrerSnap.get("points"), 100);
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
    assertEq("concurrent double-submit: referee only credited once", refereeSnap.get("points"), 50);

    const events = await getLedgerEvents(referee.uid);
    assertEq("concurrent double-submit: exactly one ledger entry", events.length, 1);
  }

  // --- checkAndResetQuizAttempts: stale last_reset_time triggers a reset ---
  {
    const user = await makeUser("resetstale");
    await seedUserDoc(user.uid, "RESETREF1", {
      quiz_attempts: 7,
      last_reset_time: Timestamp.fromDate(new Date("2000-01-01T00:00:00Z")),
    });
    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");
    const checkAndReset = httpsCallable(clientFunctions, "checkAndResetQuizAttempts");

    const res = await checkAndReset({});
    const data = res.data as {resetPerformed: boolean; attempts: number};
    assertEq("stale last_reset_time -> resetPerformed true", data.resetPerformed, true);
    assertEq("stale last_reset_time -> attempts reset to 0", data.attempts, 0);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("quiz_attempts persisted as 0 after reset", snap.get("quiz_attempts"), 0);
  }

  // --- checkAndResetQuizAttempts: same-day last_reset_time does not reset ---
  {
    const user = await makeUser("resetfresh");
    await seedUserDoc(user.uid, "RESETREF2", {
      quiz_attempts: 4,
      last_reset_time: Timestamp.now(),
    });
    await signInWithEmailAndPassword(clientAuth, user.email!, "Test1234!");
    const checkAndReset = httpsCallable(clientFunctions, "checkAndResetQuizAttempts");

    const res = await checkAndReset({});
    const data = res.data as {resetPerformed: boolean; attempts: number};
    assertEq("fresh last_reset_time -> resetPerformed false", data.resetPerformed, false);
    assertEq("fresh last_reset_time -> attempts unchanged", data.attempts, 4);

    const snap = await db.collection("users").doc(user.uid).get();
    assertEq("quiz_attempts untouched in Firestore", snap.get("quiz_attempts"), 4);
  }

  // --- checkAndResetQuizAttempts: unauthenticated call rejected ---
  {
    await signOut(clientAuth);
    const checkAndReset = httpsCallable(clientFunctions, "checkAndResetQuizAttempts");
    await assertThrows(
      "unauthenticated checkAndResetQuizAttempts rejected",
      () => checkAndReset({}),
      "unauthenticated"
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
