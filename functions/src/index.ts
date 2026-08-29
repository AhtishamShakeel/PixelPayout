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
  maskDisplayName,
  PAYOUT_FEED_COLLECTION,
} from "./economy/payoutFeed";
import {
  nextWeeklyXp,
  prizeForRank,
  totalWeeklyPrizePool,
  utcWeekFor,
  weekEndMillis,
  LEADERBOARD_PREVIEW_SIZE,
  LEADERBOARD_SIZE,
} from "./economy/leaderboard";
import {
  goalProgress,
  isGoalDone,
  resolveBonusPoints,
  resolveGoalBonus,
  selectDailyGoals,
  statsForDay,
  DAILY_GOALS_CONFIG_DOC,
  DAILY_GOAL_COUNT,
  DailyStats,
} from "./economy/dailyGoals";
import {
  resolveStreakClaim,
  resolveStreakReward,
  streakRewardForDay,
  utcDayFor,
  STREAK_CYCLE_DAYS,
  STREAK_REWARDS,
} from "./economy/streak";
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
/**
 * Caps how far any one function may scale out.
 *
 * Every 2nd-gen function is its own Cloud Run service holding a full vCPU, and
 * the regional quota is the sum of cpu x max-instances across all of them.
 * With nothing set here each of the eighteen functions reserved the platform
 * default, which took the project past that ceiling and failed every deploy
 * with "Container Healthcheck failed. Quota exceeded for total allowable CPU"
 * - a message that reads like a broken container and is nothing of the kind.
 *
 * It is worth having regardless of the quota: an unbounded fan-out is also an
 * unbounded bill, and a runaway loop against Firestore is much cheaper to stop
 * at ten instances than at a thousand. Anything that genuinely needs more can
 * override it per function.
 */
functions.setGlobalOptions({maxInstances: 10});

const CONFIG_COLLECTION = "config";
const LEVEL_CURVE_DOC = "levelCurve";
const FIELD_POINTS = "points";
const FIELD_XP = "xp";
const FIELD_LEVEL = "level";
const FIELD_STREAK_COUNT = "streakCount";
const FIELD_LAST_STREAK_DAY = "lastStreakDayUtc";
// Tracked apart from the streak day: the streak advances whether or not an ad
// played, the reward does not, and the reward stays claimable all day.
const FIELD_LAST_STREAK_REWARD_DAY = "lastStreakRewardDayUtc";
// How often a claim moved the streak on without an ad. The number that decides
// whether AdMob server-side verification is worth building.
const FIELD_ADLESS_STREAK_CLAIMS = "adlessStreakClaims";
// Per-day activity counters behind the daily goals. One map field rather than
// a subcollection: claimReward already reads and writes this document, so
// tracking costs no extra read.
const FIELD_DAILY_STATS = "dailyStats";
const FIELD_LAST_GOAL_BONUS_DAY = "lastGoalBonusDayUtc";
// The weekly leaderboard. Reset lazily by comparing weekKey rather than by a
// job that rewrites every user document at the boundary.
const FIELD_WEEKLY_XP = "weeklyXp";
const FIELD_WEEK_KEY = "weekKey";
const FIELD_ACTIVE_BUFF = "activeBuff";
// Held apart from the Points buff rather than as one field with a kind, so a
// user can run both at once and neither grant can clobber the other.
const FIELD_ACTIVE_XP_BUFF = "activeXpBuff";
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
  await getFirestore().collection(CONFIG_COLLECTION).doc(LEVEL_CURVE_DOC).set({
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
  // Defaults to points so existing callers keep their meaning.
  const kind = String(request.data.kind || "points");

  if (!targetUid) {
    throw new functions.https.HttpsError("invalid-argument", "Target uid is required");
  }
  if (kind !== "points" && kind !== "xp") {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "kind must be points or xp"
    );
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
    const field = kind === "xp" ? FIELD_ACTIVE_XP_BUFF : FIELD_ACTIVE_BUFF;
    const granted = resolveBuffGrant(
      userDoc.get(field) as PointsBuff | undefined,
      {multiplier, durationMs, source: "ADMIN_GRANT"},
      now
    );

    if (!granted) {
      // A stronger buff is already running; leave it alone.
      return {applied: false as const};
    }

    transaction.update(userRef, {[field]: granted});

    // Recorded in the ledger for the audit trail. It moves no balance itself -
    // its effect shows up as multiplierApplied on later eligible awards.
    transaction.set(
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION)
        .doc(`buff:${kind}:${now}`),
      buildAward(0, 0, {
        source: "ADMIN_GRANT",
        basePoints: 0,
        baseXp: 0,
        metadata: {
          buffKind: kind,
          buffMultiplier: granted.multiplier,
          buffExpiresAt: granted.expiresAt,
          grantedBy: request.auth?.uid,
        },
      }).ledgerDoc
    );

    return {applied: true as const, buff: granted};
  });

  return {success: true, kind, ...result};
});

