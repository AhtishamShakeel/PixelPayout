import * as functions from "firebase-functions/v2";
import * as admin from "firebase-admin";
import {getFirestore, FieldValue, Timestamp} from "firebase-admin/firestore";
import {CallableRequest} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
import {
  ANSWER_KEY_COLLECTION,
  ANSWER_KEY_DOC,
  QuizAnswerKey,
  fetchQuizAnswerKey,
  gradeAnswer,
} from "./economy/quizAnswerKey";
import {
  GAME_SESSIONS_SUBCOLLECTION,
  isKnownGame,
  validateGameClaim,
} from "./economy/gameSession";
import {buildAward, buildMilestoneEvent} from "./economy/awardReward";
import {
  REFERRAL_CODE_MAX_ATTEMPTS,
  buildNewUserProfile,
  generateReferralCode,
} from "./economy/signup";
import {
  REDEMPTIONS_COLLECTION,
  REDEMPTION_OPTIONS_COLLECTION,
  RedemptionOption,
  validateRedemption,
} from "./economy/redemption";
import {
  MAX_BUFF_DURATION_MS,
  MAX_BUFF_MULTIPLIER,
  PointsBuff,
  activeMultiplier,
  resolveBuffGrant,
} from "./economy/pointsBuff";
import {MAX_LEVEL, XP_THRESHOLDS} from "./economy/levelCurve";
import {
  GAME_XP_SCORE_DIVISOR,
  MAX_DAILY_QUIZ_ATTEMPTS,
  QUIZ_CORRECT_XP,
  QUIZ_INCORRECT_XP,
  REFERRAL_UNLOCK_XP,
  REFERRED_USER_REWARD_POINTS,
  REFERRED_USER_REWARD_XP,
  REFERRER_REWARD_POINTS,
  REFERRER_REWARD_XP,
  RewardSource,
  gameXpForScore,
  milestonePointsForLevels,
} from "./economy/rewardConfig";
admin.initializeApp();

const USERS_COLLECTION = "users";
const REWARD_EVENTS_SUBCOLLECTION = "rewardEvents";
const LEVEL_CURVE_COLLECTION = "config";
const LEVEL_CURVE_DOC = "levelCurve";
const FIELD_POINTS = "points";
const FIELD_XP = "xp";
const FIELD_LEVEL = "level";
const FIELD_ACTIVE_BUFF = "activeBuff";
const FIELD_QUIZ_ATTEMPTS = "quiz_attempts";
const FIELD_HAS_USED_REFERRAL = "hasUsedReferral";
const FIELD_REFERRED_BY = "referredBy";
const FIELD_REFERRAL_CODE = "referralCode";
const FIELD_REFERRAL_REWARD_CLAIMED = "referralRewardClaimed";
const MAX_GAME_SCORE = 1_000_000;

/**
 * In-process answer key cache. Function instances are reused between
 * invocations, so a warm instance grades without touching Firestore at all -
 * this was otherwise an extra document read on every single quiz answer.
 * A short TTL bounds how long a newly published quiz takes to become
 * gradeable on an already-warm instance.
 */
const ANSWER_KEY_TTL_MS = 5 * 60 * 1000;
let cachedAnswerKey: {key: QuizAnswerKey; loadedAt: number} | null = null;

/**
 * The curve only changes when this code is deployed, and a deploy always
 * brings up fresh instances - so publishing once per instance keeps the
 * client's copy current without waiting for the scheduled sync, at a cost of
 * one small write per cold start.
 */
let levelCurvePublishedByThisInstance = false;

/** Refreshes the server-side quiz answer key from the published quizzes.json. */
async function syncAnswerKey(): Promise<QuizAnswerKey> {
  const key = await fetchQuizAnswerKey();
  await getFirestore().collection(ANSWER_KEY_COLLECTION).doc(ANSWER_KEY_DOC).set(key);
  cachedAnswerKey = {key, loadedAt: Date.now()};
  console.log("Quiz answer key synced", {
    version: key.version,
    quizzes: Object.keys(key.answers).length,
  });
  return key;
}

/**
 * Publishes the level curve so the client can render "X / Y XP to next level"
 * without duplicating the thresholds in Kotlin (which would silently drift the
 * moment the curve is retuned here). Read-only for clients; the server stays
 * the only writer.
 */
async function publishLevelCurve(): Promise<void> {
  await getFirestore().collection(LEVEL_CURVE_COLLECTION).doc(LEVEL_CURVE_DOC).set({
    maxLevel: MAX_LEVEL,
    thresholds: XP_THRESHOLDS,
    updatedAt: FieldValue.serverTimestamp(),
  });
  levelCurvePublishedByThisInstance = true;
  console.log("Level curve published", {maxLevel: MAX_LEVEL, levels: XP_THRESHOLDS.length});
}

