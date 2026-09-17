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
import {createHash} from "crypto";
import {
  DELETED_ACCOUNTS_COLLECTION,
  emailFingerprint,
  purgeAfterMillis,
  resolveDeletion,
} from "./economy/accountDeletion";
import {
  ADMIN_MESSAGE_MAX,
  FIELD_USER_MESSAGES_SINCE_REPLY,
  SUPPORT_MESSAGES_SUBCOLLECTION,
  SUPPORT_TICKETS_COLLECTION,
  SupportRefusal,
  TicketStatus,
  cleanMessage,
  isSupportCategory,
  messagesSentToday,
  resolveNewTicket,
  resolveUserReply,
  subjectFor,
} from "./economy/supportTickets";
import {
  buildSignature,
  offerwallTransactionId,
  resolveNetwork,
  validatePostback,
  OFFERWALL_SECRETS_COLLECTION,
  OFFERWALL_SECRETS_DOC,
  OFFERWALL_TRANSACTIONS_COLLECTION,
} from "./economy/offerwall";
import {
  maskDisplayName,
  PAYOUT_FEED_COLLECTION,
} from "./economy/payoutFeed";
import {
  buildSettlement,
  isRankedInWeek,
  isTournamentUnlocked,
  mergeSettlementBoard,
  prizeForRank,
  SettlementEntry,
  settlementCost,
  settlementWeekFor,
  withCallerRow,
  totalWeeklyPrizePool,
  utcWeekFor,
  weekEndMillis,
  weeklyRollover,
  FIELD_LAST_WEEKLY_XP,
  FIELD_LAST_WEEK_KEY,
  LEADERBOARD_PREVIEW_SIZE,
  LEADERBOARD_PRIZES,
  LEADERBOARD_SETTLEMENTS_COLLECTION,
  LEADERBOARD_SIZE,
  FIELD_TOURNAMENT_WEEK,
  TOURNAMENT_UNLOCK_LEVEL,
  weeklyXpGain,
} from "./economy/leaderboard";
import {
  resolveBonusXp,
  resolveGoalBonus,
  selectDailyGoals,
  statsForDay,
  DAILY_GOALS_CONFIG_DOC,
  MAX_DAILY_GOAL_BONUS_XP,
  DAILY_GOAL_POOL,
  GOAL_KINDS,
  DailyStats,
} from "./economy/dailyGoals";
import {
  resolveStreakClaim,
  resolveStreakReward,
  streakRewardForDay,
  utcDayFor,
  MAX_STREAK_DAY_XP,
  STREAK_CYCLE_DAYS,
  STREAK_REWARDS,
} from "./economy/streak";
import {
  REFERRAL_CODE_MAX_ATTEMPTS,
  buildNewUserProfile,
  generateReferralCode,
} from "./economy/signup";
import {
  GAME_PROFILES_SUBCOLLECTION,
  PLAYER_LINKS_COLLECTION,
  REDEMPTIONS_COLLECTION,
  REDEMPTION_OPTIONS_COLLECTION,
  RedemptionGame,
  playerLinkId,
  validateRedemption,
} from "./economy/redemption";
import {
  MAX_BUFF_DURATION_MS,
  MAX_BUFF_MULTIPLIER,
  PointsBuff,
  activeMultiplier,
  resolveBuffGrant,
} from "./economy/pointsBuff";
import {MAX_LEVEL, XP_THRESHOLDS, levelForXp} from "./economy/levelCurve";
import {
  PLAY_TUTORIAL_LEDGER_ID,
  PLAY_TUTORIAL_GAMES_REQUIRED,
  PLAY_TUTORIAL_QUIZZES_REQUIRED,
  playTutorialRequirementsMet,
  playTutorialTopUpXp,
} from "./economy/playTutorial";
import {
  GAME_XP_PER_SESSION_CAP,
  GAME_XP_SCORE_DIVISOR,
  LEVEL_UP_POINTS,
  ATTEMPTS_CONFIG_DOC,
  resolveBonusAttemptsCap,
  MAX_DAILY_GAME_SESSIONS,
  MAX_DAILY_QUIZ_ATTEMPTS,
  QUIZ_CORRECT_XP,
  QUIZ_INCORRECT_XP,
  REFERRAL_UNLOCK_LEVEL,
  REFERRER_LEVEL_REWARD_POINTS,
  REFERRER_REDEEM_REWARD_POINTS,
  RewardSource,
  attemptsAllowance,
  gameXpForScore,
  levelUpPointsForLevels,
  parseLevelRewards,
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
/**
 * Levels whose one-time star bonus has been EARNED but not yet released.
 *
 * An array of level numbers, appended to by writeAward with arrayUnion and
 * emptied one entry at a time by claimLevelReward - lowest first, so a player
 * sitting on levels 2, 3 and 4 claims 2, then 3, then 4 rather than cherry
 * picking the biggest. It is on the user document rather than derived from a
 * query over rewardEvents so the client learns what is waiting from the
 * snapshot it already holds, at no extra read.
 */
const FIELD_PENDING_LEVEL_REWARDS = "pendingLevelRewards";
/** Set once, by completePlayTutorial, together with its XP top-up. */
const FIELD_PLAY_TUTORIAL_COMPLETED = "playTutorialCompleted";
const FIELD_STREAK_COUNT = "streakCount";
const FIELD_LAST_STREAK_DAY = "lastStreakDayUtc";
// Tracked apart from the streak day, so the once-a-day gate is on the reward
// itself rather than inferred from the streak having moved.
const FIELD_LAST_STREAK_REWARD_DAY = "lastStreakRewardDayUtc";
// Per-day activity counters behind the daily goals. One map field rather than
// a subcollection: claimReward already reads and writes this document, so
// tracking costs no extra read.
const FIELD_DAILY_STATS = "dailyStats";
const FIELD_LAST_GOAL_BONUS_DAY = "lastGoalBonusDayUtc";
// The weekly leaderboard. Reset lazily by comparing weekKey rather than by a
// job that rewrites every user document at the boundary. The closing total is
// carried into FIELD_LAST_WEEKLY_XP on the way past, since the settlement for
// a week runs after users have already started playing the next one.
//
// Only XP earned after reaching TOURNAMENT_UNLOCK_LEVEL accrues, and only
// players stamped into the week (FIELD_TOURNAMENT_WEEK) are ranked or paid.
const FIELD_WEEKLY_XP = "weeklyXp";
const FIELD_WEEK_KEY = "weekKey";
// The last weekly prize this user won, for the app to congratulate them with.
//
// A SETTLEMENT USED TO BE SILENT. The Points landed, the balance changed, and
// nothing anywhere said a prize had been won - the one event in this economy
// that a user competed for all week was the only one that arrived without a
// word. This is what the client reads to say so.
//
// Written inside the transaction that pays, so it cannot exist without the
// Points having landed and the Points cannot land without it. It carries
// everything the dialog needs - the rank, the prize, the XP that earned it -
// because by the time anyone opens the app the live counters have moved on to
// a new week and none of it could be recovered from them.
const FIELD_LAST_LEADERBOARD_PRIZE = "lastLeaderboardPrize";
const FIELD_ACTIVE_BUFF = "activeBuff";
// Held apart from the Points buff rather than as one field with a kind, so a
// user can run both at once and neither grant can clobber the other.
const FIELD_ACTIVE_XP_BUFF = "activeXpBuff";
const FIELD_QUIZ_ATTEMPTS = "quiz_attempts";
// Games share FIELD_LAST_RESET_TIME with quizzes rather than carrying a day
// stamp of their own: one rollover, one re-stamp, and whichever activity the
// user does first on a new day resets both counters.
const FIELD_GAME_ATTEMPTS = "game_attempts";
const FIELD_LAST_RESET_TIME = "last_reset_time";
// Extra attempts bought with a rewarded ad, one counter per activity.
//
// These RAISE the ceiling; they do not refund a used attempt. Decrementing
// quiz_attempts/game_attempts was the obvious alternative and is worse in
// three ways: those counters mean "runs actually claimed today", which is the
// number any abuse analysis rests on, and a counter that also absorbed
// favours can no longer answer that; it underflows, so two grants at zero
// used is -2, silent permanent headroom; and it leaves no way to cap the
// favours separately from the usage.
//
// They ride FIELD_LAST_RESET_TIME with the counters they raise, so all four
// roll over together - see readDailyAttempts, which every reader goes through
// so that the staleness rule exists exactly once.
const FIELD_BONUS_QUIZ_ATTEMPTS = "bonus_quiz_attempts";
const FIELD_BONUS_GAME_ATTEMPTS = "bonus_game_attempts";
// Lifetime bonus attempts granted, never reset.
//
// This is the abuse signal, and it is deliberately NOT "grants that arrived
// without an ad": an honest client only ever calls after one, so a client
// that lies simply sends adWatched: true and such a count stays zero. A
// lifetime total is comparable against the ad network's own reported
// impressions, which is the one number a lying client cannot forge.
const FIELD_BONUS_GRANTS_TOTAL = "bonusAttemptsGranted";
const FIELD_HAS_USED_REFERRAL = "hasUsedReferral";
const REFERRAL_LIST_LIMIT = 50;
const FIELD_REFERRED_BY = "referredBy";
const FIELD_REFERRAL_CODE = "referralCode";
/**
 * The two referral milestones, marked on the REFEREE.
 *
 * On the referee rather than the referrer because the referee is the one
 * whose progress decides them, and because that is the document already open
 * in every transaction that could satisfy one. Each is the only thing making
 * its payout once-per-referee: both are written in the same transaction that
 * pays, so a second attempt cannot find either false.
 */
const FIELD_REFERRAL_LEVEL_PAID = "referralLevelRewardPaid";
const FIELD_REFERRAL_REDEEM_PAID = "referralRedeemRewardPaid";
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
 *
 * The reward table and the referral threshold ride along for the same
 * reason: the Level rewards screen lists what each level pays, and a second
 * copy of those numbers in Kotlin would go stale the first time the economy
 * is retuned - which is the exact failure the thresholds were published to
 * avoid. Neither is a secret; both are already visible in what the server
 * pays out.
 *
 * EDITING THIS DOCUMENT BY HAND DOES NOT CHANGE WHAT IS PAID. The server
 * reads LEVEL_UP_POINTS from the deployed code when it awards, so this copy
 * is what the app DISPLAYS. Retuning the economy means editing rewardConfig.ts
 * and redeploying; a console edit here would only make the ladder lie.
 *
 * So does the daily goal pool, and there it is load-bearing rather than
 * convenient. The client now derives today's three goals itself instead of
 * calling getDailyGoals on every return to Home, and the derivation has to
 * agree with this server EXACTLY - a client showing "play 8 games" while
 * claimDailyGoalBonus requires nine is a bonus that never pays and no error
 * that explains why. Publishing the pool means there is one array, here, and
 * retuning the day stays a single edit.
 */
async function publishLevelCurve(): Promise<void> {
  const ref = getFirestore().collection(CONFIG_COLLECTION).doc(LEVEL_CURVE_DOC);
  const existingRewards = parseLevelRewards((await ref.get()).get("levelRewards"), MAX_LEVEL);

  const payload: Record<string, unknown> = {
    maxLevel: MAX_LEVEL,
    thresholds: XP_THRESHOLDS,
    referralUnlockLevel: REFERRAL_UNLOCK_LEVEL,
    // The goal pool and the order of kinds. Order matters: selectDailyGoals
    // hashes the kind's INDEX, so a reordering here changes which goal each
    // user gets - and the client must hash the same index.
    dailyGoalPool: DAILY_GOAL_POOL,
    dailyGoalKinds: GOAL_KINDS,
    updatedAt: FieldValue.serverTimestamp(),
  };

  // SEEDED ONCE, then never written again - the one field in this document
  // the CONSOLE owns rather than the code.
  //
  // Everything else here is derived from constants and rewritten on every
  // cold start, so editing it in the console would be undone by the next
  // deploy. Level rewards are meant to be retuned without one, so they get
  // the opposite treatment: written only when the document has none.
  //
  // Keys are level numbers; Firestore stores them as strings.
  if (!existingRewards) payload.levelRewards = LEVEL_UP_POINTS;

  await ref.set(payload, {merge: true});
  levelCurvePublishedByThisInstance = true;
  console.log("Level curve published", {
    maxLevel: MAX_LEVEL,
    levels: XP_THRESHOLDS.length,
    seededRewards: !existingRewards,
  });
}

/**
 * The live level-reward table: what each level actually pays.
 *
 * Read from config/levelCurve rather than straight from LEVEL_UP_POINTS, so
 * the numbers can be retuned in the Firebase console without a deploy - which
 * is the whole reason publishLevelCurve seeds the field instead of
 * republishing it. The code table is the seed and the fallback, never the
 * last word once the document exists.
 *
 * Cached in-process on a short TTL because [writeAward] runs inside a
 * transaction and cannot await anything: the callables that can actually
 * level somebody up refresh this first, and the transaction then reads it
 * synchronously. A warm instance pays at most one document read per TTL
 * window however many awards pass through it.
 *
 * Seeded with the deployed table at module load so it is NEVER empty - a cold
 * instance whose first refresh fails pays the deployed numbers rather than
 * paying nothing at all.
 */
const LEVEL_REWARDS_TTL_MS = 5 * 60 * 1000;
let cachedLevelRewards: {table: Record<number, number>; loadedAt: number} = {
  table: LEVEL_UP_POINTS,
  loadedAt: 0,
};

/** Refreshes [cachedLevelRewards] if its TTL has expired. Never throws. */
async function ensureLevelRewardsFresh(): Promise<void> {
  if (Date.now() - cachedLevelRewards.loadedAt < LEVEL_REWARDS_TTL_MS) return;

  try {
    const snap = await getFirestore()
      .collection(CONFIG_COLLECTION).doc(LEVEL_CURVE_DOC).get();
    const parsed = parseLevelRewards(snap.get("levelRewards"), MAX_LEVEL);
    if (!parsed) {
      console.warn("Level rewards missing or invalid - using the deployed table");
    }
    cachedLevelRewards = {table: parsed ?? LEVEL_UP_POINTS, loadedAt: Date.now()};
  } catch (error) {
    // Keep whatever is cached. Paying the deployed numbers is right; failing
    // somebody's claim because a config read blipped is not.
    console.error("Level rewards refresh failed", error);
  }
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

/**
 * Whether the caller is signed in as a guest (Firebase Anonymous Auth).
 *
 * Read from the verified ID token, never from the user document or the
 * request: the token's sign-in provider is set by Firebase and cannot be
 * edited by a client. Linking Google to a guest issues a new token with the
 * Google provider, so a linked account stops being a guest here at once.
 *
 * Guests may play (games, quizzes, streak, goals). Anything with value that
 * leaves the app - redeeming, referral codes, the weekly tournament - needs a
 * Google account; see requireLinkedAccount.
 */
function isGuest(request: CallableRequest): boolean {
  return request.auth?.token?.firebase?.sign_in_provider === "anonymous";
}

/** Refuses guests. The app shows a "log in with Google" prompt before this. */
function requireLinkedAccount(request: CallableRequest): void {
  if (isGuest(request)) {
    throw new functions.https.HttpsError("permission-denied", "guest_account");
  }
}

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

  const guest = isGuest(request);
  // A guest has no name to take; linking Google later fills it in.
  const displayName = String(request.data.displayName || "").trim() || (guest ? "Guest" : "");
  const androidId = String(request.data.androidId || "").trim();
  // Trust the token for the email, not the request body.
  const email = String(request.auth.token.email || request.data.email || "");

  // Has this device held an account before? Checked here because the client
  // could simply not ask. Requires reading across users, which only the Admin
  // SDK can do - another reason this belongs on the server.
  //
  // A DELETED account counts too. Otherwise deleting and signing up again
  // would reopen the referral window - the exact farming the tombstone in
  // deletedAccounts is kept for (see economy/accountDeletion.ts).
  let hasUsedReferral = false;
  if (androidId) {
    const [priorAccounts, priorDeleted] = await Promise.all([
      firestore.collection(USERS_COLLECTION)
        .where("androidId", "==", androidId).limit(1).get(),
      firestore.collection(DELETED_ACCOUNTS_COLLECTION)
        .where("androidId", "==", androidId).limit(1).get(),
    ]);
    hasUsedReferral = !priorAccounts.empty || !priorDeleted.empty;
  }
  if (!hasUsedReferral) {
    const fingerprint = emailFingerprint(email);
    if (fingerprint) {
      const deletedEmail = await firestore.collection(DELETED_ACCOUNTS_COLLECTION)
        .where("emailHash", "==", fingerprint).limit(1).get();
      hasUsedReferral = !deletedEmail.empty;
    }
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
      // Display only - the server decides guest status from the ID token.
      // Cleared by completeGoogleLink.
      isGuest: guest,
      // The discounted first redeem is no longer withheld here by device.
      // Every account sees the offer; the admin tool shows how many first
      // redeems a device has already had (listRedemptions) and the decision
      // is made by hand at approval, where a farmer learns nothing about
      // which signal gave them away. The per-game-UID rule still applies.
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

  console.log("User document created", {userId, repeatDeviceOrEmail: hasUsedReferral});
  return {success: true, created: true, referralCode};
});

// checkAndResetQuizAttempts was REMOVED, and this note is here so it does not
// come back.
//
// It reset quiz_attempts and re-stamped last_reset_time on its own, outside
// the award funnel - and quizzes and games SHARE that stamp. Stamping today
// while leaving game_attempts alone told claimReward the day had already
// rolled over, so yesterday's game count was read as today's. The client
// applies the same stamp rule for display, so the Games card agreed. Since
// MainActivity called this on every cold start, anyone who had ever used all
// ten game runs was locked out of games permanently: the counter could never
// reach a claim that would reset it.
//
// Nothing needs it now. The rollover happens inside the claimReward
// transaction - the moment it is actually enforced - and the client reads
// both counters straight off the user snapshot it already holds.

/**
 * Finishes linking Google to a guest account.
 *
 * The link itself happens on the client (FirebaseUser.linkWithCredential),
 * which keeps the same uid - so every star, level and streak is already on
 * this account. This only brings the user document up to date: it is no
 * longer a guest, and it gains the email and name the Google account carries.
 *
 * The token must already show the Google provider; a guest calling this is
 * refused, so it cannot be used to flip the display flag without linking.
 */
export const completeGoogleLink = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (isGuest(request)) {
    throw new functions.https.HttpsError("failed-precondition", "not_linked");
  }

  const userRef = getFirestore().collection(USERS_COLLECTION).doc(request.auth.uid);
  const userDoc = await userRef.get();
  if (!userDoc.exists) {
    throw new functions.https.HttpsError("not-found", "User document not found");
  }

  const email = String(request.auth.token.email || "");
  const googleName = String(request.auth.token.name || "").trim();
  const currentName = String(userDoc.get("displayName") || "");
  // A name the player never chose ("Guest") is replaced; anything else stays.
  const takeName = googleName !== "" && (currentName === "" || currentName === "Guest");

  await userRef.update({
    isGuest: false,
    ...(email ? {email} : {}),
    ...(takeName ? {displayName: googleName} : {}),
    linkedAt: FieldValue.serverTimestamp(),
  });

  return {success: true, displayName: takeName ? googleName : currentName};
});