/**
 * Advances the daily streak, and pays today's reward if an ad was watched.
 *
 * Two independent once-per-day gates, which is the whole design:
 *
 *   the STREAK advances on the first call of a new day, always. It is the
 *   retention mechanic and must not be lost to an ad that would not load.
 *
 *   the REWARD pays only when an ad was watched, and only once. Until it does,
 *   it stays claimable for the rest of the day, so a user whose ad failed can
 *   simply tap again rather than losing the day.
 *
 * The decision and the write share one transaction, so two taps racing cannot
 * both pass either gate.
 */
export const claimDailyStreak = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const adWatched = request.data?.adWatched === true;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    // Server time, never the client's - the same guarantee the quiz reset
    // gives, and the reason a device clock cannot buy an extra streak day.
    const now = Date.now();
    const todayUtc = utcDayFor(now);

    const claim = resolveStreakClaim(
      (userDoc.get(FIELD_LAST_STREAK_DAY) as number | undefined) ?? null,
      todayUtc,
      Number(userDoc.get(FIELD_STREAK_COUNT) || 0)
    );
    const reward = resolveStreakReward(
      (userDoc.get(FIELD_LAST_STREAK_REWARD_DAY) as number | undefined) ?? null,
      todayUtc,
      adWatched
    );

    const day = claim.day;
    const updates: Record<string, unknown> = {};
    if (claim.status === "claimed") {
      updates[FIELD_STREAK_COUNT] = day;
      updates[FIELD_LAST_STREAK_DAY] = todayUtc;
    }

    if (!reward.pay) {
      // A day that moved on without paying is the case worth counting.
      if (reward.reason === "no_ad" && claim.status === "claimed") {
        updates[FIELD_ADLESS_STREAK_CLAIMS] = FieldValue.increment(1);
      }
      if (Object.keys(updates).length > 0) {
        transaction.update(
          userRef,
          updates as FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>
        );
      }
      return {
        day,
        streakAdvanced: claim.status === "claimed",
        rewarded: false as const,
        reason: reward.reason,
        pointsAwarded: 0,
        xpAwarded: 0,
      };
    }

    const dayReward = streakRewardForDay(day);
    const award = buildAward(
      Number(userDoc.get(FIELD_POINTS) || 0),
      Number(userDoc.get(FIELD_XP) || 0),
      {
        source: "STREAK",
        basePoints: dayReward.points,
        baseXp: dayReward.xp,
        metadata: {
          streakDay: day,
          continued: claim.status === "claimed" && claim.continued,
        },
        storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
      }
    );

    // Keyed by the day, so a retry that somehow passed the gate above writes
    // the same document rather than paying twice.
    writeAward(
      transaction,
      userRef,
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`streak:${todayUtc}`),
      award,
      {...updates, [FIELD_LAST_STREAK_REWARD_DAY]: todayUtc}
    );

    // A day may also hand out a buff. Nothing in the table does yet; the path
    // exists so that becomes a data change.
    if (dayReward.buff) {
      const field = dayReward.buff.kind === "xp" ?
        FIELD_ACTIVE_XP_BUFF :
        FIELD_ACTIVE_BUFF;
      const granted = resolveBuffGrant(
        userDoc.get(field) as PointsBuff | undefined,
        {
          multiplier: dayReward.buff.multiplier,
          durationMs: dayReward.buff.durationMs,
          source: "STREAK",
        },
        now
      );
      if (granted) transaction.update(userRef, {[field]: granted});
    }

    return {
      day,
      streakAdvanced: claim.status === "claimed",
      rewarded: true as const,
      pointsAwarded: award.pointsAwarded,
      xpAwarded: award.xpAwarded,
    };
  });

  console.log("Daily streak", {userId, adWatched, ...result});
  return {
    success: true,
    serverTime: Date.now(),
    cycleDays: STREAK_CYCLE_DAYS,
    cycle: STREAK_REWARDS,
    ...result,
  };
});