/** Best-effort, once per instance. Never blocks or fails a reward claim. */
async function ensureLevelCurvePublished(): Promise<void> {
  if (levelCurvePublishedByThisInstance) return;
  levelCurvePublishedByThisInstance = true;
  try {
    await publishLevelCurve();
  } catch (error) {
    levelCurvePublishedByThisInstance = false;
    console.error("Level curve publish failed", error);
  }
}

/**
 * Reads the answer key: in-process cache first, then Firestore, then (on a
 * cold database) bootstraps from source so grading works immediately after a
 * deploy instead of waiting for the scheduled sync. Fails closed - if the key
 * can't be obtained we refuse to grade rather than assuming a correct answer.
 */
async function loadAnswerKey(): Promise<QuizAnswerKey> {
  if (cachedAnswerKey && Date.now() - cachedAnswerKey.loadedAt < ANSWER_KEY_TTL_MS) {
    return cachedAnswerKey.key;
  }

  const snap = await getFirestore().collection(ANSWER_KEY_COLLECTION).doc(ANSWER_KEY_DOC).get();
  if (snap.exists) {
    const key = snap.data() as QuizAnswerKey;
    cachedAnswerKey = {key, loadedAt: Date.now()};
    return key;
  }

  console.log("Quiz answer key missing - bootstrapping from source");
  try {
    return await syncAnswerKey();
  } catch (error) {
    console.error("Quiz answer key bootstrap failed", error);
    throw new functions.https.HttpsError(
      "failed-precondition",
      "Quiz answer key unavailable"
    );
  }
}

// weeklyReset used to sweep every user document to zero their quiz attempts.
// It was removed: checkAndResetQuizAttempts already resets each user lazily on
// their first call of a new UTC day, so the sweep was redundant - and it cost
// one read plus one write per registered user per week, forever, whether or
// not that user ever opened the app.

// ✅ Fix for Checking If Email Exists
export const checkEmailExists = functions.https.onCall(async (request: CallableRequest) => {
  const email: string = request.data.email;

  try {
    await admin.auth().getUserByEmail(email);
    return {exists: true}; // ✅ Email exists
 } catch (error: any) {
    if (error.code === "auth/user-not-found") {
      return {exists: false}; // ❌ Email does not exist
   } else {
      throw new functions.https.HttpsError("internal", error.message);
   }
 }
});

/**
 * Creates the caller's user document, or returns the existing one.
 *
 * Called right after Firebase Auth sign-up (both email/password and Google),
 * replacing the client-side document write. Idempotent, so the Google path no
 * longer needs its own "does this user exist?" round-trip, and a retry after a
 * network failure is harmless.
 */
export const completeSignup = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  const existing = await userRef.get();
  if (existing.exists) {
    return {
      success: true,
      created: false,
      referralCode: existing.get(FIELD_REFERRAL_CODE) ?? "",
    };
  }

  const displayName = String(request.data.displayName || "").trim();
  const androidId = String(request.data.androidId || "").trim();
  // Trust the token for the email, not the request body.
  const email = String(request.auth.token.email || request.data.email || "");

  // Has this device held an account before? Checked here because the client
  // could simply not ask. Requires reading across users, which only the Admin
  // SDK can do - another reason this belongs on the server.
  let hasUsedReferral = false;
  if (androidId) {
    const priorAccounts = await firestore
      .collection(USERS_COLLECTION)
      .where("androidId", "==", androidId)
      .limit(1)
      .get();
    hasUsedReferral = !priorAccounts.empty;
  }

  // Referral codes were generated client-side with no uniqueness check, so two
  // users could share one - and submitReferral resolves codes with limit(1),
  // meaning the wrong person would be credited. Retry until the code is free.
  let referralCode = "";
  for (let attempt = 0; attempt < REFERRAL_CODE_MAX_ATTEMPTS; attempt++) {
    const candidate = generateReferralCode();
    const clash = await firestore
      .collection(USERS_COLLECTION)
      .where(FIELD_REFERRAL_CODE, "==", candidate)
      .limit(1)
      .get();

    if (clash.empty) {
      referralCode = candidate;
      break;
    }
    console.log("Referral code collision, retrying", {candidate, attempt});
  }

  if (!referralCode) {
    throw new functions.https.HttpsError("internal", "Could not allocate a referral code");
  }

  const profile = buildNewUserProfile({
    displayName,
    email,
    androidId,
    hasUsedReferral,
    referralCode,
  });

  // create() rather than set(): if a concurrent call won the race, this fails
  // instead of overwriting an account that may already hold a balance.
  try {
    await userRef.create({
      ...profile,
      uid: userId,
      joinedDate: Timestamp.now(),
      lastActive: Timestamp.now(),
      quiz_attempts: 0,
      last_reset_time: Timestamp.now(),
    });
  } catch (error) {
    const latest = await userRef.get();
    if (latest.exists) {
      return {
        success: true,
        created: false,
        referralCode: latest.get(FIELD_REFERRAL_CODE) ?? "",
      };
    }
    throw error;
  }

  console.log("User document created", {userId, hasUsedReferral});
  return {success: true, created: true, referralCode};
});