/**
 * Applies an award to the transaction: the user's points/xp/level fields, the
 * ledger entry, and the level-up bonus for every level the XP gain crossed.
 *
 * LEVEL-UP BONUSES ARE LOCKED HERE, NOT PAID. Crossing the level earns the
 * stars; a rewarded ad releases them (claimLevelReward). So this writes two
 * things and credits nothing:
 *
 *   * a `levelup:<level>` ledger entry per level crossed, status "locked",
 *     carrying the amount as it stood at the moment it was earned, and
 *   * the level number onto [FIELD_PENDING_LEVEL_REWARDS], the user's queue
 *     of unclaimed levels.
 *
 * THE QUEUE IS AN arrayUnion, WHICH IS THE WHOLE REASON IT IS AN ARRAY. It
 * needs no prior value, so writeAward does not have to read the user document
 * (several of its eight callers do not hold one), it creates the field on
 * first use rather than needing a migration for accounts that predate this,
 * and appending a level already in it is a no-op - so a retried transaction
 * cannot queue the same level twice. The client gets the pending set for free
 * on the snapshot it already listens to, which is what keeps the "you have
 * rewards waiting" prompt off the read budget entirely.
 *
 * Points from the award ITSELF are still a single increment: two
 * FieldValue.increment writes to one field in one transaction would clobber
 * each other rather than add up.
 */
function writeAward(
  transaction: FirebaseFirestore.Transaction,
  userRef: FirebaseFirestore.DocumentReference,
  ledgerRef: FirebaseFirestore.DocumentReference,
  award: ReturnType<typeof buildAward>,
  extraUpdates: Record<string, unknown> = {}
): {milestonePoints: number; milestoneLevels: number[]} {
  const milestones = levelUpPointsForLevels(
    award.level.levelsCrossed,
    cachedLevelRewards.table
  );
  // What was LOCKED, not what was credited. Callers pass it back to the
  // client so the level-up moment can say what is now waiting to be claimed.
  const milestonePoints = milestones.reduce((sum, m) => sum + m.points, 0);

  const updateData: Record<string, unknown> = {
    ...award.userUpdate,
    ...extraUpdates,
  };

  if (award.pointsAwarded !== 0) {
    updateData[FIELD_POINTS] = FieldValue.increment(award.pointsAwarded);
  }

  if (milestones.length > 0) {
    updateData[FIELD_PENDING_LEVEL_REWARDS] = FieldValue.arrayUnion(
      ...milestones.map((m) => m.level)
    );
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
    console.log("Level-up rewards locked", {
      levels: milestones.map((m) => m.level),
      points: milestonePoints,
    });
  }

  return {milestonePoints, milestoneLevels: milestones.map((m) => m.level)};
}

interface PendingReferrerPayout {
  milestone: ReferralMilestone;
  referrerRef: FirebaseFirestore.DocumentReference;
  referrerLedgerRef: FirebaseFirestore.DocumentReference;
  refereeId: string;
  currentPoints: number;
  currentXp: number;
  currentLevel: number;
}

/**
 * One referral milestone, and what it pays.
 *
 * Two of them exist and they are deliberately very different prices - see
 * REFERRER_LEVEL_REWARD_POINTS in rewardConfig. Everything else about them is
 * identical, which is why they share one read/pay pair rather than having a
 * copy each: the same once-per-referee flag discipline, the same
 * deterministic ledger id, the same write path.
 */
interface ReferralMilestone {
  /** Flag on the REFEREE that marks this milestone as already paid. */
  paidField: string;
  /** Prefix of the deterministic ledger id written on the REFERRER. */
  ledgerPrefix: string;
  points: number;
}

const REFERRAL_LEVEL_MILESTONE: ReferralMilestone = {
  paidField: FIELD_REFERRAL_LEVEL_PAID,
  ledgerPrefix: "referral_level",
  points: REFERRER_LEVEL_REWARD_POINTS,
};

const REFERRAL_REDEEM_MILESTONE: ReferralMilestone = {
  paidField: FIELD_REFERRAL_REDEEM_PAID,
  ledgerPrefix: "referral_redeem",
  points: REFERRER_REDEEM_REWARD_POINTS,
};

/**
 * Reads the referrer's document if [milestone] is now owed on this referee.
 *
 * Read-only: every write happens in payReferrer, so callers can satisfy
 * Firestore's all-reads-before-writes rule.
 */
async function readReferrerFor(
  transaction: FirebaseFirestore.Transaction,
  userDoc: FirebaseFirestore.DocumentSnapshot,
  milestone: ReferralMilestone
): Promise<PendingReferrerPayout | null> {
  const referredBy = userDoc.get(FIELD_REFERRED_BY) as string | undefined;
  if (!referredBy) return null;

  // The ONLY thing that makes this once-per-referee. Set on the referee in
  // the same transaction that pays the referrer, so a second attempt cannot
  // find it false.
  if (userDoc.get(milestone.paidField) === true) return null;

  const referrerRef = getFirestore().collection(USERS_COLLECTION).doc(referredBy);
  const referrerDoc = await transaction.get(referrerRef);
  if (!referrerDoc.exists) {
    console.log("Referral reward skipped: referrer no longer exists", {referredBy});
    return null;
  }

  return {
    milestone,
    referrerRef,
    // Deterministic per referee AND per milestone, so a retry can never pay
    // the referrer twice and the two milestones can never collide.
    referrerLedgerRef: referrerRef
      .collection(REWARD_EVENTS_SUBCOLLECTION)
      .doc(`${milestone.ledgerPrefix}:${userDoc.id}`),
    refereeId: userDoc.id,
    currentPoints: Number(referrerDoc.get(FIELD_POINTS) || 0),
    currentXp: Number(referrerDoc.get(FIELD_XP) || 0),
    currentLevel: Number(referrerDoc.get(FIELD_LEVEL) || 1),
  };
}

/**
 * The level milestone: the referee has reached REFERRAL_UNLOCK_LEVEL.
 *
 * AT OR ABOVE THE LEVEL - not only on the exact crossing. The crossing can
 * happen somewhere this function never runs (claimDailyStreak and
 * claimDailyGoalBonus both award XP without calling it), and a reward keyed
 * to the crossing would simply be lost when that happened. Testing the
 * threshold costs nothing, because the paid flag above is what prevents a
 * second payout - so the worst case is that the reward lands on the referee's
 * next quiz or game rather than on the streak that actually took them there.
 *
 * The level is computed from XP rather than read from the `level` field:
 * `level` is a cache that lags behind the XP it is derived from until the
 * next award repairs it, and a milestone keyed to a stale cache would pay
 * late for no reason.
 */
async function readReferrerForLevelUnlock(
  transaction: FirebaseFirestore.Transaction,
  userDoc: FirebaseFirestore.DocumentSnapshot,
  currentXp: number,
  xpGain: number
): Promise<PendingReferrerPayout | null> {
  if (xpGain <= 0) return null;
  if (levelForXp(currentXp + xpGain) < REFERRAL_UNLOCK_LEVEL) return null;

  return readReferrerFor(transaction, userDoc, REFERRAL_LEVEL_MILESTONE);
}

/**
 * The redeem milestone: the referee has just placed their first FULL-PRICE
 * order.
 *
 * The discounted first-redeem offer deliberately does not count. It is sold
 * at a loss to get somebody through the flow once, so paying an acquisition
 * bonus on top of it would mean paying twice for the same event - and it is
 * the cheapest order in the app, which makes it exactly what a farmed account
 * would use. A full-price order is somebody spending stars they actually
 * earned.
 */
async function readReferrerForRedeemUnlock(
  transaction: FirebaseFirestore.Transaction,
  userDoc: FirebaseFirestore.DocumentSnapshot,
  usedFirstRedeem: boolean
): Promise<PendingReferrerPayout | null> {
  if (usedFirstRedeem) return null;

  return readReferrerFor(transaction, userDoc, REFERRAL_REDEEM_MILESTONE);
}

/** Applies the payout prepared by one of the reads above. Writes only. */
function payReferrer(
  transaction: FirebaseFirestore.Transaction,
  refereeRef: FirebaseFirestore.DocumentReference,
  pending: PendingReferrerPayout | null
): void {
  if (!pending) return;

  // Stars only - no XP. See the note in rewardConfig on why referral XP was
  // removed: it levelled the referrer up and so paid them level rewards too.
  const award = buildAward(pending.currentPoints, pending.currentXp, {
    source: "REFERRAL_REFERRER",
    basePoints: pending.milestone.points,
    baseXp: 0,
    metadata: {
      refereeId: pending.refereeId,
      milestone: pending.milestone.ledgerPrefix,
    },
    storedLevel: pending.currentLevel,
  });

  // Through writeAward rather than a direct points write, so the referrer's
  // ledger entry is built the same way every other award is. It crosses no
  // level (the award carries no XP), so the reward table is never consulted.
  writeAward(transaction, pending.referrerRef, pending.referrerLedgerRef, award);
  transaction.update(refereeRef, {[pending.milestone.paidField]: true});

  console.log("Referral reward applied", {
    refereeId: pending.refereeId,
    milestone: pending.milestone.ledgerPrefix,
    pointsAwarded: award.pointsAwarded,
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
 * Advances the daily streak and pays today's reward.
 *
 * Two once-per-day gates: the STREAK advances on the first call of a new day,
 * and the REWARD pays once a day. No ad is involved any more - the reward is
 * XP, paid at once, and the app then offers a rewarded ad to double it through
 * claimDoubleXp against the `eventId` returned here.
 *
 * The decision and the write share one transaction, so two taps racing cannot
 * both pass either gate.
 */
export const claimDailyStreak = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  // Refreshed before the transaction, because writeAward reads the reward
  // table synchronously from inside one. See ensureLevelRewardsFresh.
  await ensureLevelRewardsFresh();

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
      todayUtc
    );

    const day = claim.day;
    const updates: Record<string, unknown> = {};
    if (claim.status === "claimed") {
      updates[FIELD_STREAK_COUNT] = day;
      updates[FIELD_LAST_STREAK_DAY] = todayUtc;
    }

    if (!reward.pay) {
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
    // the same document rather than paying twice. Also the handle the
    // double is claimed against.
    const eventRef = userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`streak:${todayUtc}`);
    writeAward(
      transaction,
      userRef,
      eventRef,
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
      eventId: eventRef.id,
    };
  });

  console.log("Daily streak", {userId, ...result});
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
 * The daily ad-bonus cap, as configured in config/attempts.maxBonusAttempts.
 *
 * Read on every claim and every grant, so it is cached in the instance for a
 * minute like the goal bonus: at most one read a minute per instance on the
 * hottest path in the app. A console edit therefore takes up to a minute to
 * reach a warm instance. A failed read enforces the deployed default rather
 * than refusing claims.
 */
let bonusCapCache: {cap: number; readAt: number} | null = null;
const BONUS_CAP_TTL_MS = 60_000;

async function configuredBonusCap(): Promise<number> {
  const now = Date.now();
  if (bonusCapCache && now - bonusCapCache.readAt < BONUS_CAP_TTL_MS) {
    return bonusCapCache.cap;
  }

  let cap: number;
  try {
    const snapshot = await getFirestore()
      .collection(CONFIG_COLLECTION)
      .doc(ATTEMPTS_CONFIG_DOC)
      .get();
    cap = resolveBonusAttemptsCap(snapshot.get("maxBonusAttempts"));
  } catch (error) {
    console.error("Attempts config unreadable", error);
    cap = resolveBonusAttemptsCap(undefined);
  }

  bonusCapCache = {cap, readAt: now};
  return cap;
}

let goalConfigCache: {xp: number; readAt: number} | null = null;
const GOAL_CONFIG_TTL_MS = 60_000;

/** config/dailyGoals.bonusXp, cached for a minute per instance. */
async function configuredGoalBonus(): Promise<number> {
  const now = Date.now();
  if (goalConfigCache && now - goalConfigCache.readAt < GOAL_CONFIG_TTL_MS) {
    return goalConfigCache.xp;
  }

  let xp: number;
  try {
    const snapshot = await getFirestore()
      .collection(CONFIG_COLLECTION)
      .doc(DAILY_GOALS_CONFIG_DOC)
      .get();
    xp = resolveBonusXp(snapshot.get("bonusXp"));
  } catch (error) {
    // A config read that fails must not stop the goals paying out.
    console.error("Daily goal config unreadable", error);
    xp = resolveBonusXp(undefined);
  }

  goalConfigCache = {xp, readAt: now};
  return xp;
}

// getDailyGoals was REMOVED. DailyGoalEngine on the client derives the same
// three goals from the pool published on config/levelCurve and the dailyStats
// map the snapshot listener already delivers, so the callable answered a
// question nobody was asking any more - and a second implementation of the
// selection rule is exactly the thing that drifts out of step with the one
// that decides whether the bonus pays.

/**
 * Pays the XP bonus for finishing all three of today's goals.
 *
 * Every condition is re-derived here from the counters and the day. The client
 * is told what it may do, never trusted about what it has done.
 *
 * No ad gates the claim; the app offers one afterwards to double it through
 * claimDoubleXp against the returned `eventId`.
 */
