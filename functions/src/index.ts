import * as functions from "firebase-functions/v2";
import * as admin from "firebase-admin";
import {CallableRequest} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
admin.initializeApp();

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