/**
 * The streak reward table.
 *
 * Served from the same constant the awards are computed from, so the card can
 * show what every day of the cycle pays - before any claim - without keeping
 * its own copy to fall out of step. Publishing it to config/ would work too,
 * but that is a document someone has to remember to edit when the table
 * changes; this cannot drift.
 */
export const getStreakConfig = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  return {
    serverTime: Date.now(),
    cycleDays: STREAK_CYCLE_DAYS,
    cycle: STREAK_REWARDS,
  };
});

/**
 * The daily goal bonus, as configured.
 *
 * Cached in the instance for a minute. getDailyGoals runs on every return to
 * Home, and a Firestore read per resume per user is a real bill for a number
 * that changes about once a month. A minute of staleness after a console edit
 * is the trade.
 */
let goalConfigCache: {points: number; readAt: number} | null = null;
const GOAL_CONFIG_TTL_MS = 60_000;

async function configuredGoalBonus(): Promise<number> {
  const now = Date.now();
  if (goalConfigCache && now - goalConfigCache.readAt < GOAL_CONFIG_TTL_MS) {
    return goalConfigCache.points;
  }

  let points: number;
  try {
    const snapshot = await getFirestore()
      .collection(CONFIG_COLLECTION)
      .doc(DAILY_GOALS_CONFIG_DOC)
      .get();
    points = resolveBonusPoints(snapshot.get("bonusPoints"));
  } catch (error) {
    // A config read that fails must not stop the goals paying out.
    console.error("Daily goal config unreadable", error);
    points = resolveBonusPoints(undefined);
  }

  goalConfigCache = {points, readAt: now};
  return points;
}

/**
 * Today's three goals, with progress.
 *
 * The selection is derived from the uid and the day rather than stored, so it
 * is the same on every read without a write, stable for the whole day, and
 * different from the next user's. Progress is computed from the same counters
 * the client already receives on its user document, so the card stays live
 * without calling back here.
 */
export const getDailyGoals = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const userDoc = await getFirestore()
    .collection(USERS_COLLECTION)
    .doc(userId)
    .get();

  const todayUtc = utcDayFor(Date.now());
  const goals = selectDailyGoals(userId, todayUtc);
  const stats = statsForDay(
    userDoc.get(FIELD_DAILY_STATS) as Partial<DailyStats> | undefined,
    todayUtc
  );

  const bonusPoints = await configuredGoalBonus();

  // serverTime rides along on every response so ServerClock can sync from
  // whichever call happens to land first, rather than depending on the quiz
  // reset having been fetched. Nothing here trusts a client clock - this is
  // only so the UI counts down against the same time the server enforces.
  return {
    serverTime: Date.now(),
    dayUtc: todayUtc,
    bonusPoints,
    goalCount: DAILY_GOAL_COUNT,
    bonusClaimed: userDoc.get(FIELD_LAST_GOAL_BONUS_DAY) === todayUtc,
    goals: goals.map((goal) => ({
      id: goal.id,
      kind: goal.kind,
      target: goal.target,
      progress: goalProgress(goal, stats),
      done: isGoalDone(goal, stats),
    })),
  };
});

/**
 * Pays the bonus for finishing all three of today's goals.
 *
 * Every condition is re-derived here from the counters and the day. The client
 * is told what it may do, never trusted about what it has done.
 */
