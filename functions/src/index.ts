import * as functions from "firebase-functions/v2";
import * as admin from "firebase-admin";
import {CallableRequest} from "firebase-functions/v2/https";
import {onSchedule} from "firebase-functions/v2/scheduler";
admin.initializeApp();

export const dailyReset = onSchedule("every day 00:00", async (_event) => {
  const usersRef = admin.firestore().collection("users");
  const usersSnapshot = await usersRef.get();

  const batch = admin.firestore().batch();
  usersSnapshot.forEach((doc) => {
    batch.update(doc.ref, {quiz_attempts: 0});
 });

  await batch.commit();
  console.log("Daily quiz attempts reset");
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
