import * as functions from 'firebase-functions/v2';
import * as admin from 'firebase-admin';

admin.initializeApp();

export const dailyReset = functions.scheduler.onSchedule({
  schedule: 'every day 00:00',
  timeZone: 'UTC',
  retryCount: 2
}, async () => {
  const resetRef = admin.firestore().collection("metadata").doc("daily_resets");
  await resetRef.set({ count: 0, lastReset: admin.firestore.FieldValue.serverTimestamp() });

  console.log('Daily counters reset');
  return null;
});