// New function for on-demand quiz attempt reset
export const checkAndResetQuizAttempts = functions.https.onCall(async (request: CallableRequest) => {
  // Ensure user is authenticated
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const userRef = getFirestore().collection("users").doc(userId);

  // Get the current user data
  const userDoc = await userRef.get();
  if (!userDoc.exists) {
    throw new functions.https.HttpsError("not-found", "User document not found");
  }

  const userData = userDoc.data();

  // Get server timestamp (not client timestamp)
  const now = Timestamp.now();
  const lastResetTime = userData?.last_reset_time || Timestamp.fromDate(new Date(0));

  // Check if it's a new day in UTC time
  const lastResetDate = lastResetTime.toDate();
  const nowDate = now.toDate();

  const isNewDay =
    lastResetDate.getUTCFullYear() !== nowDate.getUTCFullYear() ||
    lastResetDate.getUTCMonth() !== nowDate.getUTCMonth() ||
    lastResetDate.getUTCDate() !== nowDate.getUTCDate();

  if (isNewDay) {
    // It's a new day, reset attempts
    await userRef.update({
      quiz_attempts: 0,
      last_reset_time: now
    });
    return {
      success: true,
      attempts: 0,
      resetPerformed: true,
      lastResetTime: now.toMillis(),
      serverTime: now.toMillis()
    };
  } else {
    // Not a new day, return current attempts
    return {
      success: true,
      attempts: userData?.quiz_attempts || 0,
      resetPerformed: false,
      lastResetTime: lastResetTime.toMillis(),
      serverTime: now.toMillis()
    };
  }
});

/**
 * Applies an award to the transaction: the user's points/xp/level fields, the
 * ledger entry, and any level-milestone bonuses the XP gain unlocked.
 *
 * Milestone bonuses are folded into the SAME points increment as the award
 * itself - two FieldValue.increment writes to one field in one transaction
 * would clobber each other rather than add up. Each milestone gets its own
 * ledger entry keyed by level, so a level can never pay out twice.
 */
function writeAward(
  transaction: FirebaseFirestore.Transaction,
  userRef: FirebaseFirestore.DocumentReference,
  ledgerRef: FirebaseFirestore.DocumentReference,
  award: ReturnType<typeof buildAward>,
  extraUpdates: Record<string, unknown> = {}
): {milestonePoints: number; milestoneLevels: number[]} {
  const milestones = milestonePointsForLevels(award.level.levelsCrossed);
  const milestonePoints = milestones.reduce((sum, m) => sum + m.points, 0);

  const updateData: Record<string, unknown> = {
    ...award.userUpdate,
    ...extraUpdates,
  };

  const totalPoints = award.pointsAwarded + milestonePoints;
  if (totalPoints !== 0) {
    updateData[FIELD_POINTS] = FieldValue.increment(totalPoints);
  }

  if (Object.keys(updateData).length > 0) {
    transaction.update(userRef, updateData as FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>);
  }
  transaction.set(ledgerRef, award.ledgerDoc);

  for (const milestone of milestones) {
    transaction.set(
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`levelup:${milestone.level}`),
      buildMilestoneEvent(milestone.level, milestone.points)
    );
  }

  if (milestones.length > 0) {
    console.log("Level milestones awarded", {
      levels: milestones.map((m) => m.level),
      points: milestonePoints,
    });
  }

  return {milestonePoints, milestoneLevels: milestones.map((m) => m.level)};
}

interface PendingReferrerPayout {
  referrerRef: FirebaseFirestore.DocumentReference;
  referrerLedgerRef: FirebaseFirestore.DocumentReference;
  refereeId: string;
  currentPoints: number;
  currentXp: number;
  currentLevel: number;
}

/**
 * Decides whether this XP gain pushes a referred user past the engagement
 * threshold that unlocks their referrer's reward, and if so reads the
 * referrer's document. Read-only: every write happens in
 * payReferrerIfUnlocked, so callers can satisfy Firestore's
 * all-reads-before-writes rule.
 */
async function readReferrerForUnlock(
  transaction: FirebaseFirestore.Transaction,
  userDoc: FirebaseFirestore.DocumentSnapshot,
  currentXp: number,
  xpGain: number
): Promise<PendingReferrerPayout | null> {
  if (xpGain <= 0) return null;

  const referredBy = userDoc.get(FIELD_REFERRED_BY) as string | undefined;
  if (!referredBy) return null;
  if (userDoc.get(FIELD_REFERRAL_REWARD_CLAIMED) === true) return null;

  // Only on the transition across the threshold, never again after.
  if (currentXp >= REFERRAL_UNLOCK_XP) return null;
  if (currentXp + xpGain < REFERRAL_UNLOCK_XP) return null;

  const referrerRef = getFirestore().collection(USERS_COLLECTION).doc(referredBy);
  const referrerDoc = await transaction.get(referrerRef);
  if (!referrerDoc.exists) {
    console.log("Referral reward skipped: referrer no longer exists", {referredBy});
    return null;
  }

  return {
    referrerRef,
    // Deterministic per referee, so a retry can never pay the referrer twice.
    referrerLedgerRef: referrerRef
      .collection(REWARD_EVENTS_SUBCOLLECTION)
      .doc(`referral_referrer:${userDoc.id}`),
    refereeId: userDoc.id,
    currentPoints: Number(referrerDoc.get(FIELD_POINTS) || 0),
    currentXp: Number(referrerDoc.get(FIELD_XP) || 0),
    currentLevel: Number(referrerDoc.get(FIELD_LEVEL) || 1),
  };
}