export const claimDailyGoalBonus = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  // Refreshed before the transaction, because writeAward reads the reward
  // table synchronously from inside one. See ensureLevelRewardsFresh.
  await ensureLevelRewardsFresh();

  // Read outside the transaction: it is not part of what has to stay
  // consistent with the user document, and pulling it in would widen the read
  // set for no reason.
  const bonusXp = await configuredGoalBonus();

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
      stats
    );

    if (!decision.pay) {
      return {claimed: false as const, reason: decision.reason, xpAwarded: 0};
    }

    const award = buildAward(
      Number(userDoc.get(FIELD_POINTS) || 0),
      Number(userDoc.get(FIELD_XP) || 0),
      {
        source: "MISSION",
        basePoints: 0,
        baseXp: bonusXp,
        metadata: {goals: goals.map((goal) => goal.id), dayUtc: todayUtc},
        storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
      }
    );

    // Keyed by the day, so a retry writes the same document rather than
    // paying twice. Also the handle the double is claimed against.
    const eventRef = userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`goals:${todayUtc}`);
    writeAward(
      transaction,
      userRef,
      eventRef,
      award,
      {[FIELD_LAST_GOAL_BONUS_DAY]: todayUtc}
    );

    return {
      claimed: true as const,
      xpAwarded: award.xpAwarded,
      eventId: eventRef.id,
    };
  });

  console.log("Daily goal bonus", {userId, ...result});
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

  // Ranked players only, and only those who have scored. A zero row is
  // dropped because buildSettlement pays nobody on zero.
  //
  // weekKey is filtered as well as tournamentWeek even though, for the
  // running week, one implies the other (a scoring claim stamps both). The
  // settlement's live query needs both filters, and sharing one shape means
  // sharing the one (tournamentWeek, weekKey, weeklyXp DESC) composite index.
  const snapshot = await getFirestore()
    .collection(USERS_COLLECTION)
    .where(FIELD_TOURNAMENT_WEEK, "==", weekKey)
    .where(FIELD_WEEK_KEY, "==", weekKey)
    .where(FIELD_WEEKLY_XP, ">", 0)
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
 * How many of this week's ranked players have more weekly XP than [xp].
 *
 * A count query rather than a scan: billed per thousand index entries, so it
 * costs the same whether the caller is twelfth or twenty-thousandth. Ordered
 * descending only so it is served by the board's own index - ordering does not
 * change a count, but a second, ascending index would be write amplification
 * on a document written on every reward claim.
 */
async function entrantsAhead(weekKey: number, xp: number): Promise<number> {
  const ahead = await getFirestore()
    .collection(USERS_COLLECTION)
    .where(FIELD_TOURNAMENT_WEEK, "==", weekKey)
    .where(FIELD_WEEK_KEY, "==", weekKey)
    .where(FIELD_WEEKLY_XP, ">", xp)
    .orderBy(FIELD_WEEKLY_XP, "desc")
    .count()
    .get();
  return ahead.data().count;
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
 *
 * A caller below TOURNAMENT_UNLOCK_LEVEL is told the board is locked and is
 * never placed on it; they have no weekly XP to rank, because none accrues
 * before unlocking.
 */
export const getLeaderboard = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const full = request.data?.full === true;
  const firestore = getFirestore();
  const weekKey = utcWeekFor(Date.now());

  const [cachedBoard, userDoc] = await Promise.all([
    cachedTopBoard(weekKey),
    firestore.collection(USERS_COLLECTION).doc(userId).get(),
  ]);

  // From XP, not the cached `level` field, which lags until the next award.
  const unlocked = isTournamentUnlocked(Number(userDoc.get(FIELD_XP) || 0));
  const ranked = unlocked && isRankedInWeek(
    userDoc.get(FIELD_TOURNAMENT_WEEK) as number | undefined,
    weekKey
  );
  // A stale week means the caller has not scored this week, whatever figure
  // is still sitting on the document.
  const myXp = ranked && userDoc.get(FIELD_WEEK_KEY) === weekKey ?
    Number(userDoc.get(FIELD_WEEKLY_XP) || 0) :
    0;

  // The caller's own row from the document just read, not from the cache - so
  // a finished quiz shows on the board at once. See withCallerRow.
  const board = withCallerRow(
    cachedBoard,
    userId,
    ranked ?
      {
        uid: userId,
        name: maskDisplayName(userDoc.get("displayName") as string | undefined),
        xp: myXp,
      } :
      null
  );

  let myRank = 0;
  if (myXp > 0) {
    // Position in the board first, which costs nothing - the board is already
    // in hand - and, more importantly, is the rank the SETTLEMENT will pay.
    //
    // The count query below answers "how many people are ahead of me", which
    // gives every tied player the same rank. That is the friendlier reading,
    // and it is the wrong one to show anybody who is about to be paid by
    // rank: two players tied on 400 XP would both be told they were second
    // and only one of them would receive second prize. Inside the board the
    // two questions have to give the same answer, so this one wins.
    const seat = board.findIndex((entry) => entry.uid === userId);
    if (seat >= 0) {
      myRank = seat + 1;
    } else {
      // Outside the board, where no prize is at stake and the count is the
      // only way to answer at all.
      myRank = (await entrantsAhead(weekKey, myXp)) + 1;
    }
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
    // The whole prize table, independent of who is on the board. The Prizes
    // tab used to rebuild the bands from the rows on the board, so a week with
    // three players showed three places' worth of prizes and hid the rest.
    prizeBands: LEADERBOARD_PRIZES,
    // Whether the caller takes part at all, and the level that decides it.
    unlocked,
    unlockLevel: TOURNAMENT_UNLOCK_LEVEL,
    // This week's XP as the board counts it: only what was earned after
    // unlocking.
    myXp,
    // Zero means unranked - locked, or unlocked and not yet scored this week.
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
 * Decides one week's standings, once, and records them before anything is paid.
 *
 * Two queries because last week's total lives in two places by then: on the
 * live counters of everyone who has not played since it ended, and on the
 * carried ones of everyone who has. Each is capped at LEADERBOARD_SIZE, which
 * is safe - the top thirty of the union cannot contain a row that was outside
 * the top thirty of the list it came from.
 *
 * The marker is written `pending` BEFORE the first transaction runs, so the
 * ranking survives a crash midway through paying it out. A settlement that
 * dies here, before any prize has moved, simply recomputes on the next run.
 */
async function freezeBoard(
  markerRef: FirebaseFirestore.DocumentReference,
  weekKey: number
): Promise<SettlementEntry[]> {
  const users = getFirestore().collection(USERS_COLLECTION);

  // The first of these is the same query, and the same composite index, the
  // live board uses - so what was on screen all week is what gets paid.
  //
  // RANKED PLAYERS ONLY, in both halves. The live half says so with the
  // tournamentWeek filter; here weekKey genuinely matters as well, because a
  // player who has since played into the new week still has tournamentWeek
  // naming this one while weeklyXp has moved on. The carried half needs no
  // filter: weeklyRollover only ever carries a week the player was ranked in.
  const [live, carried] = await Promise.all([
    users
      .where(FIELD_TOURNAMENT_WEEK, "==", weekKey)
      .where(FIELD_WEEK_KEY, "==", weekKey)
      .orderBy(FIELD_WEEKLY_XP, "desc")
      .limit(LEADERBOARD_SIZE)
      .get(),
    users
      .where(FIELD_LAST_WEEK_KEY, "==", weekKey)
      .orderBy(FIELD_LAST_WEEKLY_XP, "desc")
      .limit(LEADERBOARD_SIZE)
      .get(),
  ]);

  const payouts = buildSettlement(
    mergeSettlementBoard(
      live.docs.map((doc) => ({
        uid: doc.id,
        weeklyXp: Number(doc.get(FIELD_WEEKLY_XP) || 0),
      })),
      carried.docs.map((doc) => ({
        uid: doc.id,
        weeklyXp: Number(doc.get(FIELD_LAST_WEEKLY_XP) || 0),
      }))
    )
  );

  console.log("Weekly leaderboard settling", {
    weekKey,
    live: live.size,
    carried: carried.size,
    winners: payouts.length,
    cost: settlementCost(payouts),
  });

  await markerRef.set(
    {
      weekKey,
      status: "pending",
      frozenAt: FieldValue.serverTimestamp(),
      winners: payouts.length,
      cost: settlementCost(payouts),
      // The whole board as it will be paid, so a query about last week never
      // has to be answered from user documents that have since moved on - and
      // so a retry pays the ranking this attempt decided, not a new one.
      entries: payouts,
    },
    {merge: true}
  );

  return payouts;
}

/** The ranking a previous attempt froze. Authoritative, empty week included. */
function frozenBoard(
  marker: FirebaseFirestore.DocumentSnapshot,
  weekKey: number
): SettlementEntry[] {
  const entries = marker.get("entries");
  const payouts: SettlementEntry[] = Array.isArray(entries) ? entries : [];

  console.log("Weekly leaderboard resuming a frozen board", {
    weekKey,
    winners: payouts.length,
  });

  return payouts;
}

/**
 * Pays out one finished week of the leaderboard.
 *
 * The board has been promising prizes since it shipped and nothing has ever
 * credited them; this is what makes that promise true. Four things it has to
 * get right, because this is the one path that hands out Points nobody
 * earned through an activity:
 *
 *   1. IDEMPOTENT. Schedulers retry, and a retry that pays twice is money
 *      gone. Each winner's ledger entry is `leaderboard:{weekKey}`, and the
 *      transaction refuses to pay when it already exists. The marker document
 *      is a shortcut, not the guarantee - it saves reading thirty user
 *      documents to be told there is nothing to do.
 *   2. BOUNDED. buildSettlement pays by position, capped at LEADERBOARD_SIZE,
 *      so a week costs at most totalWeeklyPrizePool() however many people
 *      played or how many of them tied.
 *   3. SETTLES THE WEEK THAT ENDED, not "the week the trigger implies". A job
 *      that fires late, or is run by hand days afterwards, still pays the
 *      same week.
 *   4. PARTIAL PROGRESS SURVIVES. One transaction per winner rather than one
 *      for all thirty: a single transaction spanning thirty user documents
 *      contends with live play and gets retried whole, and a failure halfway
 *      through must not roll back prizes that already landed.
 *   5. THE BOARD IS FROZEN BEFORE A PENNY MOVES. The two problems that come
 *      from reading a live collection at settlement time, and what answers
 *      them:
 *
 *      THE BOARD MOVES UNDER A RETRY. A first attempt that pays fifteen
 *      winners and then dies used to leave the rest to a second attempt that
 *      re-read the collection - by which time the standings had changed, so
 *      the two halves of one settlement could rank the same week differently
 *      and hand a prize to somebody the first half had already ranked
 *      elsewhere. Per-user idempotency cannot see that: it stops a user being
 *      paid twice, not the week being ranked twice. So the ranking is decided
 *      once, written to the marker as `pending`, and every later attempt pays
 *      from that record instead of asking the collection again.
 *
 *      THE WINNERS HAVE ALREADY MOVED ON. The live counters are overwritten
 *      the moment somebody plays in the new week, and this job runs five
 *      minutes into it - so a query for last week silently omits anyone who
 *      opened the app after the reset, which is exactly the population most
 *      likely to have been at the top. The rollover preserves each closing
 *      total under lastWeekKey/lastWeeklyXp, and the board is the merge of
 *      both queries. Reading only the live one paid the runner-up.
 */
async function settleLeaderboardWeek(weekKey: number): Promise<{
  weekKey: number;
  alreadySettled: boolean;
  winners: number;
  paid: number;
  pointsPaid: number;
}> {
  const firestore = getFirestore();
  const markerRef = firestore
    .collection(LEADERBOARD_SETTLEMENTS_COLLECTION)
    .doc(String(weekKey));

  const marker = await markerRef.get();
  if (marker.get("status") === "complete") {
    console.log("Weekly leaderboard already settled", {weekKey});
    return {
      weekKey,
      alreadySettled: true,
      winners: Number(marker.get("winners") || 0),
      paid: 0,
      pointsPaid: 0,
    };
  }

  const payouts = marker.get("status") === "pending" ?
    frozenBoard(marker, weekKey) :
    await freezeBoard(markerRef, weekKey);

  let paid = 0;
  let pointsPaid = 0;

  // One stamp for the whole settlement rather than one per winner, so every
  // winner's announcement is dated the moment the week was paid - not the
  // moment their particular transaction happened to commit.
  const settledAtMillis = Date.now();

  for (const payout of payouts) {
    const userRef = firestore.collection(USERS_COLLECTION).doc(payout.uid);
    const ledgerRef = userRef
      .collection(REWARD_EVENTS_SUBCOLLECTION)
      .doc(`leaderboard:${weekKey}`);

    const applied = await firestore.runTransaction(async (transaction) => {
      const [userDoc, ledgerDoc] = await Promise.all([
        transaction.get(userRef),
        transaction.get(ledgerRef),
      ]);

      if (!userDoc.exists) return false;
      // The real guard. A deterministic id means a re-run finds the entry it
      // wrote last time and pays nothing, whatever the marker says.
      if (ledgerDoc.exists) return false;

      const award = buildAward(
        Number(userDoc.get(FIELD_POINTS) || 0),
        Number(userDoc.get(FIELD_XP) || 0),
        {
          source: "LEADERBOARD",
          basePoints: payout.points,
          // Prizes pay Points only. Awarding XP here would feed the next
          // week's board with last week's result.
          baseXp: 0,
          metadata: {
            weekKey,
            rank: payout.rank,
            weeklyXp: payout.weeklyXp,
          },
          storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
        }
      );

      // The announcement rides the same write as the payment. There is no
      // second path that could leave a user congratulated but unpaid, or paid
      // and never told.
      writeAward(transaction, userRef, ledgerRef, award, {
        [FIELD_LAST_LEADERBOARD_PRIZE]: {
          weekKey,
          rank: payout.rank,
          points: payout.points,
          weeklyXp: payout.weeklyXp,
          settledAtMillis,
        },
      });
      return true;
    });

    if (applied) {
      paid++;
      pointsPaid += payout.points;
    }
  }

  // `entries` is already on the document - it was written when the board was
  // frozen - so this only records what became of it. Merged rather than set,
  // for that reason: rewriting the board here would let a completion overwrite
  // the very record the retry path depends on.
  await markerRef.set(
    {
      weekKey,
      status: "complete",
      settledAt: FieldValue.serverTimestamp(),
      winners: payouts.length,
      paid,
      pointsPaid,
    },
    {merge: true}
  );

  console.log("Weekly leaderboard settled", {
    weekKey,
    winners: payouts.length,
    paid,
    pointsPaid,
  });

  return {
    weekKey,
    alreadySettled: false,
    winners: payouts.length,
    paid,
    pointsPaid,
  };
}

/**
 * Settles the week that has just ended, every Monday.
 *
 * Five past midnight rather than on the stroke of it: weeklyXp is written by
 * claimReward, and starting the read a few minutes after the boundary keeps
 * the settlement clear of the claims still landing on the old week.
 *
 * The timezone is pinned because everything else in this economy counts UTC
 * days and weeks. Leaving it to the platform default would settle on a
 * boundary the rest of the code does not recognise.
 */
export const settleWeeklyLeaderboard = onSchedule(
  {schedule: "5 0 * * 1", timeZone: "UTC"},
  async (_event) => {
    await settleLeaderboardWeek(settlementWeekFor(Date.now()));
  }
);

/**
 * Settles a week on demand. Admin-only.
 *
 * Two jobs: paying the weeks that have already run without a settlement (the
 * board has been showing prizes it could not honour), and making the schedule
 * testable without waiting for a Monday. Defaults to the week that just
 * ended; pass weekKey to name an older one.
 *
 * Safe to run repeatedly - it goes through the same idempotent path the
 * schedule does.
 */
export const settleLeaderboardNow = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (request.auth.token.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin only");
  }

  const requested = request.data?.weekKey;
  const weekKey = requested === undefined || requested === null ?
    settlementWeekFor(Date.now()) :
    Number(requested);

  if (!Number.isInteger(weekKey) || weekKey < 0) {
    throw new functions.https.HttpsError("invalid-argument", "Invalid weekKey");
  }
  // Refusing the current week is the guard that matters: settling it would
  // pay a standing that is still moving, and burn the ledger id the real
  // settlement needs on Monday.
  if (weekKey >= utcWeekFor(Date.now())) {
    throw new functions.https.HttpsError(
      "failed-precondition",
      "That week has not finished yet"
    );
  }

  const result = await settleLeaderboardWeek(weekKey);
  return {success: true, ...result};
});

/** Today's attempt counters, with the daily rollover already applied. */
interface DailyAttempts {
  /** True when the stored day stamp is not today's, so every count reads 0. */
  stale: boolean;
  quiz: number;
  game: number;
  bonusQuiz: number;
  bonusGame: number;
}

