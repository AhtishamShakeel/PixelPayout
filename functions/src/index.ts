import * as functions from 'firebase-functions/v2';
import * as admin from 'firebase-admin';
import { CallableRequest } from 'firebase-functions/v2/https';

admin.initializeApp();

// ✅ Fix for Scheduled Daily Reset Function
export const dailyReset = functions.scheduler.onSchedule(
  { schedule: 'every day 00:00', timeZone: 'UTC' },
  async (event) => {
    const resetRef = admin.firestore().collection("metadata").doc("daily_resets");
    await resetRef.set({ count: 0, lastReset: admin.firestore.FieldValue.serverTimestamp() });

    console.log('Daily counters reset');
  }
);


// ✅ Fix for Checking If Email Exists
export const checkEmailExists = functions.https.onCall(async (request: CallableRequest) => {
  const email: string = request.data.email;

  try {
    await admin.auth().getUserByEmail(email);
    return { exists: true }; // ✅ Email exists
  } catch (error: any) {
    if (error.code === 'auth/user-not-found') {
      return { exists: false }; // ❌ Email does not exist
    } else {
      throw new functions.https.HttpsError('internal', error.message);
    }
  }
});
