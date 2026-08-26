import * as functions from "firebase-functions/v2";
import * as admin from "firebase-admin";
import {CallableRequest} from "firebase-functions/v2/https";
import {onDocumentUpdated} from "firebase-functions/v2/firestore";
import {onSchedule} from "firebase-functions/v2/scheduler";
admin.initializeApp();

const USERS_COLLECTION = "users";
const FIELD_POINTS = "points";
const FIELD_QUIZ_ATTEMPTS = "quiz_attempts";
const FIELD_HAS_USED_REFERRAL = "hasUsedReferral";
const FIELD_REFERRED_BY = "referredBy";
const FIELD_REFERRAL_CODE = "referralCode";
const FIELD_REFERRAL_REWARD_CLAIMED = "referralRewardClaimed";
const MAX_DAILY_QUIZ_ATTEMPTS = 10;
const QUIZ_CORRECT_REWARD_POINTS = 10;
const REFERRED_USER_REWARD_POINTS = 50;
const REFERRER_REWARD_POINTS = 100;
const REFERRAL_REWARD_UNLOCK_POINTS = 100;
const MAX_GAME_SCORE = 1_000_000;

// Changed from daily to weekly as a safety net backup
export const weeklyReset = onSchedule("every monday 00:00", async (_event) => {
  const usersRef = admin.firestore().collection("users");
  const usersSnapshot = await usersRef.get();

  const batch = admin.firestore().batch();
  usersSnapshot.forEach((doc) => {
    // Update both the quiz_attempts counter and last_reset_time
    batch.update(doc.ref, {
      quiz_attempts: 0,
      last_reset_time: admin.firestore.Timestamp.now()
    });
  });

  await batch.commit();
  console.log("Weekly quiz attempts reset completed - all users reset");
});


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

// New function for on-demand quiz attempt reset
export const checkAndResetQuizAttempts = functions.https.onCall(async (request: CallableRequest) => {
  // Ensure user is authenticated
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }
  
  const userId = request.auth.uid;
  const userRef = admin.firestore().collection("users").doc(userId);
  
  // Get the current user data
  const userDoc = await userRef.get();
  if (!userDoc.exists) {
    throw new functions.https.HttpsError("not-found", "User document not found");
  }
  
  const userData = userDoc.data();
  
  // Get server timestamp (not client timestamp)
  const now = admin.firestore.Timestamp.now();
  const lastResetTime = userData?.last_reset_time || admin.firestore.Timestamp.fromDate(new Date(0));
  
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

export const claimReward = functions.https.onCall(async (request: CallableRequest) => {
  if (!request.auth) {
    throw new functions.https.HttpsError("unauthenticated", "User must be logged in");
  }

  const rewardType = String(request.data.rewardType || "").trim();
  const userId = request.auth.uid;
  let pointsAwarded = 0;
  let incrementQuizAttempt = false;

  if (rewardType === "quiz") {
    incrementQuizAttempt = true;
    pointsAwarded = request.data.wasCorrect === true ? QUIZ_CORRECT_REWARD_POINTS : 0;
  } else if (rewardType === "game") {
    const gameId = String(request.data.gameId || "").trim();
    const score = Number(request.data.score || 0);

    if (!Number.isFinite(score) || score < 0 || score > MAX_GAME_SCORE) {
      throw new functions.https.HttpsError("invalid-argument", "Invalid game score");
    }

    if (gameId === "floppy_bird") {
      pointsAwarded = Math.floor(score);
    } else if (gameId === "game_2048") {
      pointsAwarded = Math.floor(score / 10);
    } else {
      throw new functions.https.HttpsError("invalid-argument", "Unknown game reward");
    }
  } else {
    throw new functions.https.HttpsError("invalid-argument", "Unknown reward type");
  }

  const userRef = admin.firestore().collection(USERS_COLLECTION).doc(userId);
  const result = await admin.firestore().runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    const currentPoints = Number(userDoc.get(FIELD_POINTS) || 0);
    const currentAttempts = Number(userDoc.get(FIELD_QUIZ_ATTEMPTS) || 0);

    if (incrementQuizAttempt && currentAttempts >= MAX_DAILY_QUIZ_ATTEMPTS) {
      throw new functions.https.HttpsError("failed-precondition", "Daily quiz limit reached");
    }

    const updateData: Record<string, admin.firestore.FieldValue> = {
      [FIELD_POINTS]: admin.firestore.FieldValue.increment(pointsAwarded),
    };

    if (incrementQuizAttempt) {
      updateData[FIELD_QUIZ_ATTEMPTS] = admin.firestore.FieldValue.increment(1);
    }

    transaction.update(userRef, updateData);

    return {
      pointsAwarded,
      totalPoints: currentPoints + pointsAwarded,
      attempts: incrementQuizAttempt ? currentAttempts + 1 : currentAttempts,
    };
  });

  return {
    success: true,
    ...result,
  };
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
  const firestore = admin.firestore();

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

  await firestore.runTransaction(async (transaction) => {
    const userDoc = await transaction.get(userRef);

    if (!userDoc.exists) {
      throw new functions.https.HttpsError("not-found", "User document not found");
    }

    if (userDoc.get(FIELD_HAS_USED_REFERRAL) === true) {
      throw new functions.https.HttpsError("failed-precondition", "Referral already used");
    }

    transaction.update(userRef, {
      [FIELD_HAS_USED_REFERRAL]: true,
      [FIELD_REFERRED_BY]: referrerId,
      [FIELD_POINTS]: admin.firestore.FieldValue.increment(REFERRED_USER_REWARD_POINTS),
    });
  });

  return {status: "success"};
});