/**
 * Reads all four attempt counters against one staleness test.
 *
 * ONE PLACE, on purpose. The two counters and their two bonus counters share
 * a single day stamp, so "is this stamp from today" has to give the same
 * answer for all four at the same instant. When that test lived at each call
 * site it did not: a callable re-stamped the day while resetting only
 * quiz_attempts, left game_attempts reading as yesterday's, and locked users
 * out of games until repairStuckGameAttempts was written to dig them out.
 * Adding two more counters to that stamp doubles the surface for the same
 * bug, which is why the rule moved in here rather than being copied twice
 * more.
 *
 * [stale] is returned rather than hidden because writers need it too: a
 * counter found stale must be SET rather than incremented, and the stamp has
 * to be rewritten in the same breath - along with every other counter riding
 * it.
 */
function readDailyAttempts(
  userDoc: FirebaseFirestore.DocumentSnapshot
): DailyAttempts {
  const lastResetAt = userDoc.get(FIELD_LAST_RESET_TIME) as Timestamp | undefined;
  const stale =
    !lastResetAt || utcDayFor(lastResetAt.toMillis()) !== utcDayFor(Date.now());
  const read = (field: string) => (stale ? 0 : Number(userDoc.get(field) || 0));

  return {
    stale,
    quiz: read(FIELD_QUIZ_ATTEMPTS),
    game: read(FIELD_GAME_ATTEMPTS),
    bonusQuiz: read(FIELD_BONUS_QUIZ_ATTEMPTS),
    bonusGame: read(FIELD_BONUS_GAME_ATTEMPTS),
  };
}

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

  const userRef = getFirestore().collection(USERS_COLLECTION).doc(request.auth.uid);

  // Checked but NOT incremented here. The counter moves when a run is actually
  // claimed, so abandoning a game costs the player nothing; this read only
  // stops the app opening a session it already knows can never be paid out.
  const [userDoc, bonusCap] = await Promise.all([userRef.get(), configuredBonusCap()]);
  const daily = readDailyAttempts(userDoc);
  // The bonus counter has to be read HERE as well as at the claim, or an
  // attempt the user just paid an ad for would be refused a session and never
  // reach the claim that honours it.
  if (daily.game >= attemptsAllowance(MAX_DAILY_GAME_SESSIONS, daily.bonusGame, bonusCap)) {
    throw new functions.https.HttpsError("failed-precondition", "Daily game limit reached");
  }

  const sessionRef = userRef
    .collection(GAME_SESSIONS_SUBCOLLECTION)
    .doc();

  await sessionRef.set({
    gameId,
    startedAt: FieldValue.serverTimestamp(),
    consumed: false,
  });

  return {sessionId: sessionRef.id};
});

/**
 * Buys one extra attempt for today with a rewarded ad.
 *
 * RAISES THE CEILING; it does not refund a spent attempt. The user may call
 * it with attempts still in hand, so a day runs 0..(10 + cap) rather than resetting
 * to 10 only once emptied - and whatever is left, bought or not, is gone at
 * the UTC rollover.
 *
 * THE AD IS TAKEN ON THE CLIENT'S WORD, exactly as dailyStreak takes it.
 * That is safe for the streak because its reward is capped at once a day; it
 * is safe here for the same reason and no other, which is why
 * the bonus cap (see DEFAULT_DAILY_BONUS_ATTEMPTS) is described as the security rather than as a
 * tuning knob. There is no impression id to deduplicate on without
 * server-side verification, so a lying client cannot be caught - only
 * bounded. FIELD_BONUS_GRANTS_TOTAL is what makes the lying visible, by being
 * comparable against the network's own impression counts.
 *
 * Refusals are RETURNED rather than thrown: by the time this is called the ad
 * has already played, so the client needs to say something specific rather
 * than show a generic failure. The client is expected to hide the button at
 * the cap - reaching the refusal below means a user watched an ad for
 * nothing, and that is a client bug worth seeing in the logs.
 */
export const grantBonusAttempt = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const activity = String(request.data?.activity || "").trim();
  if (activity !== "quiz" && activity !== "game") {
    throw new functions.https.HttpsError("invalid-argument", "Unknown activity");
  }
  // An honest client only reaches here from onUserEarnedReward, so a missing
  // flag is a malformed call rather than a user who declined the ad.
  if (request.data?.adWatched !== true) {
    throw new functions.https.HttpsError("invalid-argument", "adWatched must be true");
  }

  const userId = request.auth.uid;
  const isQuiz = activity === "quiz";
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  // Outside the transaction: it is not part of what has to stay consistent
  // with the user document.
  const bonusCap = await configuredBonusCap();

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const daily = readDailyAttempts(userDoc);
    const granted = isQuiz ? daily.bonusQuiz : daily.bonusGame;
    const base = isQuiz ? MAX_DAILY_QUIZ_ATTEMPTS : MAX_DAILY_GAME_SESSIONS;
    const used = isQuiz ? daily.quiz : daily.game;

    if (granted >= bonusCap) {
      return {
        granted: false as const,
        reason: "daily_bonus_limit" as const,
        bonusAttempts: granted,
        attemptsUsed: used,
        allowance: attemptsAllowance(base, granted, bonusCap),
      };
    }

    const bonusField = isQuiz ? FIELD_BONUS_QUIZ_ATTEMPTS : FIELD_BONUS_GAME_ATTEMPTS;
    const updates: Record<string, unknown> = {
      // Set rather than incremented when the day has rolled over, for the
      // same reason claimReward sets its counters: incrementing a stale
      // counter carries yesterday's number into today.
      [bonusField]: daily.stale ? 1 : FieldValue.increment(1),
      [FIELD_BONUS_GRANTS_TOTAL]: FieldValue.increment(1),
    };

    // Granting on a stale day re-stamps it, and from that moment every
    // counter sharing the stamp reads as TODAY's. The three not being granted
    // must be zeroed in the same write, or the user buys an attempt and finds
    // yesterday's usage already spending it.
    if (daily.stale) {
      updates[FIELD_LAST_RESET_TIME] = Timestamp.now();
      updates[FIELD_QUIZ_ATTEMPTS] = 0;
      updates[FIELD_GAME_ATTEMPTS] = 0;
      updates[isQuiz ? FIELD_BONUS_GAME_ATTEMPTS : FIELD_BONUS_QUIZ_ATTEMPTS] = 0;
    }

    transaction.update(
      userRef,
      updates as FirebaseFirestore.UpdateData<FirebaseFirestore.DocumentData>
    );

    const bonusAttempts = granted + 1;
    return {
      granted: true as const,
      bonusAttempts,
      attemptsUsed: daily.stale ? 0 : used,
      allowance: attemptsAllowance(base, bonusAttempts, bonusCap),
    };
  });

  console.log("Bonus attempt", {userId, activity, ...result});

  return {
    success: true,
    activity,
    bonusCap,
    ...result,
  };
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
  // Refreshed before the transaction, because writeAward reads the reward
  // table synchronously from inside one. See ensureLevelRewardsFresh.
  await ensureLevelRewardsFresh();
  // The live ad-bonus cap, which the allowance below is enforced against.
  const bonusCap = await configuredBonusCap();

  let xpAward = 0;
  let incrementQuizAttempt = false;
  let incrementGameAttempt = false;
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
    incrementGameAttempt = true;
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

    // The daily attempt reset happens HERE, not in a callable of its own.
    //
    // checkAndResetQuizAttempts used to own this, which meant the client had
    // to call it - a Firestore read - after every quiz just to keep the
    // counter honest. This transaction already holds the user document, so
    // the rollover costs nothing extra, and the stored counter can never be
    // stale at the moment it actually matters: the moment it is enforced.
    //
    // The client applies the same rule locally for DISPLAY (see
    // UserData.quizAttemptsToday), but display is all it decides - the cap
    // below is what a quiz claim is actually refused by.
    const daily = readDailyAttempts(userDoc);
    const attemptsAreStale = daily.stale;
    const currentAttempts = daily.quiz;
    const currentGameAttempts = daily.game;
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

    // The allowance, not the bare constant: an attempt bought with a rewarded
    // ad is only worth anything if the cap that actually refuses claims knows
    // about it. This is the enforcement point - startGameSession's identical
    // test is a courtesy that saves a doomed session, nothing more.
    const quizAllowance = attemptsAllowance(MAX_DAILY_QUIZ_ATTEMPTS, daily.bonusQuiz, bonusCap);
    const gameAllowance = attemptsAllowance(MAX_DAILY_GAME_SESSIONS, daily.bonusGame, bonusCap);

    if (incrementQuizAttempt && currentAttempts >= quizAllowance) {
      throw new functions.https.HttpsError("failed-precondition", "Daily quiz limit reached");
    }
    // Thrown before the session is burned below, so a run refused for being
    // over the cap leaves its session open rather than silently spending it.
    if (incrementGameAttempt && currentGameAttempts >= gameAllowance) {
      throw new functions.https.HttpsError("failed-precondition", "Daily game limit reached");
    }

    // Referrer payout is evaluated here, in the same transaction that grants
    // the XP - it used to be a document trigger that woke on EVERY user write
    // (millions of invocations to check a condition that can fire at most once
    // per referred user). Reads must all happen before any write below.
    const referrer =
      await readReferrerForLevelUnlock(transaction, userDoc, currentXp, xpAward);

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

    const extraUpdates: Record<string, FieldValue | number | Timestamp | DailyStats> = {};
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
    //
    // ONLY XP EARNED AFTER UNLOCKING COUNTS - see weeklyXpGain. Below
    // TOURNAMENT_UNLOCK_LEVEL nothing accrues, and the claim that crosses it
    // counts only the part past the threshold, so reaching the level is not
    // itself a score.
    //
    // Entry is automatic: an unlocked player is stamped into the running week
    // by the same write that scores it.
    //
    // GUESTS ARE NOT ENTERED. Their XP only starts counting once Google is
    // linked, so nothing is accrued or stamped while the token is anonymous.
    if (!isGuest(request)) {
      const currentWeek = utcWeekFor(Date.now());
      const weekly = weeklyRollover(
        userDoc.get(FIELD_WEEK_KEY) as number | undefined,
        userDoc.get(FIELD_WEEKLY_XP) as number | undefined,
        currentWeek,
        weeklyXpGain(currentXp, award.level.xp, xpAward),
        userDoc.get(FIELD_TOURNAMENT_WEEK) as number | undefined
      );
      extraUpdates[FIELD_WEEKLY_XP] = weekly.weeklyXp;
      extraUpdates[FIELD_WEEK_KEY] = weekly.weekKey;
      if (isTournamentUnlocked(award.level.xp)) {
        extraUpdates[FIELD_TOURNAMENT_WEEK] = currentWeek;
      }
      // Only on the claim that crosses a boundary out of a RANKED week, and
      // this is the claim that would otherwise erase that week before it had
      // been paid for.
      if (weekly.lastWeekKey !== undefined) {
        extraUpdates[FIELD_LAST_WEEKLY_XP] = weekly.lastWeeklyXp as number;
        extraUpdates[FIELD_LAST_WEEK_KEY] = weekly.lastWeekKey;
      }
    }

    if (rewardSource === "GAME") stats.games += 1;
    if (rewardSource === "QUIZ") {
      stats.quizzes += 1;
      if (wasCorrect) stats.correct += 1;
    }
    // Set rather than increment: the whole map is replaced, which is also how
    // it resets when the day rolls over.
    extraUpdates[FIELD_DAILY_STATS] = stats;

    if (incrementQuizAttempt) {
      // Set rather than increment when the day has rolled over: incrementing
      // a stale counter would carry yesterday's attempts into today.
      extraUpdates[FIELD_QUIZ_ATTEMPTS] = attemptsAreStale ?
        1 :
        FieldValue.increment(1);
    }
    if (incrementGameAttempt) {
      extraUpdates[FIELD_GAME_ATTEMPTS] = attemptsAreStale ?
        1 :
        FieldValue.increment(1);
    }
    // Re-stamped on any claim that finds the day has rolled over - a game
    // counts too, so a user who plays before quizzing still gets the reset
    // recorded rather than carrying a stale day around.
    if (attemptsAreStale) {
      extraUpdates[FIELD_LAST_RESET_TIME] = Timestamp.now();
      if (!incrementQuizAttempt) extraUpdates[FIELD_QUIZ_ATTEMPTS] = 0;
      if (!incrementGameAttempt) extraUpdates[FIELD_GAME_ATTEMPTS] = 0;
      // The bonus counters ride this same stamp. Re-stamping the day without
      // clearing them would carry yesterday's purchased attempts into today,
      // handing out a free extra allowance every morning to anyone who bought
      // one the day before.
      extraUpdates[FIELD_BONUS_QUIZ_ATTEMPTS] = 0;
      extraUpdates[FIELD_BONUS_GAME_ATTEMPTS] = 0;
    }

    const {milestonePoints, milestoneLevels} =
      writeAward(transaction, userRef, ledgerRef, award, extraUpdates);

    payReferrer(transaction, userRef, referrer);

    return {
      rejected: false as const,
      pointsAwarded: award.pointsAwarded,
      // What the level-ups LOCKED, and which levels are now waiting. The
      // balance below deliberately excludes them: they are claimed with an
      // ad, so adding them here would report stars the user does not have.
      milestonePoints,
      milestoneLevels,
      totalPoints: currentPoints + award.pointsAwarded,
      xpAwarded: award.xpAwarded,
      totalXp: award.level.xp,
      level: award.level.level,
      leveledUp: award.level.leveledUp,
      attempts: incrementQuizAttempt ? currentAttempts + 1 : currentAttempts,
      gameAttempts: incrementGameAttempt ? currentGameAttempts + 1 : currentGameAttempts,
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
    // The handle a double is claimed against. Returned rather than
    // reconstructed by the client because only games have an id the client
    // could have rebuilt (`game:<sessionId>`) - a quiz entry is an
    // auto-generated document name that exists nowhere else.
    eventId: ledgerRef.id,
    ...payload,
  };
});

/**
 * How long after a claim its double is still accepted.
 *
 * Doubling is offered on the results screen and taken within seconds, so this
 * only has to be generous enough to cover a slow ad load and a player who
 * reads before tapping. It exists because the base ledger entry would
 * otherwise be a permanent, doubleable asset: with no window, a client could
 * sit on a day's worth of entry ids and cash them in together later, which is
 * exactly the shape of farming this is meant to make pointless.
 */
const DOUBLE_XP_WINDOW_MS = 10 * 60 * 1000;

/**
 * The sources a double is offered on, and the most one base entry of each may
 * have paid. All are XP-only and once-per-attempt or once-per-day.
 *
 * GAME and QUIZ are play; STREAK and MISSION are the daily login reward and
 * the daily goal bonus, whose claims are free and whose ad doubles them.
 */
const DOUBLE_XP_CEILINGS: Readonly<Record<string, number>> = {
  GAME: GAME_XP_PER_SESSION_CAP,
  QUIZ: QUIZ_CORRECT_XP,
  STREAK: MAX_STREAK_DAY_XP,
  MISSION: MAX_DAILY_GOAL_BONUS_XP,
};

/** Doubles that count toward the weekly tournament: play only. */
const WEEKLY_DOUBLE_SOURCES: ReadonlySet<string> = new Set(["GAME", "QUIZ"]);

/** Suffix marking an entry as the bonus half of a pair. */
const DOUBLE_SUFFIX = ":double";