export const claimDailyGoalBonus = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const adWatched = request.data?.adWatched === true;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  // Read outside the transaction: it is not part of what has to stay
  // consistent with the user document, and pulling it in would widen the read
  // set for no reason.
  const bonusPoints = await configuredGoalBonus();

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const todayUtc = utcDayFor(Date.now());
    const goals = selectDailyGoals(userId, todayUtc);
    const stats = statsForDay(
      userDoc.get(FIELD_DAILY_STATS) as Partial<DailyStats> | undefined,
      todayUtc
    );

    const decision = resolveGoalBonus(
      (userDoc.get(FIELD_LAST_GOAL_BONUS_DAY) as number | undefined) ?? null,
      todayUtc,
      goals,
      stats,
      adWatched
    );

    if (!decision.pay) {
      return {claimed: false as const, reason: decision.reason, pointsAwarded: 0};
    }

    const award = buildAward(
      Number(userDoc.get(FIELD_POINTS) || 0),
      Number(userDoc.get(FIELD_XP) || 0),
      {
        source: "MISSION",
        basePoints: bonusPoints,
        baseXp: 0,
        metadata: {goals: goals.map((goal) => goal.id), dayUtc: todayUtc},
        storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
      }
    );

    // Keyed by the day, so a retry writes the same document rather than
    // paying twice.
    writeAward(
      transaction,
      userRef,
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`goals:${todayUtc}`),
      award,
      {[FIELD_LAST_GOAL_BONUS_DAY]: todayUtc}
    );

    return {
      claimed: true as const,
      pointsAwarded: award.pointsAwarded,
    };
  });

  console.log("Daily goal bonus", {userId, adWatched, ...result});
  return {success: true, serverTime: Date.now(), ...result};
});

/**
 * The top of the board, cached in the instance.
 *
 * This is the expensive read in the whole app - a hundred documents - and it
 * was being run on every return to Home by every user. The standings barely
 * move minute to minute, so it is fetched once a minute per instance and
 * shared by everyone who asks in between. Without this the leaderboard alone
 * exhausts the daily free read quota at roughly fifty daily users.
 */
interface CachedBoard {
  weekKey: number;
  readAt: number;
  entries: Array<{uid: string; name: string; xp: number}>;
}

let boardCache: CachedBoard | null = null;
const BOARD_CACHE_TTL_MS = 60_000;

async function cachedTopBoard(weekKey: number): Promise<CachedBoard["entries"]> {
  const now = Date.now();
  if (
    boardCache &&
    boardCache.weekKey === weekKey &&
    now - boardCache.readAt < BOARD_CACHE_TTL_MS
  ) {
    return boardCache.entries;
  }

  // Needs the (weekKey ASC, weeklyXp DESC) composite index in
  // firestore.indexes.json. The rank count below is deliberately ordered the
  // same way so both queries share it.
  const snapshot = await getFirestore()
    .collection(USERS_COLLECTION)
    .where(FIELD_WEEK_KEY, "==", weekKey)
    .orderBy(FIELD_WEEKLY_XP, "desc")
    .limit(LEADERBOARD_SIZE)
    .get();

  const entries = snapshot.docs.map((doc) => ({
    uid: doc.id,
    name: maskDisplayName(doc.get("displayName") as string | undefined),
    xp: Number(doc.get(FIELD_WEEKLY_XP) || 0),
  }));

  boardCache = {weekKey, readAt: now, entries};
  return entries;
}

/**
 * The weekly leaderboard: the top places, and where the caller sits.
 *
 * `full` decides how many places come back. Home asks for the podium, the
 * sheet asks for everything - the board is cached either way, so this is
 * payload rather than reads, but there is no reason to send a hundred rows to
 * draw three.
 *
 * Rank is a count query rather than a scan: "how many people are ahead of me"
 * costs the same whether the caller is twelfth or twenty-thousandth. Reading
 * the collection to find a position would not survive the first thousand
 * users.
 *
 * Both queries filter on weekKey. Without it, last week's figures would still
 * be sitting on the documents of everyone who has not played since, and they
 * would rank.
 */