/** Applies the payout prepared by readReferrerForUnlock. Writes only. */
function payReferrerIfUnlocked(
  transaction: FirebaseFirestore.Transaction,
  refereeRef: FirebaseFirestore.DocumentReference,
  pending: PendingReferrerPayout | null
): void {
  if (!pending) return;

  const award = buildAward(pending.currentPoints, pending.currentXp, {
    source: "REFERRAL_REFERRER",
    basePoints: REFERRER_REWARD_POINTS,
    baseXp: REFERRER_REWARD_XP,
    metadata: {refereeId: pending.refereeId},
    storedLevel: pending.currentLevel,
  });

  // The referrer's XP can itself cross a milestone, so this goes through the
  // same path as any other award rather than writing points directly.
  writeAward(transaction, pending.referrerRef, pending.referrerLedgerRef, award);
  transaction.update(refereeRef, {[FIELD_REFERRAL_REWARD_CLAIMED]: true});

  console.log("Referral reward applied", {
    refereeId: pending.refereeId,
    pointsAwarded: award.pointsAwarded,
    xpAwarded: award.xpAwarded,
  });
}

/**
 * Refreshes the answer key on a schedule so newly published quizzes can be
 * graded. Also exposed as a callable below for manual/immediate syncs.
 */
export const scheduledQuizAnswerKeySync = onSchedule("every 6 hours", async (_event) => {
  await publishLevelCurve();
  await syncAnswerKey();
});

export const syncQuizAnswerKey = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  await publishLevelCurve();
  const key = await syncAnswerKey();
  return {success: true, version: key.version, quizzes: Object.keys(key.answers).length};
});

/**
 * Accounts permitted to claim admin rights via bootstrapAdmin.
 *
 * Read from functions/.env (comma-separated) rather than hardcoded, because
 * this repository is public: naming the account would tell an attacker exactly
 * which inbox to go after to take over payouts. The .env file is gitignored.
 *
 * Not a database flag either - that flag would itself need protecting.
 * An empty list disables bootstrapping entirely, which is the safe default if
 * the variable is ever missing.
 */
const ADMIN_BOOTSTRAP_EMAILS = String(process.env.ADMIN_BOOTSTRAP_EMAILS || "")
  .split(",")
  .map((email) => email.trim().toLowerCase())
  .filter((email) => email.length > 0);

/**
 * One-time self-grant of the admin claim, for the accounts listed above.
 *
 * The alternative - downloading a service-account key to run a local script -
 * puts a credential with full project access on disk, which is a far worse
 * thing to leak than this. This can only ever grant admin to an address on
 * that list, and only to a verified Google account.
 *
 * The caller must sign out and back in afterwards: custom claims are baked
 * into the ID token, so an existing session won't see the new claim.
 */
export const bootstrapAdmin = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const email = String(request.auth.token.email || "").toLowerCase();
  const emailVerified = request.auth.token.email_verified === true;

  if (ADMIN_BOOTSTRAP_EMAILS.length === 0) {
    console.log("Admin bootstrap refused: no eligible accounts configured");
    throw new functions.https.HttpsError("permission-denied", "Not eligible for admin");
  }

  if (!emailVerified || !ADMIN_BOOTSTRAP_EMAILS.includes(email)) {
    console.log("Admin bootstrap refused", {uid: request.auth.uid, email, emailVerified});
    throw new functions.https.HttpsError("permission-denied", "Not eligible for admin");
  }

  if (request.auth.token.admin === true) {
    return {success: true, alreadyAdmin: true};
  }

  await admin.auth().setCustomUserClaims(request.auth.uid, {admin: true});
  console.log("Admin claim granted", {uid: request.auth.uid, email});

  return {success: true, alreadyAdmin: false};
});

/**
 * Grants a temporary Points buff.
 *
 * Admin-only: a self-service grant would let any user hand themselves a
 * multiplier. Real grants will come from server-verified sources (a completed
 * offer, a streak milestone, a promotion) calling the same resolveBuffGrant
 * path internally; this callable exists for support and for testing the
 * mechanism before those sources exist.
 */
