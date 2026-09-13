/* PixelPayout security audit - emulator proof-of-concept suite.
 * READ-ONLY with respect to the repo: lives in the scratchpad, imports the
 * already-built functions/lib and functions/node_modules. Run ONLY via
 * `firebase emulators:exec --project demo-pixelpayout-audit ...` so every call
 * lands on local emulators.
 */
const {createRequire} = require("module");
const path = require("path");
const crypto = require("crypto");
const FN = "D:/android studio projects/PixelPayout/functions";
const req = createRequire(path.join(FN, "package.json"));

const PROJECT = process.env.GCLOUD_PROJECT || "demo-pixelpayout-audit";
if (!PROJECT.startsWith("demo-")) { console.error("Refusing: not a demo project"); process.exit(2); }
if (!process.env.FIRESTORE_EMULATOR_HOST) { console.error("Refusing: no emulator"); process.exit(2); }

const admin = req("firebase-admin");
const {Timestamp} = req("firebase-admin/firestore");
const {initializeApp} = req("firebase/app");
const {getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut} = req("firebase/auth");
const {getFunctions, connectFunctionsEmulator, httpsCallable} = req("firebase/functions");
const {getFirestore, connectFirestoreEmulator, doc, getDoc, setDoc, updateDoc} = req("firebase/firestore");
const {levelForXp} = req(path.join(FN, "lib/economy/levelCurve.js"));
const {mergeSettlementBoard, buildSettlement} = req(path.join(FN, "lib/economy/leaderboard.js"));

admin.initializeApp({projectId: PROJECT});
const db = admin.firestore();
const FN_BASE = `http://127.0.0.1:5001/${PROJECT}/us-central1`;