export const rewardReferrerOnPointsThreshold = onDocumentUpdated(
  `${USERS_COLLECTION}/{userId}`,
  async (event) => {
    const userId = event.params.userId;
    const before = event.data?.before;
    const after = event.data?.after;

    if (!before || !after) {
      console.log("Referral reward skipped: missing before/after snapshots", {userId});
      return;
    }

    const beforePoints = Number(before.get(FIELD_POINTS) || 0);
    const afterPoints = Number(after.get(FIELD_POINTS) || 0);
    const referredBy = after.get(FIELD_REFERRED_BY) as string | undefined;
    const referralRewardClaimed = after.get(FIELD_REFERRAL_REWARD_CLAIMED) === true;

    console.log("Referral reward threshold check", {
      userId,
      beforePoints,
      afterPoints,
      referredBy,
      referralRewardClaimed,
    });

    if (
      beforePoints >= REFERRAL_REWARD_UNLOCK_POINTS ||
      afterPoints < REFERRAL_REWARD_UNLOCK_POINTS ||
      !referredBy ||
      referralRewardClaimed
    ) {
      console.log("Referral reward skipped: conditions not met", {
        userId,
        beforePoints,
        afterPoints,
        referredBy,
        referralRewardClaimed,
      });
      return;
    }

    const firestore = admin.firestore();
    const referredUserRef = after.ref;
    const referrerRef = firestore.collection(USERS_COLLECTION).doc(referredBy);

    await firestore.runTransaction(async (transaction) => {
      const latestReferredUser = await transaction.get(referredUserRef);

      if (latestReferredUser.get(FIELD_REFERRAL_REWARD_CLAIMED) === true) {
        console.log("Referral reward skipped: already claimed in transaction", {userId});
        return;
      }

      transaction.update(referrerRef, {
        [FIELD_POINTS]: admin.firestore.FieldValue.increment(REFERRER_REWARD_POINTS),
      });

      transaction.update(referredUserRef, {
        [FIELD_REFERRAL_REWARD_CLAIMED]: true,
      });
    });

    console.log("Referral reward applied", {
      referredUserId: userId,
      referrerId: referredBy,
      pointsAwarded: REFERRER_REWARD_POINTS,
    });
  }
);