export const grantPointsBuff = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (request.auth.token.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin only");
  }

  const targetUid = String(request.data.uid || "").trim();
  const multiplier = Number(request.data.multiplier);
  const durationMs = Number(request.data.durationMs);

  if (!targetUid) {
    throw new functions.https.HttpsError("invalid-argument", "Target uid is required");
  }
  if (!Number.isFinite(multiplier) || multiplier <= 1 || multiplier > MAX_BUFF_MULTIPLIER) {
    throw new functions.https.HttpsError("invalid-argument", "Invalid buff multiplier");
  }
  if (!Number.isFinite(durationMs) || durationMs <= 0 || durationMs > MAX_BUFF_DURATION_MS) {
    throw new functions.https.HttpsError("invalid-argument", "Invalid buff duration");
  }

  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(targetUid);

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const now = Date.now();
    const granted = resolveBuffGrant(
      userDoc.get(FIELD_ACTIVE_BUFF) as PointsBuff | undefined,
      {multiplier, durationMs, source: "ADMIN_GRANT"},
      now
    );

    if (!granted) {
      // A stronger buff is already running; leave it alone.
      return {applied: false as const};
    }

    transaction.update(userRef, {[FIELD_ACTIVE_BUFF]: granted});

    // Recorded in the ledger for the audit trail. It moves no balance itself -
    // its effect shows up as multiplierApplied on later eligible awards.
    transaction.set(
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`buff:${now}`),
      buildAward(0, 0, {
        source: "ADMIN_GRANT",
        basePoints: 0,
        baseXp: 0,
        metadata: {
          buffMultiplier: granted.multiplier,
          buffExpiresAt: granted.expiresAt,
          grantedBy: request.auth?.uid,
        },
      }).ledgerDoc
    );

    return {applied: true as const, buff: granted};
  });

  return {success: true, ...result};
});

/**
 * Opens a game session. The returned sessionId must be presented when
 * claiming the reward, which is what ties a claim to a real, server-timed
 * play session instead of a bare "trust me, I scored N" call.
 */
export const startGameSession = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const gameId = String(request.data.gameId || "").trim();
  if (!isKnownGame(gameId)) {
    throw new functions.https.HttpsError("invalid-argument", "Unknown game");
  }

  const sessionRef = getFirestore()
    .collection(USERS_COLLECTION)
    .doc(request.auth.uid)
    .collection(GAME_SESSIONS_SUBCOLLECTION)
    .doc();

  await sessionRef.set({
    gameId,
    startedAt: FieldValue.serverTimestamp(),
    consumed: false,
  });

  return {sessionId: sessionRef.id};
});