/**
 * Pays a second helping of XP for a run or answer whose ad the player watched.
 *
 * KEYED ON THE LEDGER ENTRY, not on a game session. A game session id would
 * only ever have covered games: a quiz attempt has no session, and quizzes
 * needed the same offer. What both have is exactly one ledger entry recording
 * exactly what they were paid - which is the thing being doubled - so that
 * entry's id is the natural handle. `claimReward` now returns it.
 *
 * SEPARATE FROM claimReward ON PURPOSE. The base claim lands the moment the
 * activity ends, before any ad is shown, so a failed, unfilled or abandoned
 * ad can never cost the player the XP they actually earned - the offer is
 * strictly upside. Folding this into claimReward as a `doubled` flag would
 * have meant holding the entire claim open until the ad resolved.
 *
 * The base entry is the input: this reads what was really awarded rather than
 * taking a number from the client, so the amount doubled is the server's own
 * figure. The double writes its own entry at `<eventId>:double`, which makes
 * it idempotent for free - a redelivered call lands on the same document
 * instead of paying twice - and leaves the audit trail showing base and bonus
 * as two entries rather than one inflated one.
 *
 * THE AD IS ASSERTED, NOT PROVEN, exactly as grantBonusAttempt's is: there is
 * no server-side verification, so a tampered client can call this without
 * watching anything. What bounds that is what bounds a bonus attempt - the
 * ceiling. One double per entry, one entry per attempt, attempts capped per
 * day and XP capped per attempt; the most a liar collects is what a patient
 * honest player collects.
 *
 * WEEKLY LEADERBOARD XP IS COUNTED, deliberately, and this is the one place
 * the double touches something that settles against other players.
 *
 * The reasoning is that the weekly pot is FIXED. Letting ad-boosted XP into
 * the standings redistributes rank; it does not increase what we pay out, so
 * the tournament becomes a reason to watch ads at no extra payout cost. That
 * is the whole point of the offer.
 *
 * What that knowingly accepts: the ad is asserted, not proven, so a tampered
 * client can call this without watching anything and convert that straight
 * into rank. It is bounded - one double per attempt, attempts capped per day,
 * so a liar's ceiling is exactly an honest ad-watcher's ceiling - but within
 * that bound a cheat takes a real prize from someone who actually watched.
 * Server-side ad verification (AdMob SSV) is what would close it; until then
 * this is a priced trade, not an oversight.
 *
 * Daily goal counters are still NOT advanced - see below. Those are a count
 * of activities completed, and a double is one attempt paid twice rather than
 * a second attempt.
 */
export const claimDoubleXp = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const eventId = String(request.data.eventId || "").trim();
  if (!eventId) {
    throw new functions.https.HttpsError("invalid-argument", "A reward event id is required");
  }
  // A slash would address a different collection entirely - `doc("a/b/c")` is
  // a path, not a name - so an id containing one could reach outside this
  // user's rewardEvents. Refused before it is ever turned into a reference.
  if (eventId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "Malformed reward event id");
  }
  // A bonus entry carries the same source and shape as the base one it came
  // from, so without this it would satisfy every check below and double
  // itself - `x:double:double`, and again for as long as the window held.
  if (eventId.endsWith(DOUBLE_SUFFIX)) {
    throw new functions.https.HttpsError("invalid-argument", "A bonus cannot be doubled");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const events = userRef.collection(REWARD_EVENTS_SUBCOLLECTION);
  const baseRef = events.doc(eventId);
  const doubleRef = events.doc(`${eventId}${DOUBLE_SUFFIX}`);

  await ensureLevelCurvePublished();
  // Refreshed before the transaction, because writeAward reads the reward
  // table synchronously from inside one. See ensureLevelRewardsFresh.
  await ensureLevelRewardsFresh();

  return firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    const baseDoc = await transaction.get(baseRef);
    const doubleDoc = await transaction.get(doubleRef);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }
    if (!baseDoc.exists) {
      throw new functions.https.HttpsError("not-found", "No claim to double");
    }
    // Not something a player should ever reach - the button is gone by then -
    // but a redelivered call must not pay twice, and saying so is better than
    // silently rewriting the same document.
    if (doubleDoc.exists) {
      throw new functions.https.HttpsError("already-exists", "This was already doubled");
    }

    const source = String(baseDoc.get("source") || "");
    // Play and the two daily claims earn a double. Referral and level-up
    // awards are fixed by design and a redemption is a debit - none of them
    // are things an ad may re-pay.
    const ceiling = DOUBLE_XP_CEILINGS[source];
    if (ceiling === undefined) {
      throw new functions.https.HttpsError("invalid-argument", "That reward cannot be doubled");
    }
    if (baseDoc.get("status") !== "applied") {
      throw new functions.https.HttpsError("failed-precondition", "That claim was reversed");
    }

    const createdAt = baseDoc.get("createdAt") as Timestamp | undefined;
    if (!createdAt || Date.now() - createdAt.toMillis() > DOUBLE_XP_WINDOW_MS) {
      throw new functions.https.HttpsError("failed-precondition", "That reward is too old to double");
    }

    // What was actually paid, buff included. Doubling the buffed figure is
    // the intent: the offer on screen says "double your XP", and the number
    // it doubles has to be the one the player was just shown.
    const baseXp = Number(baseDoc.get("xpAwarded") || 0);
    if (!Number.isFinite(baseXp) || baseXp <= 0) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "That earned no XP to double"
      );
    }

    // Clamped against the source's own per-attempt ceiling as well as
    // trusting the ledger's figure. The ledger is server-written and should
    // never exceed it, but this is the one place an old or hand-edited entry
    // could be turned back into XP, so it is not taken on faith either.
    //
    // THE CEILING IS SCALED BY THE BUFF THE BASE ENTRY RECORDED. The caps
    // govern the PRE-buff figure - gameXpForScore applies GAME_XP_PER_SESSION_CAP
    // before buildAward multiplies - so a player running an XP booster is
    // legitimately paid above the bare cap, and `xpAwarded` here is that
    // buffed number. Comparing it against the unscaled ceiling would quietly
    // pay a boosted player LESS for their double than the ad promised, and
    // the further the booster went above 1x the worse it would get.
    const recordedMultiplier = Number(baseDoc.get("xpMultiplierApplied"));
    // A missing or nonsensical multiplier falls back to 1 rather than being
    // trusted: this value only ever widens the ceiling, so it is exactly the
    // field a hand-edited entry would want to inflate.
    const buff = Number.isFinite(recordedMultiplier) && recordedMultiplier >= 1 ?
      Math.min(recordedMultiplier, MAX_BUFF_MULTIPLIER) :
      1;
    const bonusXp = Math.min(Math.floor(baseXp), Math.floor(ceiling * buff));

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);
    const currentLevel = Number(userDoc.get(FIELD_LEVEL) || 1);

    // Reads before writes, as everywhere else: the bonus can be the gain that
    // carries a referred user to REFERRAL_UNLOCK_LEVEL.
    const referrer =
      await readReferrerForLevelUnlock(transaction, userDoc, currentXp, bonusXp);

    // No activeXpMultiplier is passed: the base entry was already buffed and
    // this doubles what that paid. Applying the buff again would compound it.
    const award = buildAward(currentPoints, currentXp, {
      source: source as RewardSource,
      basePoints: 0,
      baseXp: bonusXp,
      metadata: {
        doubledFrom: eventId,
        rewardedAd: true,
      },
      storedLevel: currentLevel,
    });

    // The weekly leaderboard, and nothing else.
    //
    // Read through weeklyRollover rather than incremented blindly so a week
    // that rolls over between the base claim and its double resets the total
    // instead of carrying last week's into this one - and preserves the
    // closing figure of a ranked week on the way past, so the double cannot
    // be what wipes a winning week out from under the settlement. That gap is
    // seconds wide, but it is exactly as wide as a rewarded ad and the
    // boundary does not care.
    //
    // The daily goal counters and the attempt counters stay untouched: this
    // is one attempt being paid twice, not a second attempt, so a double must
    // never advance a goal or spend an allowance.
    //
    // Counted by the same unlock rule as the base claim: a double that lands
    // below TOURNAMENT_UNLOCK_LEVEL adds nothing, and one that carries the
    // player across it counts only the part past the threshold.
    //
    // Streak and goal doubles skip all of this: their base claims never feed
    // the weekly board, so neither may the ad on top of them.
    const extraUpdates: Record<string, FieldValue | number> = {};
    // Guests are not in the tournament; see claimReward.
    if (WEEKLY_DOUBLE_SOURCES.has(source) && !isGuest(request)) {
      const currentWeek = utcWeekFor(Date.now());
      const weekly = weeklyRollover(
        userDoc.get(FIELD_WEEK_KEY) as number | undefined,
        userDoc.get(FIELD_WEEKLY_XP) as number | undefined,
        currentWeek,
        weeklyXpGain(currentXp, award.level.xp, bonusXp),
        userDoc.get(FIELD_TOURNAMENT_WEEK) as number | undefined
      );
      extraUpdates[FIELD_WEEKLY_XP] = weekly.weeklyXp;
      extraUpdates[FIELD_WEEK_KEY] = weekly.weekKey;
      if (isTournamentUnlocked(award.level.xp)) {
        extraUpdates[FIELD_TOURNAMENT_WEEK] = currentWeek;
      }
      if (weekly.lastWeekKey !== undefined) {
        extraUpdates[FIELD_LAST_WEEKLY_XP] = weekly.lastWeeklyXp as number;
        extraUpdates[FIELD_LAST_WEEK_KEY] = weekly.lastWeekKey;
      }
    }

    const {milestonePoints, milestoneLevels} =
      writeAward(transaction, userRef, doubleRef, award, extraUpdates);

    payReferrer(transaction, userRef, referrer);

    return {
      success: true,
      xpAwarded: award.xpAwarded,
      totalXp: award.level.xp,
      level: award.level.level,
      leveledUp: award.level.leveledUp,
      // Locked, not credited - see the note in claimReward's response.
      milestonePoints,
      milestoneLevels,
      totalPoints: currentPoints,
    };
  });
});

/**
 * Releases ONE locked level-up bonus, in exchange for a rewarded ad.
 *
 * Levelling still happens the instant the XP lands - the level number, the
 * curve, the perks it unlocks and everything gated on it are untouched. What
 * moved behind the ad is only the star payout: writeAward now queues the
 * level on the user's [FIELD_PENDING_LEVEL_REWARDS] and writes its ledger
 * entry "locked", and this is the other half of that.
 *
 * ONE CALL RELEASES ONE LEVEL, LOWEST FIRST. A player who climbed three
 * levels without claiming holds three entries in the queue and watches three
 * ads to empty it, in order - 2, then 3, then 4. That is not a limitation
 * working around a batch call that would have been easier to write; it is the
 * point of the feature, so the queue is drained with `Math.min` rather than
 * by taking whatever the client names. THE CLIENT DOES NOT CHOOSE THE LEVEL
 * AT ALL: nothing is read from `request.data`, which is what makes the order
 * unskippable rather than merely suggested.
 *
 * THE AMOUNT COMES FROM THE LOCKED LEDGER ENTRY, not from the reward table.
 * The table is console-editable, so re-reading it here would let a retune
 * change what a player was already promised - upward or downward - between
 * reaching a level and claiming it. The entry recorded the figure at the
 * moment it was earned and this pays exactly that.
 *
 * WHAT STOPS A DOUBLE PAYOUT, in a transaction, twice over: the level must
 * still be in the queue (removed with arrayRemove in the same commit as the
 * credit), and its entry must still be "locked" (flipped to "applied" in that
 * same commit). Either alone would do; both cost nothing, and they fail in
 * opposite directions - a queue that somehow held a stale level cannot pay,
 * and an entry that was somehow already applied cannot be re-applied.
 *
 * THE AD IS ASSERTED, NOT PROVEN - the same trade the streak claim, the goal
 * bonus, the bonus attempts and the double-XP offer already make, and the
 * same reasoning: there is no AdMob SSV yet, so a tampered client can call
 * this without watching anything. The exposure is smaller here than anywhere
 * else in the app, because this pays out a FIXED, ALREADY-EARNED amount from
 * a queue the server built. A liar does not mint stars; they skip an ad on
 * stars the honest path would have paid them anyway. The ceiling is the
 * level curve itself, which XP - not ad-watching - controls.
 */
export const claimLevelReward = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  return firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    // Defensive about the shape as well as the contents: this array is only
    // ever written by writeAward, but a document is a document, and a stray
    // string in here would otherwise become a document path below.
    const queue = (userDoc.get(FIELD_PENDING_LEVEL_REWARDS) as unknown[] | undefined) ?? [];
    const levels = queue
      .map((value) => Number(value))
      .filter((level) => Number.isInteger(level) && level > 1 && level <= MAX_LEVEL);

    if (levels.length === 0) {
      // Not an error. The button is gone once the queue empties, so reaching
      // this means a stale screen or a double tap, and both deserve the
      // truth rather than a red toast.
      return {
        success: true,
        claimed: false,
        reason: "nothing_pending",
        level: 0,
        pointsAwarded: 0,
        totalPoints: Number(userDoc.get(FIELD_POINTS) || 0),
        pendingLevels: [] as number[],
      };
    }

    // Lowest first. See the note on ordering above.
    const level = Math.min(...levels);
    const eventRef = userRef
      .collection(REWARD_EVENTS_SUBCOLLECTION)
      .doc(`levelup:${level}`);
    const eventDoc = await transaction.get(eventRef);

    // The queue and the ledger entry are written together and can only
    // disagree if something outside this code touched one of them. Dropping
    // the level rather than paying a guessed amount is the safe direction:
    // the alternative is inventing stars from a table the entry was supposed
    // to have pinned.
    if (!eventDoc.exists || eventDoc.get("status") !== "locked") {
      transaction.update(userRef, {
        [FIELD_PENDING_LEVEL_REWARDS]: FieldValue.arrayRemove(level),
      });
      console.warn("Dropped a queued level with no locked entry", {userId, level});
      return {
        success: true,
        claimed: false,
        reason: "already_claimed",
        level,
        pointsAwarded: 0,
        totalPoints: Number(userDoc.get(FIELD_POINTS) || 0),
        pendingLevels: levels.filter((l) => l !== level).sort((a, b) => a - b),
      };
    }

    const points = Math.max(Math.trunc(Number(eventDoc.get("finalPoints") || 0)), 0);
    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);

    transaction.update(userRef, {
      ...(points > 0 ? {[FIELD_POINTS]: FieldValue.increment(points)} : {}),
      [FIELD_PENDING_LEVEL_REWARDS]: FieldValue.arrayRemove(level),
    });

    // The entry becomes a real balance movement now, so it joins the Stars
    // activity list - and it is dated to the claim rather than to the
    // level-up, because that is when the balance actually changed. The
    // moment it was EARNED is not lost: `lockedAt` keeps it.
    transaction.update(eventRef, {
      status: "applied",
      affectsPoints: points !== 0,
      lockedAt: eventDoc.get("createdAt") ?? null,
      createdAt: FieldValue.serverTimestamp(),
    });

    const pendingLevels = levels.filter((l) => l !== level).sort((a, b) => a - b);
    console.log("Level reward claimed", {userId, level, points, remaining: pendingLevels.length});

    return {
      success: true,
      claimed: true,
      reason: "",
      level,
      pointsAwarded: points,
      totalPoints: currentPoints + points,
      pendingLevels,
    };
  });
});

/**
 * Finishes the Play tab tutorial: tops XP up to level 2, once per account.
 *
 * See economy/playTutorial.ts for why the top-up exists and why it is safe.
 * The short version, as enforced here:
 *
 *   * THE FLAG AND THE XP COMMIT TOGETHER. A second call - a retry, a double
 *     tap, another device - finds the flag set and pays nothing.
 *   * THE LEDGER, NOT THE CLIENT, SAYS THE TUTORIAL WAS PLAYED. At least one
 *     game run and two quiz answers must exist as reward events, which only
 *     claimReward writes. Wrong answers count: they are still ledger entries,
 *     and the tutorial is about trying a quiz, not acing one.
 *   * NOTHING IS READ FROM request.data. The amount is the gap to the level-2
 *     threshold, computed from the stored XP.
 *
 * An account already at level 2 or beyond completes with no XP, so the flag
 * is still set and the tutorial is not offered again.
 */