const results = [];
function record(id, verdict, detail) {
  results.push({id, verdict, detail});
  console.log(`[${verdict}] ${id} :: ${typeof detail === "string" ? detail : JSON.stringify(detail)}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let appSeq = 0;

async function session(prefix) {
  const app = initializeApp({projectId: PROJECT, apiKey: "fake-api-key"}, `a${appSeq++}`);
  const auth = getAuth(app);
  connectAuthEmulator(auth, "http://127.0.0.1:9099", {disableWarnings: true});
  const fns = getFunctions(app);
  connectFunctionsEmulator(fns, "127.0.0.1", 5001);
  const fs = getFirestore(app);
  connectFirestoreEmulator(fs, "127.0.0.1", 8080);
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@audit.local`;
  const cred = await createUserWithEmailAndPassword(auth, email, "Audit1234!");
  const call = (name, data) => httpsCallable(fns, name)(data).then((r) => r.data);
  return {app, auth, fns, fs, call, user: cred.user, uid: cred.user.uid, email};
}
const userDoc = async (uid) => (await db.collection("users").doc(uid).get()).data() || {};

async function retrying(fn, tries = 6) {
  let last;
  for (let i = 0; i < tries; i++) {
    try { return await fn(); } catch (e) {
      last = e;
      const code = String(e.code || "");
      if (!/aborted|internal|unavailable|deadline/i.test(code)) throw e;
      await sleep(150 * (i + 1));
    }
  }
  throw last;
}

async function seed() {
  await db.collection("config").doc("quizAnswerKey").set({
    version: 1,
    answers: {"Audit:1": [2], "Audit:2": [0], "Audit:3": [1]},
    syncedAt: Date.now(),
  });
  await db.collection("redemptionOptions").doc("audit_game").set({
    name: "Audit Game", code: "AG", currencyName: "Coins", enabled: true,
    idMinLength: 4, requiresUsername: false,
    packs: {
      taster: {amount: "30 Coins", pointsCost: 600, firstRedeemCost: 150, firstRedeemOnly: true},
      full: {amount: "60 Coins", pointsCost: 1200},
    },
  });
  await db.collection("serverConfig").doc("offerwall").set({
    test: {
      enabled: true, secret: "audit-secret-123", scheme: "md5",
      signatureTemplate: "{uid}:{tx}:{amt}:{secret}",
      paramNames: {uid: "uid", transactionId: "tx", amount: "amt", signature: "sig", status: "status"},
      chargebackValues: ["reversed"], successBody: "1",
    },
    iptest: {
      enabled: true, secret: "ip-secret", scheme: "md5",
      signatureTemplate: "{uid}:{tx}:{amt}:{secret}",
      paramNames: {uid: "uid", transactionId: "tx", amount: "amt", signature: "sig"},
      allowedIps: ["203.0.113.7"], successBody: "1",
    },
  });
}

// ---------------------------------------------------------------------------
async function botFarmDay() {
  const main = await session("main");
  await main.call("completeSignup", {displayName: "Main Account", androidId: "real-device-aaaa"});
  const mainCode = (await userDoc(main.uid)).referralCode;

  // Same real device -> flagged. Random id -> clean.
  const sameDevice = await session("samedev");
  await sameDevice.call("completeSignup", {displayName: "Alt", androidId: "real-device-aaaa"});
  const spoofed = await session("bot");
  const botDevice = crypto.randomBytes(8).toString("hex");
  await spoofed.call("completeSignup", {displayName: "Bot One", androidId: botDevice});
  record("DEVICE-CHECK-SPOOF", (await userDoc(spoofed.uid)).hasUsedReferral === false &&
    (await userDoc(sameDevice.uid)).hasUsedReferral === true ? "VULNERABLE" : "PROTECTED",
  {sameDeviceFlagged: (await userDoc(sameDevice.uid)).hasUsedReferral,
    randomDeviceFlagged: (await userDoc(spoofed.uid)).hasUsedReferral,
    emailVerified: spoofed.user.emailVerified});

  const bot = spoofed;
  const t0 = Date.now();
  const ref = await bot.call("submitReferral", {referralCode: mainCode});

  // Answer key straight from Firestore with the bot's own client credentials.
  let keyReadable = false; let key = null;
  try { const s = await getDoc(doc(bot.fs, "config", "quizAnswerKey")); keyReadable = s.exists(); key = s.data(); } catch (e) { /* denied */ }
  record("QUIZ-ANSWER-KEY-CLIENT-READABLE", keyReadable ? "VULNERABLE" : "PROTECTED",
    keyReadable ? `client read ${Object.keys(key.answers).length} answer rows` : "denied");

  // Bonus attempts with no ad at all.
  for (const activity of ["game", "game", "game", "quiz", "quiz", "quiz"]) {
    await retrying(() => bot.call("grantBonusAttempt", {activity, adWatched: true}));
  }

  // 13 game sessions opened at once, claimed together after the 15s floor.
  const sessions = await Promise.all(Array.from({length: 13}, () =>
    retrying(() => bot.call("startGameSession", {gameId: "tower_game"})).then((r) => r.sessionId)));
  await sleep(16_000);
  const gameClaims = await Promise.allSettled(sessions.map((sessionId) =>
    bot.call("claimReward", {rewardType: "game", gameId: "tower_game", score: 1500, sessionId})));
  const eventIds = [];
  let gameXp = 0; let gameFailed = [];
  gameClaims.forEach((r, i) => {
    if (r.status === "fulfilled") { gameXp += r.value.xpAwarded; eventIds.push(r.value.eventId); } else gameFailed.push(i);
  });
  for (const i of gameFailed) { // what an attacker does with contention failures
    try {
      const r = await retrying(() => bot.call("claimReward", {rewardType: "game", gameId: "tower_game", score: 1500, sessionId: sessions[i]}));
      gameXp += r.xpAwarded; eventIds.push(r.eventId);
    } catch (e) { /* cap or burned */ }
  }

  // 13 quiz answers, all correct from the stolen key.
  let quizXp = 0;
  for (let i = 0; i < 13; i++) {
    const r = await retrying(() => bot.call("claimReward", {rewardType: "quiz", category: "Audit", quizId: "1", questionIndex: 0, selectedAnswer: key ? key.answers["Audit:1"][0] : 2}));
    quizXp += r.xpAwarded; eventIds.push(r.eventId);
  }
  // Double every entry, no ads.
  let doubleXp = 0;
  for (const eventId of eventIds) {
    try { doubleXp += (await retrying(() => bot.call("claimDoubleXp", {eventId}))).xpAwarded; } catch (e) { /* ignore */ }
  }
  const streak = await retrying(() => bot.call("claimDailyStreak", {adWatched: true}));
  const goals = await retrying(() => bot.call("claimDailyGoalBonus", {adWatched: true}));
  let levelStars = 0; let levelClaims = 0;
  for (;;) {
    const r = await retrying(() => bot.call("claimLevelReward", {}));
    if (!r.claimed) break;
    levelStars += r.pointsAwarded; levelClaims++;
  }
  const afterFarm = await userDoc(bot.uid);
  let firstRedeem = null;
  try {
    firstRedeem = await bot.call("redeemReward", {optionId: "audit_game", packId: "taster", playerId: `fresh${Date.now()}`, useFirstRedeem: true});
  } catch (e) { firstRedeem = {error: e.message}; }
  const mainAfter = await userDoc(main.uid);
  const seconds = Math.round((Date.now() - t0) / 1000);

  record("BOT-FARM-ONE-DAY", "VULNERABLE", {
    wallClockSeconds: seconds, referral: ref.status,
    gameXp, quizXp, doubleXp, streakRewarded: streak.rewarded, goalBonus: goals.pointsAwarded,
    xp: afterFarm.xp, level: levelForXp(afterFarm.xp), levelRewardsClaimedWithoutAds: levelClaims,
    starsFromLevels: levelStars, starsBeforeRedeem: afterFarm.points,
    firstRedeemOrder: firstRedeem, referrerStarsFromThisBot: mainAfter.points || 0,
    bonusAttemptsGranted: afterFarm.bonusAttemptsGranted, adlessStreakClaims: afterFarm.adlessStreakClaims || 0,
  });
  return {main, bot};
}

// ---------------------------------------------------------------------------
async function referralRedeemClawback() {
  const referrer = await session("ref");
  await referrer.call("completeSignup", {displayName: "Referrer", androidId: "r1"});
  const code = (await userDoc(referrer.uid)).referralCode;
  const referee = await session("referee");
  await referee.call("completeSignup", {displayName: "Referee", androidId: crypto.randomUUID()});
  await referee.call("submitReferral", {referralCode: code});
  await db.collection("users").doc(referee.uid).update({points: 1200});
  const order = await referee.call("redeemReward", {optionId: "audit_game", packId: "full", playerId: "junk0000"});
  const paidAtOrder = (await userDoc(referrer.uid)).points || 0;

  const adm = await session("admin");
  await adm.call("completeSignup", {displayName: "Admin", androidId: "adm"});
  await admin.auth().setCustomUserClaims(adm.uid, {admin: true});
  await adm.user.getIdToken(true);
  await adm.call("resolveRedemption", {redemptionId: order.redemptionId, status: "rejected", reason: "invalid id"});
  const refereeAfter = await userDoc(referee.uid);
  const referrerAfter = await userDoc(referrer.uid);
  record("REFERRAL-REDEEM-BONUS-NOT-CLAWED-BACK", referrerAfter.points > 0 && refereeAfter.points === 1200 ? "VULNERABLE" : "PROTECTED",
    {referrerPaidAtOrderCreation: paidAtOrder, refereeRefundedTo: refereeAfter.points, referrerKeeps: referrerAfter.points});
  return adm;
}

// ---------------------------------------------------------------------------
async function redeemNotIdempotent() {
  const u = await session("dup");
  await u.call("completeSignup", {displayName: "Dup Redeemer", androidId: crypto.randomUUID()});
  await db.collection("users").doc(u.uid).update({points: 2400});
  const payload = {optionId: "audit_game", packId: "full", playerId: "player1234"};
  const a = await u.call("redeemReward", payload);
  const b = await u.call("redeemReward", payload); // the retry a lost response causes
  const orders = await db.collection("redemptions").where("uid", "==", u.uid).get();
  record("REDEEM-NO-IDEMPOTENCY-KEY", orders.size === 2 ? "VULNERABLE" : "PROTECTED",
    {identicalRequests: 2, ordersCreated: orders.size, pointsLeft: (await userDoc(u.uid)).points, ids: [a.redemptionId, b.redemptionId]});
}

// ---------------------------------------------------------------------------
async function postback(network, params, headers = {}) {
  const qs = new URLSearchParams({network, ...params}).toString();
  const res = await fetch(`${FN_BASE}/offerwallCallback?${qs}`, {headers});
  return {status: res.status, body: await res.text()};
}
const md5 = (s) => crypto.createHash("md5").update(s).digest("hex");

async function offerwall() {
  const u = await session("ow");
  await u.call("completeSignup", {displayName: "Offerwall User", androidId: crypto.randomUUID()});
  const tx = `tx-${Date.now()}`;
  const credit = await postback("test", {uid: u.uid, tx, amt: "100", sig: md5(`${u.uid}:${tx}:100:audit-secret-123`)});
  const afterCredit = (await userDoc(u.uid)).points;
  const reversal = await postback("test", {uid: u.uid, tx, amt: "100", status: "reversed", sig: md5(`${u.uid}:${tx}:100:audit-secret-123`)});
  const negative = await postback("test", {uid: u.uid, tx, amt: "-100", sig: md5(`${u.uid}:${tx}:-100:audit-secret-123`)});
  const afterReversal = (await userDoc(u.uid)).points;
  record("OFFERWALL-CHARGEBACK-SAME-TXID-SWALLOWED", afterReversal === afterCredit && afterCredit === 100 ? "VULNERABLE" : "PROTECTED",
    {credit, afterCredit, statusFlagReversal: reversal, negativeAmountReversal: negative, balanceAfterBothReversals: afterReversal});

  // Signing oracle in logs: forged request, then compare with emulator log output.
  const forgedTx = `forged-${Date.now()}`;
  const forged = await postback("test", {uid: u.uid, tx: forgedTx, amt: "20000", sig: "00000000000000000000000000000000"});
  const validForForged = md5(`${u.uid}:${forgedTx}:20000:audit-secret-123`);
  console.log(`AUDIT_EXPECTED_SIG_FOR_FORGED=${validForForged}`);
  await sleep(1500);
  const replay = await postback("test", {uid: u.uid, tx: forgedTx, amt: "20000", sig: validForForged});
  record("OFFERWALL-DEBUG-LOG-SIGNING-ORACLE", "SEE-LOG-GREP",
    {forgedStatus: forged.status, validSigForForgedParams: validForForged,
      replayWithThatSig: replay, balanceAfterReplay: (await userDoc(u.uid)).points,
      note: "grep emulator output for this sig inside 'Offerwall signature debug'"});

  // IP allowlist via X-Forwarded-For.
  const ipTx = `ip-${Date.now()}`;
  const ipSig = md5(`${u.uid}:${ipTx}:5:ip-secret`);
  const noHeader = await postback("iptest", {uid: u.uid, tx: ipTx + "a", amt: "5", sig: md5(`${u.uid}:${ipTx}a:5:ip-secret`)});
  const spoofed = await postback("iptest", {uid: u.uid, tx: ipTx, amt: "5", sig: ipSig}, {"X-Forwarded-For": "203.0.113.7, 198.51.100.99"});
  record("OFFERWALL-IP-ALLOWLIST-XFF-SPOOF", spoofed.status === 200 && noHeader.status === 403 ? "VULNERABLE" : "PROTECTED",
    {withoutHeader: noHeader, withSpoofedLeftmostXff: spoofed});
}

// ---------------------------------------------------------------------------
async function unauthAndSpam() {
  const known = await session("known");
  const anonApp = initializeApp({projectId: PROJECT, apiKey: "fake-api-key"}, `anon${appSeq++}`);
  const anonFns = getFunctions(anonApp); connectFunctionsEmulator(anonFns, "127.0.0.1", 5001);
  const hit = await httpsCallable(anonFns, "checkEmailExists")({email: known.email}).then((r) => r.data);
  const miss = await httpsCallable(anonFns, "checkEmailExists")({email: "nobody-here@audit.local"}).then((r) => r.data);
  let malformed; try { malformed = await httpsCallable(anonFns, "checkEmailExists")({}); } catch (e) { malformed = {code: e.code, message: e.message}; }
  record("EMAIL-ENUMERATION-UNAUTHENTICATED", hit.exists === true && miss.exists === false ? "VULNERABLE" : "PROTECTED",
    {unauthenticatedKnownEmail: hit, unknownEmail: miss, missingEmailParam: malformed});

  await known.call("completeSignup", {displayName: "Spammer", androidId: crypto.randomUUID()});
  const opened = await Promise.allSettled(Array.from({length: 40}, () => known.call("startGameSession", {gameId: "floppy_bird"})));
  const docs = await db.collection("users").doc(known.uid).collection("gameSessions").get();
  record("GAME-SESSION-SPAM-UNBOUNDED", docs.size >= 40 ? "VULNERABLE" : "PROTECTED",
    {requested: 40, accepted: opened.filter((r) => r.status === "fulfilled").length, sessionDocsWritten: docs.size, claimsMade: 0});

  const huge = await session("huge");
  const crash = await huge.call("completeSignup", {displayName: "x".repeat(200), androidId: "A".repeat(200_000)}).catch((e) => ({code: e.code}));
  await huge.call("completeSignup", {displayName: "x".repeat(200), androidId: "B".repeat(1400)});
  const hd = await userDoc(huge.uid);
  record("SIGNUP-ANDROIDID-UNVALIDATED", "VULNERABLE",
    {with200kbAndroidId: crash, storedAndroidIdLengthWith1400: hd.androidId.length, storedDisplayNameLength: hd.displayName.length});

  const u1 = await session("unk1"); await u1.call("completeSignup", {displayName: "NoId1", androidId: "UNKNOWN_ANDROID_ID"});
  const u2 = await session("unk2"); await u2.call("completeSignup", {displayName: "NoId2", androidId: "UNKNOWN_ANDROID_ID"});
  record("UNKNOWN-ANDROID-ID-COLLISION", (await userDoc(u2.uid)).hasUsedReferral === true ? "VULNERABLE" : "PROTECTED",
    {secondFallbackAccountBlockedFromReferral: (await userDoc(u2.uid)).hasUsedReferral});

  // Self-visibility of abuse signals + rule sanity.
  let ownDoc = null; try { ownDoc = (await getDoc(doc(known.fs, "users", known.uid))).data(); } catch (e) { /* */ }
  let writeDenied = false; try { await updateDoc(doc(known.fs, "users", known.uid), {points: 999999}); } catch (e) { writeDenied = true; }
  let otherReadDenied = false; try { await getDoc(doc(known.fs, "users", huge.uid)); } catch (e) { otherReadDenied = true; }
  let ledgerForgeDenied = false; try { await setDoc(doc(known.fs, "users", known.uid, "rewardEvents", "x"), {finalPoints: 5}); } catch (e) { ledgerForgeDenied = true; }
  let offerTxReadDenied = false; try { await getDoc(doc(known.fs, "serverConfig", "offerwall")); } catch (e) { offerTxReadDenied = true; }
  record("RULES-SANITY", writeDenied && otherReadDenied && ledgerForgeDenied && offerTxReadDenied ? "PROTECTED" : "VULNERABLE",
    {selfPointsWriteDenied: writeDenied, otherUserReadDenied: otherReadDenied, ledgerForgeDenied, secretsReadDenied: offerTxReadDenied,
      ownDocExposesFields: ownDoc ? Object.keys(ownDoc).filter((k) => /androidId|referredBy|adless|bonusAttemptsGranted|email/.test(k)) : null});

  let syncOk;
  try { syncOk = await known.call("syncQuizAnswerKey", {}); } catch (e) { syncOk = {code: e.code, message: e.message}; }
  record("SYNC-ANSWER-KEY-ANY-USER", syncOk && syncOk.success ? "VULNERABLE" : "PARTIAL/INCONCLUSIVE", syncOk);
}

// ---------------------------------------------------------------------------
async function concurrency() {
  const u = await session("race");
  await u.call("completeSignup", {displayName: "Racer", androidId: crypto.randomUUID()});
  const ref = db.collection("users").doc(u.uid);
  await ref.update({pendingLevelRewards: [2], points: 100});
  await ref.collection("rewardEvents").doc("levelup:2").set({source: "LEVEL_UP", status: "locked", finalPoints: 5, createdAt: Timestamp.now()});
  const lv = await Promise.allSettled(Array.from({length: 6}, () => u.call("claimLevelReward", {})));
  const lvPaid = lv.filter((r) => r.status === "fulfilled" && r.value.claimed).length;

  const st = await Promise.allSettled(Array.from({length: 6}, () => u.call("claimDailyStreak", {adWatched: true})));
  const stPaid = st.filter((r) => r.status === "fulfilled" && r.value.rewarded).length;

  const tn = await Promise.allSettled(Array.from({length: 6}, () => u.call("enterTournament", {expectedFee: 20})));
  const tnOk = tn.filter((r) => r.status === "fulfilled").length;

  const s = (await u.call("startGameSession", {gameId: "tower_game"})).sessionId;
  await sleep(16_000);
  const gm = await Promise.allSettled(Array.from({length: 6}, () => u.call("claimReward", {rewardType: "game", gameId: "tower_game", score: 1500, sessionId: s})));
  const gmPaid = gm.filter((r) => r.status === "fulfilled").map((r) => r.value);
  const dbl = gmPaid[0] ? await Promise.allSettled(Array.from({length: 6}, () => u.call("claimDoubleXp", {eventId: gmPaid[0].eventId}))) : [];
  const dblPaid = dbl.filter((r) => r.status === "fulfilled").length;

  const inflate = await u.call("claimReward", {rewardType: "game", gameId: "tower_game", score: 999999, sessionId: (await u.call("startGameSession", {gameId: "tower_game"})).sessionId}).catch((e) => ({code: e.code}));
  const neg = await u.call("claimReward", {rewardType: "game", gameId: "tower_game", score: -5, sessionId: "x"}).catch((e) => ({code: e.code}));
  const fee = await u.call("enterTournament", {expectedFee: -1}).catch((e) => ({code: e.code}));
  const dblPath = await u.call("claimDoubleXp", {eventId: "../x/y"}).catch((e) => ({code: e.code}));
  const pts = (await ref.get()).get("points");
  record("CONCURRENCY-AND-MALFORMED", lvPaid === 1 && stPaid === 1 && tnOk === 1 && gmPaid.length === 1 && dblPaid === 1 ? "PROTECTED" : "VULNERABLE",
    {levelRewardParallel6Paid: lvPaid, streakParallel6Paid: stPaid, tournamentParallel6Entered: tnOk, sameSessionParallel6Paid: gmPaid.length,
      doubleParallel6Paid: dblPaid, finalPoints: pts, expectedPoints: 100 + 5 - 20, hugeScoreImmediate: inflate, negativeScore: neg, negativeFee: fee, slashEventId: dblPath});
}

function tieBreak() {
  const board = mergeSettlementBoard([{uid: "zzzHonestEarly", weeklyXp: 5000}, {uid: "0000BotGrinded", weeklyXp: 5000}], []);
  const pay = buildSettlement(board);
  record("LEADERBOARD-TIE-BREAK-BY-UID", pay[0].uid === "0000BotGrinded" ? "VULNERABLE" : "PROTECTED",
    {rank1: pay[0], rank2: pay[1]});
}

(async () => {
  await seed();
  tieBreak();
  const all = [botFarmDay, referralRedeemClawback, redeemNotIdempotent, offerwall, concurrency, unauthAndSpam];
  const only = (process.env.AUDIT_ONLY || "").split(",").filter(Boolean);
  const steps = only.length ? all.filter((s) => only.includes(s.name)) : all;
  for (const step of steps) {
    try { await step(); } catch (e) { record(step.name, "ERROR", {code: e.code, message: e.message, stack: String(e.stack).split("\n").slice(0, 3)}); }
  }
  console.log("\nAUDIT_JSON=" + JSON.stringify(results));
  process.exit(0);
})();