export const claimReward = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const rewardType = String(request.data.rewardType || "").trim();
  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  await ensureLevelCurvePublished();

  let xpAward = 0;
  let incrementQuizAttempt = false;
  let rewardSource: RewardSource;
  let eventMetadata: Record<string, unknown>;
  let wasCorrect = false;
  let gameSessionRef: FirebaseFirestore.DocumentReference | null = null;
  let ledgerRef: FirebaseFirestore.DocumentReference;

  if (rewardType === "quiz") {
    const category = String(request.data.category || "").trim();
    const quizId = String(request.data.quizId || "").trim();
    const questionIndex = Number(request.data.questionIndex);
    const selectedAnswer = Number(request.data.selectedAnswer);

    if (!category || !quizId) {
      throw new functions.https.HttpsError("invalid-argument", "Quiz category and id are required");
    }

    // The client's own claim about correctness is deliberately ignored -
    // correctness is decided here, against the server's answer key.
    const answerKey = await loadAnswerKey();
    const graded = gradeAnswer(answerKey, category, quizId, questionIndex, selectedAnswer);

    if (graded === null) {
      throw new functions.https.HttpsError("invalid-argument", "Unknown quiz question");
    }

    wasCorrect = graded;
    incrementQuizAttempt = true;
    // Quizzes are a progression activity: XP only, no redeemable Points.
    xpAward = wasCorrect ? QUIZ_CORRECT_XP : QUIZ_INCORRECT_XP;
    rewardSource = "QUIZ";
    eventMetadata = {category, quizId, questionIndex, selectedAnswer, wasCorrect};
    // Quiz claims still have no natural idempotency key (a user may legitimately
    // answer the same question again on another attempt). The daily attempt cap
    // is what bounds them.
    ledgerRef = userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc();
  } else if (rewardType === "game") {
    const gameId = String(request.data.gameId || "").trim();
    const score = Number(request.data.score || 0);
    const sessionId = String(request.data.sessionId || "").trim();

    if (!sessionId) {
      throw new functions.https.HttpsError("invalid-argument", "Game session is required");
    }
    if (!isKnownGame(gameId) || !GAME_XP_SCORE_DIVISOR[gameId]) {
      throw new functions.https.HttpsError("invalid-argument", "Unknown game reward");
    }
    if (!Number.isFinite(score) || score < 0 || score > MAX_GAME_SCORE) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid game score");
    }

    gameSessionRef = userRef.collection(GAME_SESSIONS_SUBCOLLECTION).doc(sessionId);

    // Games are a progression activity: XP only, capped per session so one
    // outlier run can't shortcut the level curve.
    xpAward = gameXpForScore(gameId, score);

    rewardSource = "GAME";
    eventMetadata = {gameId, score, sessionId};
    // The session is single-use, so its id is a natural idempotency key:
    // a redelivered claim lands on the same ledger doc instead of paying twice.
    ledgerRef = userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`game:${sessionId}`);
  } else {
    throw new functions.https.HttpsError("invalid-argument", "Unknown reward type");
  }

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    const sessionDoc = gameSessionRef ? await transaction.get(gameSessionRef) : null;

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);
    const currentLevel = Number(userDoc.get(FIELD_LEVEL) || 1);
    const currentAttempts = Number(userDoc.get(FIELD_QUIZ_ATTEMPTS) || 0);
    // Only actually applied to multiplier-eligible sources - buildAward
    // decides that from the source, not from this value.
    const buffMultiplier = activeMultiplier(
      userDoc.get(FIELD_ACTIVE_BUFF) as PointsBuff | undefined,
      Date.now()
    );

    if (incrementQuizAttempt && currentAttempts >= MAX_DAILY_QUIZ_ATTEMPTS) {
      throw new functions.https.HttpsError("failed-precondition", "Daily quiz limit reached");
    }

    // Referrer payout is evaluated here, in the same transaction that grants
    // the XP - it used to be a document trigger that woke on EVERY user write
    // (millions of invocations to check a condition that can fire at most once
    // per referred user). Reads must all happen before any write below.
    const referrer = await readReferrerForUnlock(transaction, userDoc, currentXp, xpAward);

    if (gameSessionRef) {
      if (!sessionDoc || !sessionDoc.exists) {
        throw new functions.https.HttpsError("not-found", "Game session not found");
      }
      if (sessionDoc.get("consumed") === true) {
        throw new functions.https.HttpsError("failed-precondition", "Game session already claimed");
      }
      if (sessionDoc.get("gameId") !== eventMetadata.gameId) {
        throw new functions.https.HttpsError("invalid-argument", "Game session mismatch");
      }

      const startedAt = sessionDoc.get("startedAt") as Timestamp | undefined;
      if (!startedAt) {
        throw new functions.https.HttpsError("failed-precondition", "Game session not started");
      }

      const elapsedMs = Date.now() - startedAt.toMillis();
      const validation = validateGameClaim({
        gameId: String(eventMetadata.gameId),
        score: Number(eventMetadata.score),
        elapsedMs,
      });

      if (!validation.valid) {
        console.log("Game claim rejected", {userId, reason: validation.rejection, elapsedMs});
        // Burn the session so a rejected claim can't simply be retried with a
        // lower score until one happens to pass. Throwing here would roll the
        // burn back with the rest of the transaction, so the rejection is
        // returned and raised by the caller once this has committed.
        transaction.update(gameSessionRef, {consumed: true, rejectedReason: validation.rejection});
        return {rejected: true as const, reason: validation.rejection};
      }

      transaction.update(gameSessionRef, {consumed: true});
      eventMetadata = {...eventMetadata, elapsedMs};
    }

    const award = buildAward(currentPoints, currentXp, {
      source: rewardSource,
      basePoints: 0, // quizzes and games award XP only
      baseXp: xpAward,
      metadata: eventMetadata,
      activeMultiplier: buffMultiplier,
      storedLevel: currentLevel,
    });

    const extraUpdates: Record<string, FieldValue | number> = {};
    if (incrementQuizAttempt) {
      extraUpdates[FIELD_QUIZ_ATTEMPTS] = FieldValue.increment(1);
    }

    const {milestonePoints, milestoneLevels} =
      writeAward(transaction, userRef, ledgerRef, award, extraUpdates);

    payReferrerIfUnlocked(transaction, userRef, referrer);

    return {
      rejected: false as const,
      pointsAwarded: award.pointsAwarded,
      milestonePoints,
      milestoneLevels,
      totalPoints: currentPoints + award.pointsAwarded + milestonePoints,
      xpAwarded: award.xpAwarded,
      totalXp: award.level.xp,
      level: award.level.level,
      leveledUp: award.level.leveledUp,
      attempts: incrementQuizAttempt ? currentAttempts + 1 : currentAttempts,
      wasCorrect,
    };
  });

  // Raised only after the burn above has committed.
  if (result.rejected) {
    throw new functions.https.HttpsError("invalid-argument", "Implausible game result");
  }

  const {rejected: _rejected, ...payload} = result;
  return {
    success: true,
    ...payload,
  };
});

/**
 * Spends Points on a redemption option.
 *
 * The cost is read from the option document server-side - a client-supplied
 * price is never trusted. The debit, the ledger entry and the pending
 * redemption record all commit in one transaction, so a balance can never go
 * negative and a redemption can never exist without its matching debit.
 *
 * Paying the user is a separate business process; this only records the
 * request and takes the Points.
 */