export const completePlayTutorial = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const events = userRef.collection(REWARD_EVENTS_SUBCOLLECTION);

  await ensureLevelCurvePublished();
  // writeAward reads the reward table synchronously inside the transaction.
  await ensureLevelRewardsFresh();

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);
    const currentLevel = Number(userDoc.get(FIELD_LEVEL) || 1);

    if (userDoc.get(FIELD_PLAY_TUTORIAL_COMPLETED) === true) {
      return {
        alreadyCompleted: true,
        xpAwarded: 0,
        level: currentLevel,
        leveledUp: false,
        milestonePoints: 0,
      };
    }

    // Limited queries: the cost is at most three small reads, once per
    // account, and single-field equality needs no composite index.
    const games = await transaction.get(
      events.where("source", "==", "GAME").limit(PLAY_TUTORIAL_GAMES_REQUIRED)
    );
    const quizzes = await transaction.get(
      events.where("source", "==", "QUIZ").limit(PLAY_TUTORIAL_QUIZZES_REQUIRED)
    );
    if (!playTutorialRequirementsMet(games.size, quizzes.size)) {
      throw new functions.https.HttpsError("failed-precondition", "Tutorial not finished");
    }

    const topUp = playTutorialTopUpXp(currentXp);
    const completedUpdates = {
      [FIELD_PLAY_TUTORIAL_COMPLETED]: true,
      playTutorialCompletedAt: FieldValue.serverTimestamp(),
    };

    if (topUp <= 0) {
      transaction.update(userRef, completedUpdates);
      return {
        alreadyCompleted: false,
        xpAwarded: 0,
        level: currentLevel,
        leveledUp: false,
        milestonePoints: 0,
      };
    }

    // Reads before writes. Level 2 is far below the referral unlock, so this
    // is null in practice; asked anyway so the rule lives in one place.
    const referrer = await readReferrerForLevelUnlock(transaction, userDoc, currentXp, topUp);

    const award = buildAward(currentPoints, currentXp, {
      source: "TUTORIAL",
      basePoints: 0,
      baseXp: topUp,
      metadata: {tutorial: "play"},
      storedLevel: currentLevel,
    });

    const {milestonePoints} = writeAward(
      transaction,
      userRef,
      events.doc(PLAY_TUTORIAL_LEDGER_ID),
      award,
      completedUpdates
    );
    payReferrer(transaction, userRef, referrer);

    return {
      alreadyCompleted: false,
      xpAwarded: award.xpAwarded,
      level: award.level.level,
      leveledUp: award.level.leveledUp,
      milestonePoints,
    };
  });

  console.log("Play tutorial completed", {userId, ...result});
  return {success: true, serverTime: Date.now(), ...result};
});

/**
 * Spends Points on one pack of one game.
 *
 * The cost is read from the game document server-side - a client-supplied
 * price is never trusted. The debit, the ledger entry, the player-ID link and
 * the pending redemption record all commit in one transaction, so a balance
 * can never go negative, a redemption can never exist without its matching
 * debit, and two accounts can never both claim the same player ID.
 *
 * Paying the user is a separate business process; this only records the
 * request and takes the Points.
 */
export const redeemReward = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  requireLinkedAccount(request);

  const optionId = String(request.data.optionId || "").trim();
  const packId = String(request.data.packId || "").trim();
  const playerId = String(request.data.playerId || "").trim();
  const username = String(request.data.username || "").trim();
  const server = String(request.data.server || "").trim();
  const useFirstRedeem = request.data.useFirstRedeem === true;

  if (!optionId) {
    throw new functions.https.HttpsError("invalid-argument", "Redemption option is required");
  }
  if (!packId) {
    throw new functions.https.HttpsError("invalid-argument", "Redemption pack is required");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const optionRef = firestore.collection(REDEMPTION_OPTIONS_COLLECTION).doc(optionId);
  const linkRef = firestore
    .collection(PLAYER_LINKS_COLLECTION)
    .doc(playerLinkId(optionId, playerId || "_"));

  const result = await firestore.runTransaction(async (transaction) => {
    // Every read first: Firestore transactions refuse a read issued after a
    // write, and the link claim below is a write.
    // config/redemption is no longer read here. Its only tunable was
    // firstRedeemMinLevel, and the offer has no level gate any more - one
    // fewer document read on the hottest transaction in the app.
    const [userDoc, optionDoc, linkDoc] = await Promise.all([
      transaction.get(userRef),
      transaction.get(optionRef),
      transaction.get(linkRef),
    ]);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentXp = Number(userDoc.get(FIELD_XP) || 0);
    const currentLevel = Number(userDoc.get(FIELD_LEVEL) || 1);

    const game = optionDoc.exists ? (optionDoc.data() as RedemptionGame) : null;
    const validation = validateRedemption({
      game,
      packId,
      userPoints: currentPoints,
      userLevel: currentLevel,
      playerId,
      username,
      server,
      useFirstRedeem,
      // An account that was never offered the discount (a repeat device or
      // email, see completeSignup) is refused like one that spent it - the
      // app hides the card, and this stops a modified app asking anyway.
      hasUsedFirstRedeem: userDoc.get("hasUsedFirstRedeem") === true ||
        userDoc.get("firstRedeemUnavailable") === true,
      // The per-game-account half of the offer rule. See the playerLinks note
      // in economy/redemption.ts for why the account flag alone is not enough.
      firstRedeemUidUsed: linkDoc.get("firstRedeemUsed") === true,
      callerUid: userId,
    });

    if (!validation.ok) {
      // A discount refused because THIS PLAYER ID has already had one is the
      // end of the offer for this account, not a retryable error: every other
      // UID they could enter belongs to somebody else's game account. Marking
      // it here - inside the transaction that refused - is what lets the app
      // retire the card for good rather than re-offering something that can
      // only be refused again.
      //
      // Deliberately NOT hasUsedFirstRedeem: they never used it, and an admin
      // restoring a rejected order must not hand it back to somebody who was
      // blocked rather than served.
      if (validation.rejection === "first_redeem_uid_used") {
        transaction.set(userRef, {firstRedeemUnavailable: true}, {merge: true});
      }
      return {ok: false as const, rejection: validation.rejection};
    }

    const pointsCost = validation.pointsCost as number;

    // BEFORE the first write below, because Firestore forbids a read after
    // one. A full-price order is the referee's second milestone; the
    // discounted offer is not - see readReferrerForRedeemUnlock.
    const referrer = await readReferrerForRedeemUnlock(
      transaction,
      userDoc,
      validation.usedFirstRedeem === true
    );

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
        optionTitle: game?.name,
        packId,
        packAmount: validation.packAmount,
        redemptionId: redemptionRef.id,
        firstRedeem: validation.usedFirstRedeem === true,
      },
      storedLevel: currentLevel,
    });

    writeAward(transaction, userRef, ledgerRef, award);
    payReferrer(transaction, userRef, referrer);

    // What is known about this player ID. No longer a claim on it - any
    // account may redeem into any UID at full price - so `uid` is now the
    // LAST account to use it rather than the owner, and is kept for tracing an
    // order by hand.
    //
    // `firstRedeemUsed` is the part that still decides anything: once a
    // discounted pack has been delivered into this game account, no sign-in
    // gets another one. Written in the same transaction as the order, so two
    // devices racing the same fresh UID cannot both take the discount.
    const linkUpdate: Record<string, unknown> = {
      uid: userId,
      gameId: optionId,
      playerId,
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (validation.usedFirstRedeem === true) {
      linkUpdate.firstRedeemUsed = true;
      linkUpdate.firstRedeemUid = userId;
      linkUpdate.firstRedeemAt = FieldValue.serverTimestamp();
    }
    transaction.set(linkRef, linkUpdate, {merge: true});

    // The user's own copy, for prefilling the form next time.
    transaction.set(
      userRef.collection(GAME_PROFILES_SUBCOLLECTION).doc(optionId),
      {
        playerId,
        username,
        server: validation.server ?? "",
        updatedAt: FieldValue.serverTimestamp(),
      },
      {merge: true}
    );

    // Burning the once-per-account discount is part of the same transaction
    // as the debit, so a retry cannot spend it twice.
    if (validation.usedFirstRedeem === true) {
      transaction.set(userRef, {hasUsedFirstRedeem: true}, {merge: true});
    }

    transaction.set(redemptionRef, {
      // Ownership is carried by this field now that the document no longer
      // lives under the user's path.
      uid: userId,
      // Denormalised so a payout can be actioned without a second lookup.
      userDisplayName: userDoc.get("displayName") ?? "",
      userEmail: userDoc.get("email") ?? "",
      optionId,
      optionTitle: game?.name ?? "",
      optionType: game?.code ?? "",
      packId,
      packAmount: validation.packAmount ?? "",
      playerId,
      username,
      server: validation.server ?? "",
      firstRedeem: validation.usedFirstRedeem === true,
      // Stored so resolveRedemption can release the per-UID mark without
      // rebuilding the id from fields that an admin may have edited since.
      playerLinkId: playerLinkId(optionId, playerId),
      // The device this account was created on, copied from the user document
      // rather than taken from the request - the client does not get to
      // choose what it is judged on. Free: the user document is already open
      // in this transaction.
      //
      // NOTHING HERE ACTS ON IT. It is written so the admin tool can say how
      // many accounts share a device, and an admin decides what that means.
      // See listRedemptions.
      androidId: String(userDoc.get("androidId") || ""),
      pointsCost,
      status: "pending",
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
    // failed-precondition means "your account is not in a state to do this";
    // invalid-argument means "what you sent is wrong". The client maps both
    // to a message, but the distinction is what tells it whether retrying
    // with different input could ever work.
    const precondition =
      result.rejection === "insufficient_points" ||
      result.rejection === "level_too_low" ||
      result.rejection === "option_disabled" ||
      result.rejection === "pack_disabled" ||
      result.rejection === "first_redeem_used" ||
      result.rejection === "first_redeem_uid_used" ||
      result.rejection === "first_redeem_unavailable";
    throw new functions.https.HttpsError(
      precondition ? "failed-precondition" : "invalid-argument",
      String(result.rejection)
    );
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

  const firestore = getFirestore();
  const users = firestore.collection(USERS_COLLECTION);

  const snapshot = await firestore
    .collection(REDEMPTIONS_COLLECTION)
    .where("status", "==", status)
    .orderBy("createdAt", "desc")
    .limit(pageSize)
    .get();

  const orders = snapshot.docs.map((doc) => ({id: doc.id, data: doc.data()}));

  // Which device each order came from.
  //
  // New orders carry it; ones placed before redeemReward started stamping do
  // not, so those are resolved from the ordering account. Looked up in one
  // getAll rather than a read per order, and only for the ones that need it -
  // in a settled backlog that is all of them once, and none of them after.
  const needsLookup = [...new Set(
    orders
      .filter((o) => !o.data.androidId)
      .map((o) => String(o.data.uid || ""))
      .filter(Boolean)
  )];

  const deviceByUid = new Map<string, string>();
  if (needsLookup.length > 0) {
    const docs = await firestore.getAll(
      ...needsLookup.map((uid) => users.doc(uid))
    );
    for (const doc of docs) {
      deviceByUid.set(doc.id, String(doc.get("androidId") || ""));
    }
  }

  const deviceOf = (o: {data: FirebaseFirestore.DocumentData}): string =>
    String(o.data.androidId || deviceByUid.get(String(o.data.uid || "")) || "");

  // HOW MANY ACCOUNTS SHARE EACH DEVICE, counted live rather than stored.
  //
  // Live because the interesting case appears AFTER the order is placed: the
  // second account is usually made later, and a figure frozen at order time
  // would still read "1" on the very order it matters for.
  //
  // A count query is billed per thousand index entries scanned rather than
  // per document, and this is asked once per distinct device on a page an
  // admin opens by hand - not on anything a user can trigger.
  //
  // NOTHING IS REJECTED ON THIS. It is a number next to an order, and an
  // admin decides what it means: two accounts on one phone is a family as
  // often as it is a farm, and no rule the server could apply would tell
  // those apart. Rejecting automatically would also teach whoever is farming
  // exactly which signal to defeat.
  const devices = [...new Set(orders.map(deviceOf).filter(Boolean))];
  const accountsPerDevice = new Map<string, number>();
  // And how many DISCOUNTED FIRST redeems each device has placed, across all
  // its accounts - the signal that replaced hiding the offer at signup. Same
  // rule as above: shown to the admin, acted on by nobody but the admin.
  // Two equality filters, which Firestore serves by merging its single-field
  // indexes, so no composite index is needed.
  const firstRedeemsPerDevice = new Map<string, number>();
  const redemptionsRef = firestore.collection(REDEMPTIONS_COLLECTION);
  await Promise.all(devices.map(async (device) => {
    const [counted, firsts] = await Promise.all([
      users.where("androidId", "==", device).count().get(),
      redemptionsRef.where("androidId", "==", device).where("firstRedeem", "==", true).count().get(),
    ]);
    accountsPerDevice.set(device, counted.data().count);
    firstRedeemsPerDevice.set(device, firsts.data().count);
  }));

  return {
    redemptions: orders.map((order) => {
      const data = order.data;
      const device = deviceOf(order);
      return {
        id: order.id,
        androidId: device,
        // 0 means we have no device on file for this account at all - an
        // account from before signup recorded one, or an install that
        // returned nothing. Shown as "unknown" rather than as "1", which
        // would be a clean bill of health we have not actually established.
        deviceAccountCount: device ? (accountsPerDevice.get(device) ?? 0) : 0,
        deviceFirstRedeemCount: device ? (firstRedeemsPerDevice.get(device) ?? 0) : 0,
        uid: data.uid ?? "",
        userDisplayName: data.userDisplayName ?? "",
        userEmail: data.userEmail ?? "",
        optionTitle: data.optionTitle ?? data.optionId ?? "",
        optionType: data.optionType ?? "",
        packAmount: data.packAmount ?? "",
        // What the operator actually needs to fulfil the order by hand.
        playerId: data.playerId ?? "",
        username: data.username ?? "",
        server: data.server ?? "",
        firstRedeem: data.firstRedeem === true,
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
 *
 * `restoreFirstRedeem` decides what happens to the DISCOUNT on a rejected
 * first order, and it is the admin's call because only they know why they
 * rejected it. The two cases pull in opposite directions and no rule could
 * tell them apart: a mistyped UID deserves the offer back, and an account
 * caught farming it does not. Defaults to true - the common rejection is an
 * honest mistake, and quietly costing somebody their one discount because an
 * admin forgot a checkbox is the worse failure of the two.
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
  // Absent means yes. An older admin build that does not send the field at
  // all keeps the behaviour it was written against.
  const restoreFirstRedeem = request.data.restoreFirstRedeem !== false;

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
      // The denomination is what the feed line actually says - "1000 UC"
      // rather than the game it belongs to. Still safe to publish: it is the
      // same figure the catalogue shows everyone.
      const feedAmount = String(redemptionDoc.get("packAmount") || "");

      transaction.set(
        firestore.collection(PAYOUT_FEED_COLLECTION).doc(redemptionId),
        {
          name: feedName,
          optionTitle: feedTitle,
          packAmount: feedAmount,
          approvedAt: FieldValue.serverTimestamp(),
        }
      );

      // Logged so an approval that produced no feed row can be told apart
      // from one that never reached this code at all.
      console.log("Payout feed entry queued", {
        redemptionId,
        name: feedName,
        optionTitle: feedTitle,
        packAmount: feedAmount,
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

    // A rejected first redeem can give the DISCOUNT back as well as the
    // stars, and whether it does is the admin's decision - see the note on
    // this function. Only a first order has anything to restore, so the flag
    // is meaningless on any other one.
    const wasFirstRedeem = redemptionDoc.get("firstRedeem") === true;
    const giveDiscountBack = wasFirstRedeem && restoreFirstRedeem;

    writeAward(
      transaction,
      userRef,
      userRef.collection(REWARD_EVENTS_SUBCOLLECTION).doc(`refund:${redemptionId}`),
      refund,
      giveDiscountBack ? {hasUsedFirstRedeem: false} : {}
    );

    // The per-UID mark has to come back with the account flag, or the user is
    // handed an offer they cannot spend: the account would be clear, the game
    // account they were redeeming into would not, and their next attempt
    // would be refused as "already claimed on this UID" - which then retires
    // the card for good. Half a restore is worse than none.
    //
    // Released only on an explicit restore. A rejection alone must never free
    // the mark, or "get rejected" becomes the way to farm the discount.
    const linkId = redemptionDoc.get("playerLinkId") as string | undefined;
    if (giveDiscountBack && linkId) {
      transaction.set(
        firestore.collection(PLAYER_LINKS_COLLECTION).doc(linkId),
        {
          firstRedeemUsed: false,
          firstRedeemReleasedAt: FieldValue.serverTimestamp(),
          firstRedeemReleasedBy: request.auth?.uid ?? "",
        },
        {merge: true}
      );
    }

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
    return {refunded: pointsCost, targetUid, firstRedeemRestored: giveDiscountBack};
  });

  console.log("Redemption resolved", {redemptionId, status, ...result});
  return {success: true, status, ...result};
});

// --- Support tickets ----------------------------------------------------------
//
// The rules live in economy/supportTickets.ts. Clients read their own tickets
// through firestore.rules and write only through these callables.

/** On the user document: the ticket still open or answered, if any. */
const FIELD_ACTIVE_SUPPORT_TICKET = "activeSupportTicketId";
/** On the user document: {dayUtc, count} of support messages sent today. */
const FIELD_SUPPORT_MESSAGES_DAILY = "supportMessagesDaily";

function supportRefusal(reason: SupportRefusal): functions.https.HttpsError {
  switch (reason) {
  case "not_owner":
    return new functions.https.HttpsError("not-found", "Ticket not found");
  case "daily_limit":
    return new functions.https.HttpsError("resource-exhausted", reason);
  default:
    return new functions.https.HttpsError("failed-precondition", reason);
  }
}

function requireCleanMessage(raw: unknown, max?: number): string {
  const cleaned = cleanMessage(raw, max);
  if (!cleaned.ok) {
    throw new functions.https.HttpsError("invalid-argument", cleaned.reason);
  }
  return cleaned.text;
}

function requireAdmin(request: CallableRequest): void {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  if (request.auth.token.admin !== true) {
    throw new functions.https.HttpsError("permission-denied", "Admin only");
  }
}

/**
 * Opens a ticket with its first message.
 *
 * Refused while the user already has an active ticket (one conversation at a
 * time) or has hit the daily message cap. A linked order must be the caller's
 * own - its id is checked, never trusted.
 */
export const createSupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  const userId = request.auth.uid;
  const category = request.data?.category;
  if (!isSupportCategory(category)) {
    throw new functions.https.HttpsError("invalid-argument", "Unknown category");
  }
  const text = requireCleanMessage(request.data?.message);
  const orderId = String(request.data?.orderId || "").trim();
  const appVersion = String(request.data?.appVersion || "").slice(0, 20);

  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const ticketRef = firestore.collection(SUPPORT_TICKETS_COLLECTION).doc();

  const result = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);
    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    // The pointer can outlive its ticket (resolved by an admin before this
    // field existed, or deleted by hand), so it is confirmed, not believed.
    const activeId = String(userDoc.get(FIELD_ACTIVE_SUPPORT_TICKET) || "");
    let hasActive = false;
    if (activeId) {
      const active = await transaction.get(
        firestore.collection(SUPPORT_TICKETS_COLLECTION).doc(activeId)
      );
      hasActive = active.exists && active.get("status") !== "resolved";
    }

    let orderLabel = "";
    if (orderId && !orderId.includes("/")) {
      const order = await transaction.get(
        firestore.collection(REDEMPTIONS_COLLECTION).doc(orderId)
      );
      if (!order.exists || order.get("uid") !== userId) {
        throw new functions.https.HttpsError("invalid-argument", "Unknown order");
      }
      const amount = String(order.get("packAmount") || order.get("optionTitle") || "Order");
      orderLabel = `${amount} · ${orderId.slice(-8).toUpperCase()}`;
    }

    const todayUtc = utcDayFor(Date.now());
    const sent = messagesSentToday(userDoc.get(FIELD_SUPPORT_MESSAGES_DAILY), todayUtc);
    const decision = resolveNewTicket(hasActive, sent);
    if (!decision.ok) {
      return {ok: false as const, reason: decision.reason, activeTicketId: activeId};
    }

    const now = FieldValue.serverTimestamp();
    transaction.set(ticketRef, {
      uid: userId,
      email: String(request.auth?.token.email || userDoc.get("email") || ""),
      displayName: String(userDoc.get("displayName") || ""),
      category,
      orderId: orderLabel ? orderId : "",
      orderLabel,
      subject: subjectFor(text),
      status: "open" as TicketStatus,
      createdAt: now,
      updatedAt: now,
      lastMessageAt: now,
      lastMessageFrom: "user",
      lastMessagePreview: subjectFor(text),
      userUnread: false,
      adminUnread: true,
      messageCount: 1,
      [FIELD_USER_MESSAGES_SINCE_REPLY]: 1,
      appVersion,
    });
    transaction.set(ticketRef.collection(SUPPORT_MESSAGES_SUBCOLLECTION).doc(), {
      from: "user",
      text,
      createdAt: now,
    });
    transaction.update(userRef, {
      [FIELD_ACTIVE_SUPPORT_TICKET]: ticketRef.id,
      [FIELD_SUPPORT_MESSAGES_DAILY]: {dayUtc: todayUtc, count: sent + 1},
    });
    return {ok: true as const};
  });

  if (!result.ok) {
    if (result.reason === "active_ticket_exists") {
      throw new functions.https.HttpsError("failed-precondition", result.reason, {
        ticketId: result.activeTicketId,
      });
    }
    throw supportRefusal(result.reason);
  }
  console.log("Support ticket opened", {userId, ticketId: ticketRef.id, category});
  return {success: true, ticketId: ticketRef.id};
});