export const getLeaderboard = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const full = request.data?.full === true;
  const firestore = getFirestore();
  const weekKey = utcWeekFor(Date.now());

  const [board, userDoc] = await Promise.all([
    cachedTopBoard(weekKey),
    firestore.collection(USERS_COLLECTION).doc(userId).get(),
  ]);

  // A stale week means the caller has not played this week, whatever figure
  // is still sitting on the document.
  const myXp = userDoc.get(FIELD_WEEK_KEY) === weekKey ?
    Number(userDoc.get(FIELD_WEEKLY_XP) || 0) :
    0;

  let myRank = 0;
  if (myXp > 0) {
    // One aggregation, billed per thousand index entries scanned rather than
    // per document ahead.
    const ahead = await firestore
      .collection(USERS_COLLECTION)
      .where(FIELD_WEEK_KEY, "==", weekKey)
      .where(FIELD_WEEKLY_XP, ">", myXp)
      // Ordering does not change what a count returns, but it does decide
      // which index serves the query. Descending reuses the index the board
      // itself needs; without it Firestore wants a second, ascending one -
      // and every extra composite index is write amplification on a document
      // that is written on every reward claim.
      .orderBy(FIELD_WEEKLY_XP, "desc")
      .count()
      .get();
    myRank = ahead.data().count + 1;
  }

  const visible = full ? board : board.slice(0, LEADERBOARD_PREVIEW_SIZE);

  return {
    serverTime: Date.now(),
    weekKey,
    // When the standings reset. Sent rather than derived on the client so the
    // countdown on the board agrees with the boundary the server enforces.
    weekEndsAt: weekEndMillis(weekKey),
    size: LEADERBOARD_SIZE,
    prizePool: totalWeeklyPrizePool(),
    myXp,
    // Zero means unranked - no play this week - which the client shows as a
    // prompt rather than as a position.
    myRank,
    myPrize: prizeForRank(myRank),
    full,
    entries: visible.map((entry, index) => ({
      rank: index + 1,
      name: entry.name,
      xp: entry.xp,
      prize: prizeForRank(index + 1),
      isMe: entry.uid === userId,
    })),
  };
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
    const xpBuffMultiplier = activeMultiplier(
      userDoc.get(FIELD_ACTIVE_XP_BUFF) as PointsBuff | undefined,
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
      activeXpMultiplier: xpBuffMultiplier,
      storedLevel: currentLevel,
    });

    const extraUpdates: Record<string, FieldValue | number | DailyStats> = {};
    // Daily goal counters. Written from inside the transaction that awards
    // the activity, so a goal can only advance when something was really
    // played or answered - never because a client said so.
    const todayUtc = utcDayFor(Date.now());
    const stats = statsForDay(
      userDoc.get(FIELD_DAILY_STATS) as Partial<DailyStats> | undefined,
      todayUtc
    );
    // Weekly leaderboard. Written here rather than in writeAward so it counts
    // play alone: streak and referral XP go through the same award path, and
    // counting those would let someone place by signing up friends.
    const weekKey = utcWeekFor(Date.now());
    extraUpdates[FIELD_WEEKLY_XP] = nextWeeklyXp(
      userDoc.get(FIELD_WEEK_KEY) as number | undefined,
      userDoc.get(FIELD_WEEKLY_XP) as number | undefined,
      weekKey,
      xpAward
    );
    extraUpdates[FIELD_WEEK_KEY] = weekKey;

    if (rewardSource === "GAME") stats.games += 1;
    if (rewardSource === "QUIZ") {
      stats.quizzes += 1;
      if (wasCorrect) stats.correct += 1;
    }
    // Set rather than increment: the whole map is replaced, which is also how
    // it resets when the day rolls over.
    extraUpdates[FIELD_DAILY_STATS] = stats;

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

      // The public feed entry is written in the same transaction as the
      // approval, so the two cannot disagree: nothing is paid out without
      // appearing here, and nothing appears here that was not paid out.
      //
      // Only a masked name, what was redeemed, and when. Never the uid, the
      // points, or the payout number - the whole reason this is a separate
      // collection from `redemptions`.
      const feedName = maskDisplayName(
        userDoc.get("displayName") as string | undefined
      );
      const feedTitle = String(redemptionDoc.get("optionTitle") || "");

      transaction.set(
        firestore.collection(PAYOUT_FEED_COLLECTION).doc(redemptionId),
        {
          name: feedName,
          optionTitle: feedTitle,
          approvedAt: FieldValue.serverTimestamp(),
        }
      );

      // Logged so an approval that produced no feed row can be told apart
      // from one that never reached this code at all.
      console.log("Payout feed entry queued", {
        redemptionId,
        name: feedName,
        optionTitle: feedTitle,
      });

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