export const redeemReward = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const optionId = String(request.data.optionId || "").trim();
  const payoutNumber = String(request.data.payoutNumber || "").trim();

  if (!optionId) {
    throw new functions.https.HttpsError("invalid-argument", "Redemption option is required");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const optionRef = firestore.collection(REDEMPTION_OPTIONS_COLLECTION).doc(optionId);

  const result = await firestore.runTransaction(async (transaction) => {
    const [userDoc, optionDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(optionRef),
    ]);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);
    const currentLevel = Number(userDoc.get(FIELD_LEVEL) || 1);

    const option = optionDoc.exists ? (optionDoc.data() as RedemptionOption) : null;
    const validation = validateRedemption({
      option,
      userPoints: currentPoints,
      userLevel: currentLevel,
      payoutNumber,
    });

    if (!validation.ok) {
      return {ok: false as const, rejection: validation.rejection};
    }

    const pointsCost = validation.pointsCost as number;
    const redemptionRef = firestore.collection(REDEMPTIONS_COLLECTION).doc();
    const ledgerRef = userRef
      .collection(REWARD_EVENTS_SUBCOLLECTION)
      .doc(`redemption:${redemptionRef.id}`);

    // Negative points, zero XP. buildAward refuses to scale a negative award
    // by any active buff, and awards no XP, so progression is untouched.
    const award = buildAward(currentPoints, currentXp, {
      source: "REDEMPTION",
      basePoints: -pointsCost,
      baseXp: 0,
      metadata: {
        optionId,
        optionTitle: option?.title,
        redemptionId: redemptionRef.id,
      },
      storedLevel: currentLevel,
    });

    writeAward(transaction, userRef, ledgerRef, award);

    transaction.set(redemptionRef, {
      // Ownership is carried by this field now that the document no longer
      // lives under the user's path.
      uid: userId,
      // Denormalised so a payout can be actioned without a second lookup.
      userDisplayName: userDoc.get("displayName") ?? "",
      userEmail: userDoc.get("email") ?? "",
      optionId,
      optionTitle: option?.title ?? "",
      optionType: option?.type ?? "",
      pointsCost,
      status: "pending",
      payoutNumber: option?.type === "EASYPAISA" ? payoutNumber : null,
      ledgerEventId: ledgerRef.id,
      createdAt: FieldValue.serverTimestamp(),
    });

    return {
      ok: true as const,
      redemptionId: redemptionRef.id,
      pointsSpent: pointsCost,
      remainingPoints: currentPoints - pointsCost,
    };
  });

  if (!result.ok) {
    const code = result.rejection === "insufficient_points" ||
      result.rejection === "level_too_low" ||
      result.rejection === "option_disabled" ?
      "failed-precondition" :
      "invalid-argument";
    throw new functions.https.HttpsError(code, String(result.rejection));
  }

  console.log("Redemption created", {userId, ...result});
  return {success: true, ...result};
});

/**
 * Lists redemptions for the admin tool. Admin-only.
 *
 * The tool reads through this rather than querying Firestore from the
 * browser, so the security rules never have to grant any client read access
 * across users - the only way to see everyone's payout numbers is to hold the
 * admin claim and come through here.
 */
export const listRedemptions = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (request.auth.token.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin only");
  }

  const status = String(request.data.status || "pending").trim();
  if (status !== "pending" && status !== "approved" && status !== "rejected") {
    throw new functions.https.HttpsError("invalid-argument", "Unknown status");
  }

  const pageSize = Math.min(Math.max(Number(request.data.limit) || 100, 1), 200);

  const snapshot = await getFirestore()
    .collection(REDEMPTIONS_COLLECTION)
    .where("status", "==", status)
    .orderBy("createdAt", "desc")
    .limit(pageSize)
    .get();

  return {
    redemptions: snapshot.docs.map((doc) => {
      const data = doc.data();
      return {
        id: doc.id,
        uid: data.uid ?? "",
        userDisplayName: data.userDisplayName ?? "",
        userEmail: data.userEmail ?? "",
        optionTitle: data.optionTitle ?? data.optionId ?? "",
        optionType: data.optionType ?? "",
        pointsCost: Number(data.pointsCost || 0),
        payoutNumber: data.payoutNumber ?? null,
        status: data.status ?? "",
        rejectionReason: data.rejectionReason ?? null,
        createdAtMillis: data.createdAt?.toMillis?.() ?? null,
      };
    }),
  };
});

/**
 * Approves or rejects a pending redemption. Admin-only.
 *
 * Rejecting refunds the Points - a failed payout must never silently cost the
 * user their balance. The original ledger entry is marked "reversed" and the
 * refund gets its own entry, so the history shows both halves rather than
 * rewriting the past.
 */