/** The user's reply on their own active ticket. Reopens it for support. */
export const replySupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  const userId = request.auth.uid;
  const ticketId = String(request.data?.ticketId || "").trim();
  if (!ticketId || ticketId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "A ticket id is required");
  }
  const text = requireCleanMessage(request.data?.message);

  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);
  const ticketRef = firestore.collection(SUPPORT_TICKETS_COLLECTION).doc(ticketId);

  await firestore.runTransaction(async (transaction) => {
    const [userDoc, ticket] = await Promise.all([
      transaction.get(userRef),
      transaction.get(ticketRef),
    ]);
    if (!ticket.exists) throw supportRefusal("not_owner");

    const todayUtc = utcDayFor(Date.now());
    const sent = messagesSentToday(userDoc.get(FIELD_SUPPORT_MESSAGES_DAILY), todayUtc);
    const decision = resolveUserReply(
      {
        uid: ticket.get("uid"),
        status: ticket.get("status"),
        userMessagesSinceReply: ticket.get(FIELD_USER_MESSAGES_SINCE_REPLY),
      },
      userId,
      sent
    );
    if (!decision.ok) throw supportRefusal(decision.reason);

    const now = FieldValue.serverTimestamp();
    transaction.set(ticketRef.collection(SUPPORT_MESSAGES_SUBCOLLECTION).doc(), {
      from: "user",
      text,
      createdAt: now,
    });
    transaction.update(ticketRef, {
      status: "open" as TicketStatus,
      updatedAt: now,
      lastMessageAt: now,
      lastMessageFrom: "user",
      lastMessagePreview: subjectFor(text),
      adminUnread: true,
      userUnread: false,
      messageCount: FieldValue.increment(1),
      [FIELD_USER_MESSAGES_SINCE_REPLY]: FieldValue.increment(1),
    });
    transaction.update(userRef, {
      [FIELD_SUPPORT_MESSAGES_DAILY]: {dayUtc: todayUtc, count: sent + 1},
    });
  });

  return {success: true};
});

/**
 * Closes a ticket. The owner may close their own; an admin may close any.
 * Clears the owner's active pointer so they can open a new one.
 */
async function closeTicket(ticketId: string, closedBy: "user" | "admin", callerUid: string) {
  const firestore = getFirestore();
  const ticketRef = firestore.collection(SUPPORT_TICKETS_COLLECTION).doc(ticketId);
  await firestore.runTransaction(async (transaction) => {
    const ticket = await transaction.get(ticketRef);
    if (!ticket.exists) throw supportRefusal("not_owner");
    const ownerUid = String(ticket.get("uid") || "");
    if (closedBy === "user" && ownerUid !== callerUid) throw supportRefusal("not_owner");
    const ownerRef = firestore.collection(USERS_COLLECTION).doc(ownerUid);
    const owner = await transaction.get(ownerRef);

    transaction.update(ticketRef, {
      status: "resolved" as TicketStatus,
      updatedAt: FieldValue.serverTimestamp(),
      resolvedAt: FieldValue.serverTimestamp(),
      resolvedBy: closedBy,
      adminUnread: false,
      // An admin closing it is news to the user; the user closing it is not.
      userUnread: closedBy === "admin",
    });
    if (owner.exists && owner.get(FIELD_ACTIVE_SUPPORT_TICKET) === ticketId) {
      transaction.update(ownerRef, {[FIELD_ACTIVE_SUPPORT_TICKET]: FieldValue.delete()});
    }
  });
}

export const closeSupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  const ticketId = String(request.data?.ticketId || "").trim();
  if (!ticketId || ticketId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "A ticket id is required");
  }
  await closeTicket(ticketId, "user", request.auth.uid);
  return {success: true};
});

/** Admin: tickets by status, most recently active first. */
export const listSupportTickets = functions.https.onCall(async (request: CallableRequest) => {
  requireAdmin(request);
  const status = String(request.data?.status || "open");
  if (status !== "open" && status !== "answered" && status !== "resolved") {
    throw new functions.https.HttpsError("invalid-argument", "Unknown status");
  }
  const snapshot = await getFirestore().collection(SUPPORT_TICKETS_COLLECTION)
    .where("status", "==", status)
    .orderBy("lastMessageAt", "desc")
    .limit(100)
    .get();
  return {
    tickets: snapshot.docs.map((doc) => ({
      id: doc.id,
      uid: doc.get("uid"),
      email: doc.get("email"),
      displayName: doc.get("displayName"),
      category: doc.get("category"),
      orderId: doc.get("orderId"),
      orderLabel: doc.get("orderLabel"),
      subject: doc.get("subject"),
      status: doc.get("status"),
      adminUnread: doc.get("adminUnread") === true,
      messageCount: doc.get("messageCount") ?? 0,
      lastMessageFrom: doc.get("lastMessageFrom"),
      lastMessageAtMillis: (doc.get("lastMessageAt") as Timestamp | undefined)?.toMillis() ?? null,
      createdAtMillis: (doc.get("createdAt") as Timestamp | undefined)?.toMillis() ?? null,
      appVersion: doc.get("appVersion") ?? "",
    })),
  };
});

/** Admin: one ticket's thread, plus the account context needed to answer it. */
export const getSupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  requireAdmin(request);
  const ticketId = String(request.data?.ticketId || "").trim();
  if (!ticketId || ticketId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "A ticket id is required");
  }
  const firestore = getFirestore();
  const ticketRef = firestore.collection(SUPPORT_TICKETS_COLLECTION).doc(ticketId);
  const [ticket, messages] = await Promise.all([
    ticketRef.get(),
    ticketRef.collection(SUPPORT_MESSAGES_SUBCOLLECTION).orderBy("createdAt", "asc").get(),
  ]);
  if (!ticket.exists) throw new functions.https.HttpsError("not-found", "Ticket not found");

  const user = await firestore.collection(USERS_COLLECTION).doc(String(ticket.get("uid"))).get();
  if (ticket.get("adminUnread") === true) await ticketRef.update({adminUnread: false});

  return {
    ticket: {
      id: ticket.id,
      uid: ticket.get("uid"),
      email: ticket.get("email") ?? "",
      displayName: ticket.get("displayName") ?? "",
      category: ticket.get("category"),
      orderId: ticket.get("orderId") ?? "",
      orderLabel: ticket.get("orderLabel") ?? "",
      subject: ticket.get("subject") ?? "",
      status: ticket.get("status"),
      userMessagesSinceReply: Number(ticket.get(FIELD_USER_MESSAGES_SINCE_REPLY) || 0),
      appVersion: ticket.get("appVersion") ?? "",
      createdAtMillis: (ticket.get("createdAt") as Timestamp | undefined)?.toMillis() ?? null,
    },
    messages: messages.docs.map((m) => ({
      id: m.id,
      from: m.get("from"),
      text: m.get("text"),
      createdAtMillis: (m.get("createdAt") as Timestamp | undefined)?.toMillis() ?? null,
    })),
    account: user.exists ? {
      points: Number(user.get(FIELD_POINTS) || 0),
      level: Number(user.get(FIELD_LEVEL) || 1),
      androidId: String(user.get("androidId") || ""),
    } : null,
  };
});

/** Admin: reply, optionally closing the ticket in the same step. */
export const adminReplySupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  requireAdmin(request);
  const ticketId = String(request.data?.ticketId || "").trim();
  if (!ticketId || ticketId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "A ticket id is required");
  }
  const text = requireCleanMessage(request.data?.message, ADMIN_MESSAGE_MAX);
  const resolve = request.data?.resolve === true;

  const firestore = getFirestore();
  const ticketRef = firestore.collection(SUPPORT_TICKETS_COLLECTION).doc(ticketId);
  await firestore.runTransaction(async (transaction) => {
    const ticket = await transaction.get(ticketRef);
    if (!ticket.exists) throw new functions.https.HttpsError("not-found", "Ticket not found");
    const now = FieldValue.serverTimestamp();
    transaction.set(ticketRef.collection(SUPPORT_MESSAGES_SUBCOLLECTION).doc(), {
      from: "admin",
      text,
      createdAt: now,
      adminUid: request.auth?.uid ?? "",
    });
    transaction.update(ticketRef, {
      status: "answered" as TicketStatus,
      updatedAt: now,
      lastMessageAt: now,
      lastMessageFrom: "admin",
      lastMessagePreview: subjectFor(text),
      userUnread: true,
      adminUnread: false,
      messageCount: FieldValue.increment(1),
      // Support replied: the user may send up to two messages again.
      [FIELD_USER_MESSAGES_SINCE_REPLY]: 0,
    });
  });
  if (resolve) await closeTicket(ticketId, "admin", request.auth?.uid ?? "");
  return {success: true};
});

/** Admin: close a ticket without replying. */
export const adminCloseSupportTicket = functions.https.onCall(async (request: CallableRequest) => {
  requireAdmin(request);
  const ticketId = String(request.data?.ticketId || "").trim();
  if (!ticketId || ticketId.includes("/")) {
    throw new functions.https.HttpsError("invalid-argument", "A ticket id is required");
  }
  await closeTicket(ticketId, "admin", request.auth?.uid ?? "");
  return {success: true};
});

/**
 * Deletes the caller's account, as Google Play requires apps with sign-up to
 * offer in-app.
 *
 * Everything under users/{uid} and the Firebase Auth user go now. A tombstone
 * with the Android ID and an email fingerprint is written FIRST, so a failure
 * part-way leaves the anti-fraud record rather than an account that can be
 * re-created to claim new-user rewards again. Orders, offer records and
 * first-redeem marks are kept for DELETION_RETENTION_MS and cleaned up by
 * purgeDeletedAccounts. What is kept is stated in public/legal/privacy.html.
 *
 * Refused while a redemption is pending - see resolveDeletion.
 */
export const deleteAccount = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();
  const userRef = firestore.collection(USERS_COLLECTION).doc(userId);

  const [pending, userDoc] = await Promise.all([
    firestore.collection(REDEMPTIONS_COLLECTION)
      .where("uid", "==", userId)
      .where("status", "==", "pending")
      .limit(1)
      .get(),
    userRef.get(),
  ]);

  const decision = resolveDeletion(!pending.empty);
  if (!decision.ok) {
    throw new functions.https.HttpsError("failed-precondition", decision.reason);
  }

  const email = String(request.auth.token.email || userDoc.get("email") || "");
  const now = Date.now();
  await firestore.collection(DELETED_ACCOUNTS_COLLECTION).doc(userId).set({
    androidId: String(userDoc.get("androidId") || ""),
    emailHash: emailFingerprint(email),
    deletedAt: Timestamp.fromMillis(now),
    purgeAfter: Timestamp.fromMillis(purgeAfterMillis(now)),
  });

  // The profile and every subcollection: rewardEvents, gameSessions,
  // gameProfiles.
  await firestore.recursiveDelete(userRef);

  // Support tickets and their messages. Not kept: nothing in them is needed
  // for fraud or payout disputes that the order records don't already hold.
  const tickets = await firestore.collection(SUPPORT_TICKETS_COLLECTION)
    .where("uid", "==", userId).get();
  for (const ticket of tickets.docs) {
    await firestore.recursiveDelete(ticket.ref);
  }

  try {
    await admin.auth().deleteUser(userId);
  } catch (error) {
    const code = (error as {code?: string}).code;
    if (code !== "auth/user-not-found") throw error;
  }

  console.log("Account deleted", {userId});
  return {success: true};
});

/**
 * Removes what deleteAccount kept, once the retention period has passed.
 *
 * Orders and offer records are deleted. First-redeem marks are anonymised
 * instead - the uid fields are cleared but the mark stays - so the one-time
 * discount remains spent on that game account.
 */
