/**
 * Seeds a TEST offerwall network plus its catalogue entry, so the postback
 * endpoint can be proved end to end before any real network is approved.
 *
 * Exists because both documents are nested maps, and typing a nested map by
 * hand in the Firestore console is slow and easy to get subtly wrong - a
 * mistyped `signatureTemplate` produces a 403 that looks identical to a
 * genuine signature failure, which is the least debuggable outcome available.
 *
 * WRITES WITH MERGE, unlike seedRedemptionOptions. That file owns its whole
 * catalogue; this one adds a test network beside whatever real ones are
 * already configured, and overwriting those would be an unpleasant surprise.
 *
 * Run:
 *   cd functions
 *   npm run seed:offerwall              # writes the test network
 *   npm run seed:offerwall -- --remove  # takes it out again
 *
 * REMOVE IT BEFORE LAUNCH. The secret below is in source control, so anybody
 * reading this repo can mint stars through this network while it is enabled.
 * That is acceptable for a test project and never acceptable in production.
 */
import * as admin from "firebase-admin";
import {
  OFFERWALL_SECRETS_COLLECTION,
  OFFERWALL_SECRETS_DOC,
  OFFERWALL_WALLS_DOC,
} from "../economy/offerwall";

const TEST_NETWORK = "testnet";

/** Deliberately obvious, so it is never mistaken for a real credential. */
const TEST_SECRET = "test-secret-do-not-ship";

const TEST_CONFIG = {
  enabled: true,
  secret: TEST_SECRET,
  scheme: "md5",
  signatureTemplate: "{uid}:{transactionId}:{amount}:{secret}",
  paramNames: {
    uid: "uid",
    transactionId: "transactionId",
    amount: "amount",
    signature: "signature",
  },
  pointsPerUnit: 1,
  maxPoints: 1000,
  successBody: "1",
};

async function main() {
  const remove = process.argv.includes("--remove");
  const explicitProjectId =
    process.env.GCLOUD_PROJECT ||
    process.env.FIREBASE_PROJECT ||
    process.argv.slice(2).find((a) => !a.startsWith("--"));

  const emulator = process.env.FIRESTORE_EMULATOR_HOST;
  if (!explicitProjectId && emulator) {
    console.error(
      "Running against the emulator needs an explicit project id:\n" +
      "  node lib/tools/seedOfferwall.js <projectId>"
    );
    process.exit(1);
  }

  admin.initializeApp(
    explicitProjectId ? {projectId: explicitProjectId} : undefined
  );
  const projectId = admin.app().options.projectId || explicitProjectId;
  const db = admin.firestore();

  const secretsRef = db
    .collection(OFFERWALL_SECRETS_COLLECTION)
    .doc(OFFERWALL_SECRETS_DOC);

  if (remove) {
    await secretsRef.set(
      {[TEST_NETWORK]: admin.firestore.FieldValue.delete()},
      {merge: true}
    );
    console.log(`Removed "${TEST_NETWORK}" from ${OFFERWALL_SECRETS_COLLECTION}/${OFFERWALL_SECRETS_DOC}`);
    console.log("Real networks, if any, were left alone.");
    return;
  }

  console.log(`Seeding the TEST offerwall network into "${projectId}" ` +
    `(${emulator ? "emulator " + emulator : "LIVE Firestore"})\n`);

  await secretsRef.set({[TEST_NETWORK]: TEST_CONFIG}, {merge: true});
  console.log(`  ${OFFERWALL_SECRETS_COLLECTION}/${OFFERWALL_SECRETS_DOC}  ->  ${TEST_NETWORK}`);

  // The catalogue entry is NOT seeded. A test network has no wall for a user
  // to open - it exists only to be called with curl - and putting a tile on
  // the Rewards screen that opens nothing would be a worse bug than the one
  // this tool is here to rule out.
  console.log(`  ${OFFERWALL_WALLS_DOC}  ->  not touched (test network has no wall)`);

  console.log("\nNow prove the endpoint. Get a UID from");
  console.log("Firebase Console > Authentication > Users:\n");
  // MYUID, not UID: bash defines UID as a read-only variable, so the obvious
  // name fails with "UID: readonly variable" and looks like a broken recipe.
  console.log("  MYUID=<your firebase uid>");
  // A FIXED string, not $(date +%s).
  //
  // This recipe used to build the id from the clock, which meant re-running
  // the block produced a new transaction id every second. Each one correctly
  // paid - and that looked exactly like idempotency being broken, which is
  // the single most alarming thing this endpoint could appear to do. The
  // whole point of the second run is to send the SAME id twice, so the id
  // has to be something that does not move.
  console.log("  TID=payment-test-1");
  console.log("  AMT=25");
  console.log(`  SECRET=${TEST_SECRET}`);
  console.log("  SIG=$(printf '%s:%s:%s:%s' \"$MYUID\" \"$TID\" \"$AMT\" \"$SECRET\" | md5sum | cut -d' ' -f1)");
  console.log(`  curl "https://us-central1-${projectId}.cloudfunctions.net/offerwallCallback?network=${TEST_NETWORK}&uid=$MYUID&transactionId=$TID&amount=$AMT&signature=$SIG"`);
  console.log("\nExpect: 1, and the user's points up by 25.\n");
  console.log("THEN RUN THE CURL LINE ON ITS OWN, AGAIN.");
  console.log("  It must print 1 and the points must NOT move a second time.");
  console.log("  Re-run only the curl line - re-running the whole block with a");
  console.log("  fresh TID is a different transaction and is meant to pay.");
  console.log(`\nWhen finished:  npm run seed:offerwall -- --remove`);
}

main().catch((err) => {
  // Credentials and project id are the two ways this fails, and both used to
  // surface as a raw stack trace that reads like a bug in the script. They
  // are setup steps, so they get told apart and answered here - a seed that
  // fails silently is worse than one that does not run, because the next
  // thing you do is test against data that was never written.
  const message = String(err?.message || err);

  if (message.includes("Could not load the default credentials")) {
    console.error([
      "",
      "No Google credentials found.",
      "",
      "Firebase Console > gear icon > Project settings > Service accounts",
      "> Generate new private key. Save the file OUTSIDE this repo, then:",
      "",
      "  export GOOGLE_APPLICATION_CREDENTIALS=\"/c/keys/pixelpayout-key.json\"",
      "  npm run seed:offerwall -- pixelpayout-check",
      "",
      "Never commit that file. It can rewrite every balance in the app.",
      "",
    ].join("\n"));
    process.exit(1);
  }

  if (message.includes("Unable to detect a Project Id")) {
    console.error([
      "",
      "No project id. Pass it as an argument:",
      "",
      "  npm run seed:offerwall -- pixelpayout-check",
      "",
    ].join("\n"));
    process.exit(1);
  }

  console.error(err);
  process.exit(1);
});