export const resolveRedemption = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (request.auth.token.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin only");
  }

  const redemptionId = String(request.data.redemptionId || "").trim();
  const status = String(request.data.status || "").trim();
  const reason = String(request.data.reason || "").trim();

  if (!redemptionId) {
    throw new functions.https.HttpsError("invalid-argument", "redemptionId is required");
  }
  if (status !== "approved" && status !== "rejected") {
    throw new functions.https.HttpsError("invalid-argument", "status must be approved or rejected");
  }

  const firestore = getFirestore();
  const redemptionRef = firestore.collection(REDEMPTIONS_COLLECTION).doc(redemptionId);

  const result = await firestore.runTransaction(async (transaction) => {
    const redemptionDoc = await transaction.get(redemptionRef);

    if (!redemptionDoc.exists) {
      throw new functions.https.HttpsError("not-found", "Redemption not found");
    }
    if (redemptionDoc.get("status") !== "pending") {
      throw new functions.https.HttpsError("failed-precondition", "Redemption already resolved");
    }

    // The owner comes from the document, so an admin never has to supply
    // (or mistype) a uid.
    const targetUid = String(redemptionDoc.get("uid") || "");
    if (!targetUid) {
      throw new functions.https.HttpsError("failed-precondition", "Redemption has no owner");
    }

    const userRef = firestore.collection(USERS_COLLECTION).doc(targetUid);
    const userDoc = await transaction.get(userRef);

    const resolution: Record<string, unknown> = {
      status,
      resolvedAt: FieldValue.serverTimestamp(),
      resolvedBy: request.auth?.uid,
    };
    if (reason) resolution.rejectionReason = reason;

    if (status === "approved") {
      transaction.update(
        redemptionRef,
        resolution as FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>
      );
      return {refunded: 0, targetUid};
    }

    // Rejected: give the Points back.
    const pointsCost = Number(redemptionDoc.get("pointsCost") || 0);
    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);

    const refund = buildAward(currentPoints, currentXp, {
      source: "REDEMPTION",
      basePoints: pointsCost,
      baseXp: 0,
      metadata: {redemptionId, refundOf: redemptionDoc.get("ledgerEventId"), reason},
      storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
    });

    writeAward(
      transaction,
      userRef,
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`refund:${redemptionId}`),
      refund
    );

    const originalLedgerId = redemptionDoc.get("ledgerEventId") as string | undefined;
    if (originalLedgerId) {
      transaction.update(
        userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(originalLedgerId),
        {status: "reversed"}
      );
    }

    transaction.update(
      redemptionRef,
      {...resolution, refundedPoints: pointsCost} as
        FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>
    );
    return {refunded: pointsCost, targetUid};
  });

  console.log("Redemption resolved", {redemptionId, status, ...result});
  return {success: true, status, ...result};
});

export const submitReferral = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const referralCode = String(request.data.referralCode || "").trim().toUpperCase();
  if (!referralCode) {
    throw new functions.https.HttpsError("invalid-argument", "Referral code is required");
  }

  const currentUserId = request.auth.uid;
  const firestore = getFirestore();

  const referralQuery = await firestore.collection(USERS_COLLECTION)
    .where(FIELD_REFERRAL_CODE, "==", referralCode)
    .limit(1)
    .get();

  if (referralQuery.empty) {
    return {status: "invalid_code"};
  }

  const referrerDoc = referralQuery.docs[0];
  const referrerId = referrerDoc.id;

  if (referrerId === currentUserId) {
    return {status: "invalid_code"};
  }

  const userRef = firestore.collection(USERS_COLLECTION).doc(currentUserId);
  // Deterministic: a referee can only ever produce one of these, so the key
  // itself is the idempotency guarantee, independent of the hasUsedReferral
  // flag check below (which already protects this too - this is redundant
  // defense-in-depth against a redelivered/retried request racing the flag).
  const refereeLedgerRef = userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`referral_referee:${currentUserId}`);

  await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    const existingLedgerDoc = await transaction.get(refereeLedgerRef);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    if (userDoc.get(FIELD_HAS_USED_REFERRAL) === true) {
      throw new functions.https.HttpsError("failed-precondition", "Referral already used");
    }

    if (existingLedgerDoc.exists) {
      // Already recorded (redelivered request) - don't double-award.
      return;
    }

    // Referrals are one of the few Points sources, and give XP as well.
    const award = buildAward(
      Number(userDoc.get(FIELD_POINTS) || 0),
      Number(userDoc.get(FIELD_XP) || 0),
      {
        source: "REFERRAL_REFEREE",
        basePoints: REFERRED_USER_REWARD_POINTS,
        baseXp: REFERRED_USER_REWARD_XP,
        metadata: {referrerId},
        storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
      }
    );

    writeAward(transaction, userRef, refereeLedgerRef, award, {
      [FIELD_HAS_USED_REFERRAL]: true,
      [FIELD_REFERRED_BY]: referrerId,
    });
  });

  return {status: "success"};
});