export const purgeDeletedAccounts = onSchedule(
  {schedule: "30 3 * * *", timeZone: "UTC"},
  async (_event) => {
    const firestore = getFirestore();
    const due = await firestore.collection(DELETED_ACCOUNTS_COLLECTION)
      .where("purgeAfter", "<=", Timestamp.now())
      .limit(100)
      .get();

    for (const tombstone of due.docs) {
      const uid = tombstone.id;
      const [orders, offers, links, firstLinks] = await Promise.all([
        firestore.collection(REDEMPTIONS_COLLECTION).where("uid", "==", uid).get(),
        firestore.collection("offerwallTransactions").where("uid", "==", uid).get(),
        firestore.collection(PLAYER_LINKS_COLLECTION).where("uid", "==", uid).get(),
        firestore.collection(PLAYER_LINKS_COLLECTION).where("firstRedeemUid", "==", uid).get(),
      ]);

      const writer = firestore.bulkWriter();
      orders.docs.forEach((doc) => writer.delete(doc.ref));
      offers.docs.forEach((doc) => writer.delete(doc.ref));
      links.docs.forEach((doc) => writer.update(doc.ref, {uid: FieldValue.delete()}));
      firstLinks.docs.forEach((doc) =>
        writer.update(doc.ref, {firstRedeemUid: FieldValue.delete()}));
      writer.delete(tombstone.ref);
      await writer.close();

      console.log("Deleted account purged", {
        uid,
        orders: orders.size,
        offers: offers.size,
        links: links.size + firstLinks.size,
      });
    }
  }
);

/**
 * The referral progress list behind the Profile screen.
 *
 * Goes through a callable rather than a client query for the same reason
 * listRedemptions does: firestore.rules never grants a client read across
 * users, so the only way to learn anything about a referee is to ask the
 * server, which can decide exactly how much to say.
 *
 * What it says is deliberately narrow. A referrer sees a MASKED name, when
 * the account joined, how far it is toward the unlock threshold, and whether
 * the reward has been paid. Never an email, never a uid, never a balance -
 * inviting somebody does not entitle you to watch their account.
 *
 * Progress is the referee's LEVEL against REFERRAL_UNLOCK_LEVEL, because that
 * is the condition the first payout actually tests - a bar measuring anything
 * else would fill at a different rate than the reward arrives. It is computed
 * from XP rather than read from the `level` field for the same reason
 * readReferrerForLevelUnlock computes it: the field is a cache that lags.
 *
 * Each invitee reports BOTH milestones, because a referrer who sees one
 * number cannot tell a referee who played and stopped from one who redeemed.
 */
export const getReferralStats = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const userId = request.auth.uid;
  const firestore = getFirestore();

  // Equality on one field - served by the automatic single-field index, so
  // no composite index has to be deployed for this.
  const snapshot = await firestore
    .collection(USERS_COLLECTION)
    .where(FIELD_REFERRED_BY, "==", userId)
    .limit(REFERRAL_LIST_LIMIT)
    .get();

  const invitees = snapshot.docs.map((doc) => {
    const level = levelForXp(Number(doc.get(FIELD_XP) || 0));
    // The paid flags live on the REFEREE and are written in the same
    // transaction that pays, so they are the honest answer to "did this
    // actually pay out" rather than "did it qualify".
    const levelPaid = doc.get(FIELD_REFERRAL_LEVEL_PAID) === true;
    const redeemPaid = doc.get(FIELD_REFERRAL_REDEEM_PAID) === true;

    return {
      name: maskDisplayName(doc.get("displayName") as string | undefined),
      joinedAtMillis: (doc.createTime?.toMillis?.() ?? null),
      level: Math.min(level, REFERRAL_UNLOCK_LEVEL),
      levelTarget: REFERRAL_UNLOCK_LEVEL,
      qualified: level >= REFERRAL_UNLOCK_LEVEL,
      levelPaid,
      redeemPaid,
    };
  }).sort((a, b) => (b.joinedAtMillis ?? 0) - (a.joinedAtMillis ?? 0));

  return {
    invitees,
    invited: invitees.length,
    qualified: invitees.filter((i) => i.qualified).length,
    levelPaid: invitees.filter((i) => i.levelPaid).length,
    redeemPaid: invitees.filter((i) => i.redeemPaid).length,
    // The rule, from the server that enforces it - so the Profile screen can
    // state the terms without hardcoding numbers that could drift.
    unlockLevel: REFERRAL_UNLOCK_LEVEL,
    levelReward: REFERRER_LEVEL_REWARD_POINTS,
    redeemReward: REFERRER_REDEEM_REWARD_POINTS,
  };
});

export const submitReferral = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  requireLinkedAccount(request);

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

  const outcome = await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    if (userDoc.get(FIELD_HAS_USED_REFERRAL) === true) {
      throw new functions.https.HttpsError("failed-precondition", "Referral already used");
    }

    // THE WINDOW CLOSES AT REFERRAL_UNLOCK_LEVEL, and it closes here rather
    // than only in the UI - the client hiding the field is a courtesy, this
    // is the rule. A code entered by an account that has already played ten
    // levels did not bring anybody to the app; it is an existing player being
    // handed a code, which is the shape most referral fraud takes, and it
    // would pay the level milestone out instantly on the referee's very next
    // award.
    //
    // Computed from XP, not read from `level`: the field is a cache that lags
    // until the next award repairs it, and half an hour of play sitting
    // uncached is exactly the gap somebody would aim for.
    if (levelForXp(Number(userDoc.get(FIELD_XP) || 0)) >= REFERRAL_UNLOCK_LEVEL) {
      return {status: "window_closed" as const};
    }

    // No award to the referee - see rewardConfig. The only writes are the
    // link itself, which is what every later milestone is read from, and the
    // flag that retires the input. No ledger entry either: nothing moved.
    transaction.update(userRef, {
      [FIELD_HAS_USED_REFERRAL]: true,
      [FIELD_REFERRED_BY]: referrerId,
    });

    return {status: "success" as const};
  });

  return outcome;
});

/**
 * The server-to-server postback endpoint.
 *
 * THE FIRST onRequest FUNCTION IN THIS CODEBASE, and deliberately generic.
 * Offerwalls, survey routers, the sponsored-app track and any future
 * affiliate integration all settle the same way - an unauthenticated HTTPS
 * call from a partner, signed with a shared secret - so this is written once
 * against a config-driven notion of "network" rather than once per partner.
 * Adding ayeT-Studios beside Torox is a document, not a deploy.
 *
 * It is the only path in the app that credits Points on the word of somebody
 * outside it, which shapes every decision below:
 *
 *   * The signature is checked BEFORE the amount is read, and an unsigned
 *     request never reaches the code that decides what to pay.
 *   * The transaction id is spent exactly once, through a deterministic
 *     document id inside the award transaction - not a query beforehand,
 *     which two simultaneous retries would both pass.
 *   * The amount is capped twice: by the network's own configured ceiling
 *     and by MAX_POINTS_PER_POSTBACK in code. A leaked secret is then a
 *     bounded loss rather than a mint.
 *
 * ON RETRIES AND STATUS CODES. Networks retry anything that is not a 200,
 * often for hours, so the reply is chosen by whether retrying could ever
 * help. A duplicate is answered 200 - the work is already done, and a second
 * payment is exactly what must not happen. A bad signature is 403 and a
 * malformed request is 400, because those are the partner's bug to fix and
 * silence would hide it. An unknown uid is answered 200 and RECORDED as
 * rejected: retrying cannot conjure the account, so the useful outcome is an
 * audit row somebody can settle by hand rather than a week of retries.
 */
export const offerwallCallback = functions.https.onRequest(
  {cors: false},
  async (request, response) => {
    // Query AND body, merged.
    //
    // Most networks in this tier send a GET, and an earlier version of this
    // read only `request.query` on that basis. That is an assumption about
    // somebody else's product, and the failure it produces is bad: a
    // POST-only network gets `missing_parameters`, retries for hours, and
    // the integration looks broken on our side with nothing in the logs to
    // say why. Reading both costs one loop.
    //
    // Query wins on a collision, because `network` is in the URL we hand the
    // partner and must not be overridable by whatever they post.
    const query: Record<string, string> = {};
    const collect = (source: unknown) => {
      if (!source || typeof source !== "object") return;
      for (const [key, value] of Object.entries(source as Record<string, unknown>)) {
        if (value === null || value === undefined) continue;
        if (typeof value === "object" && !Array.isArray(value)) continue;
        query[key] = Array.isArray(value) ? String(value[0]) : String(value);
      }
    };
    collect(request.body);
    collect(request.query);

    // Network from the PATH first, query second.
    //
    // The query form (?network=ayet) is simpler, and it collides with
    // networks that sign the sorted query string: they hash the parameters
    // THEY sent, so a parameter we appended to the callback URL is not in
    // their digest and its presence in ours fails every callback. The path
    // form carries the same information outside the query entirely, which
    // sidesteps that; excludeFromSignature covers whoever still uses the
    // query form.
    const pathNetwork = (request.path || "")
      .split("/")
      .filter((segment) => segment.length > 0)
      .pop();
    const network = (
      pathNetwork && pathNetwork.toLowerCase() !== "offerwallcallback" ?
        pathNetwork :
        String(query.network || "")
    ).trim().toLowerCase();

    if (!network) {
      response.status(400).send("missing network");
      return;
    }

    // The first proxy hop is the partner; anything after it is ours. Taking
    // the last entry instead would read our own load balancer's address and
    // make every allowlist match nothing.
    const forwarded = String(request.headers["x-forwarded-for"] || "");
    const sourceIp = forwarded.split(",")[0]?.trim() || request.ip || null;

    const firestore = getFirestore();
    // serverConfig, not config: this document holds the shared secrets, and
    // every document in `config` is readable by any signed-in user.
    const configSnapshot = await firestore
      .collection(OFFERWALL_SECRETS_COLLECTION)
      .doc(OFFERWALL_SECRETS_DOC)
      .get();

    // Header names are lower-cased by Node, but normalise anyway so a
    // config that names "X-Ayetstudios-Security-Hash" in its dashboard
    // casing still resolves.
    const headers: Record<string, string> = {};
    for (const [key, value] of Object.entries(request.headers)) {
      if (typeof value === "string") headers[key.toLowerCase()] = value;
      else if (Array.isArray(value)) headers[key.toLowerCase()] = String(value[0]);
    }

    // The query string exactly as received. request.url keeps the original
    // encoding, which is the whole point for sorted-query signing.
    const rawQuery = String(request.url || "").split("?")[1] ?? "";

    const validation = validatePostback({
      network,
      query,
      sourceIp,
      headers,
      rawQuery,
      config: configSnapshot.exists ? configSnapshot.data() : null,
    });

    if (!validation.ok) {
      // Logged with the network and the reason, never with the query - a
      // rejected request is exactly the one whose parameters are least
      // trustworthy to write anywhere.
      console.error("Offerwall postback rejected", {
        network,
        rejection: validation.rejection,
        sourceIp,
      });

      // TEMPORARY - REMOVE ONCE TAPJOY IS CREDITING.
      //
      // A bare "bad_signature" cannot tell a wrong secret from a wrong
      // template, and guessing between the two costs a dashboard round trip
      // each time. This prints enough to settle it in one callback.
      //
      // THE SECRET IS NEVER PRINTED. What goes out is a fingerprint - the
      // first 8 hex of its SHA-256 - which is enough to compare the stored
      // value against a known one (is this the SDK key by mistake?) and
      // useless for signing anything. The rest is what the partner already
      // sent us in the clear.
      if (validation.rejection === "bad_signature") {
        try {
          const debugConfig = resolveNetwork(
            configSnapshot.exists ? configSnapshot.data() : null,
            network
          );
          if (debugConfig) {
            const expected = buildSignature(query, debugConfig, rawQuery);
            const received = debugConfig.signatureHeader ?
              headers[debugConfig.signatureHeader.toLowerCase()] :
              query[debugConfig.paramNames.signature];
            const template = debugConfig.signatureTemplate.replace(
              /\{(\w+)\}/g,
              (_m, key: string) =>
                key === "secret" ?
                  "<SECRET>" :
                  `${key}=${query[key] ?? "<MISSING>"}`
            );
            console.error("Offerwall signature debug", {
              scheme: debugConfig.scheme,
              template: debugConfig.signatureTemplate,
              templateFilled: template,
              expected,
              received,
              secretLength: debugConfig.secret.length,
              secretFingerprint: createHash("sha256")
                .update(debugConfig.secret)
                .digest("hex")
                .slice(0, 8),
              params: Object.keys(query).sort().join(","),
              query,
            });
          }
        } catch (error) {
          console.error("Offerwall signature debug failed", error);
        }
      }

      const status =
        validation.rejection === "bad_signature" ||
        validation.rejection === "ip_not_allowed" ?
          403 :
          validation.rejection === "unknown_network" ||
          validation.rejection === "network_disabled" ||
          validation.rejection === "network_misconfigured" ?
            404 :
            400;
      response.status(status).send(validation.rejection);
      return;
    }

    const {parsed} = validation;
    const userRef = firestore.collection(USERS_COLLECTION).doc(parsed.uid);
    const txRef = firestore
      .collection(OFFERWALL_TRANSACTIONS_COLLECTION)
      .doc(offerwallTransactionId(network, parsed.transactionId));

    try {
      const result = await firestore.runTransaction(async (transaction) => {
        const [txDoc, userDoc] = await Promise.all([
          transaction.get(txRef),
          transaction.get(userRef),
        ]);

        // Already settled. Not an error - it is the normal shape of a
        // network retrying a call whose response it never received.
        if (txDoc.exists) {
          return {outcome: "duplicate" as const, pointsAwarded: 0};
        }

        if (!userDoc.exists) {
          transaction.set(txRef, {
            network,
            transactionId: parsed.transactionId,
            uid: parsed.uid,
            points: 0,
            status: "rejected_unknown_user",
            raw: parsed.raw,
            createdAt: FieldValue.serverTimestamp(),
          });
          return {outcome: "unknown_user" as const, pointsAwarded: 0};
        }

        const award = buildAward(
          Number(userDoc.get(FIELD_POINTS) || 0),
          Number(userDoc.get(FIELD_XP) || 0),
          {
            source: "OFFERWALL",
            basePoints: parsed.points,
            baseXp: 0,
            metadata: {
              network,
              transactionId: parsed.transactionId,
              chargeback: parsed.isChargeback,
            },
            storedLevel: Number(userDoc.get(FIELD_LEVEL) || 1),
            // OFFERWALL is one of the few MULTIPLIER_ELIGIBLE sources, so
            // the user's active Points buff is passed through here - this is
            // the earning path that buff was designed for. buildAward still
            // decides eligibility from the source, not from this value.
            activeMultiplier: activeMultiplier(
              userDoc.get(FIELD_ACTIVE_BUFF) as PointsBuff | undefined,
              Date.now()
            ),
          }
        );

        // A chargeback can take a balance negative, and that is correct.
        // Clamping at zero would let somebody redeem against a completion
        // and keep the stars when the advertiser reversed it - the reversal
        // has to land somewhere, and the account that banked the credit is
        // the honest place for it.
        writeAward(
          transaction,
          userRef,
          userRef
            .collection(REWARD_EVENTS_SUBCOLLECTION)
            .doc(`offerwall:${network}:${parsed.transactionId}`),
          award
        );

        transaction.set(txRef, {
          network,
          transactionId: parsed.transactionId,
          uid: parsed.uid,
          points: award.pointsAwarded,
          status: parsed.isChargeback ? "chargeback" : "applied",
          raw: parsed.raw,
          createdAt: FieldValue.serverTimestamp(),
        });

        return {outcome: "applied" as const, pointsAwarded: award.pointsAwarded};
      });

      console.log("Offerwall postback", {
        network,
        uid: parsed.uid,
        transactionId: parsed.transactionId,
        ...result,
      });

      // Every outcome here is final, so all three are answered with the body
      // the network treats as success. Retrying could not improve any of
      // them, and the audit row is what makes the rejected case recoverable.
      response.status(200).send(validation.successBody);
    } catch (error) {
      // The only case worth a retry: the write itself failed. A 500 is what
      // makes the network try again, which is the behaviour we want.
      console.error("Offerwall postback failed", {
        network,
        uid: parsed.uid,
        transactionId: parsed.transactionId,
        error: String(error),
      });
      response.status(500).send("error");
    }
  }
);
