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

/**
 * Tapjoy's self-managed currency callback, as configuration.
 *
 * Their verifier is MD5 of id, snuid, currency and the secret key joined by
 * colons, which the generic template scheme expresses without a line of
 * Tapjoy-specific code - the whole point of doing it this way.
 *
 * `pointsPerUnit` stays 1 because the star-per-unit ratio is set in the
 * Tapjoy dashboard, not here. Setting it in both places is how the two end
 * up disagreeing.
 */
function tapjoyConfig(secret: string) {
  return {
    enabled: true,
    secret,
    scheme: "md5",
    signatureTemplate: "{id}:{snuid}:{currency}:{secret}",
    paramNames: {
      uid: "snuid",
      transactionId: "id",
      amount: "currency",
      signature: "verifier",
    },
    pointsPerUnit: 1,
    maxPoints: 5000,
    successBody: "1",
  };
}

/** The catalogue row that puts Tapjoy on the Rewards screen. */
const TAPJOY_WALL = {
  enabled: true,
  type: "tapjoy",
  name: "Tapjoy Offers",
  subtitle: "Apps, games and surveys",
  sortOrder: 1,
  minLevel: 1,
};

/**
 * Writes the Tapjoy secret and its catalogue row together.
 *
 * TOGETHER ON PURPOSE. A wall enabled without its secret is the worst of
 * both: the tile appears, the offerwall opens, the user completes an offer -
 * and the postback is rejected as an unknown network, so they are never
 * paid. Writing one without the other is a state worth making impossible.
 */
async function seedTapjoy(
  db: FirebaseFirestore.Firestore,
  secretsRef: FirebaseFirestore.DocumentReference,
  secret: string
) {
  const batch = db.batch();
  batch.set(secretsRef, {tapjoy: tapjoyConfig(secret)}, {merge: true});
  batch.set(
    db.collection("config").doc(OFFERWALL_WALLS_DOC),
    {walls: {tapjoy: TAPJOY_WALL}},
    {merge: true}
  );
  await batch.commit();

  console.log(`  ${OFFERWALL_SECRETS_COLLECTION}/${OFFERWALL_SECRETS_DOC}  ->  tapjoy (secret written)`);
  console.log(`  config/${OFFERWALL_WALLS_DOC}  ->  tapjoy tile enabled`);
  console.log([
    "",
    "Now in the Tapjoy dashboard, confirm all three:",
    "  1. A placement named exactly:  offerwall",
    "  2. Currency mode:              self-managed",
    "     (Tapjoy-managed keeps the balance on their side and your",
    "      server never hears about a completion.)",
    "  3. Callback URL:",
    "     https://us-central1-pixelpayout-check.cloudfunctions.net/offerwallCallback?network=tapjoy",
    "",
    "Then rebuild and REINSTALL the app - the AD_ID permission is new, and",
    "without it Tapjoy will not match your test device.",
    "",
    "Watch it with:  adb logcat -s TapjoyOfferwall:V Tapjoy:V",
    "",
  ].join("\n"));
}

/**
 * ayeT-Studios, which is the network that broke the original config shape.
 *
 * They sign in an HTTP HEADER rather than a parameter, and they HMAC the
 * request's own query string sorted alphabetically rather than a fixed
 * template - so there is no signatureTemplate here at all. Both were added
 * to the validator for this network; see hmac_sha256_sorted_query.
 *
 * `excludeFromSignature` matters more than it looks: they hash the
 * parameters THEY sent, so the `network` we add to the callback URL is not
 * in their digest. Leaving it in ours would fail every single callback.
 */
function ayetConfig(apiKey: string) {
  return {
    enabled: true,
    secret: apiKey,
    scheme: "hmac_sha256_sorted_query",
    signatureHeader: "X-Ayetstudios-Security-Hash",
    excludeFromSignature: ["network"],
    paramNames: {
      uid: "external_identifier",
      transactionId: "transaction_id",
      amount: "currency_amount",
      signature: "",
      status: "is_chargeback",
    },
    // Reversals arrive as is_chargeback=1 with a positive amount, and carry
    // an "r-" prefixed transaction id - so a reversal is idempotent against
    // its own id and can never collide with the conversion it reverses.
    chargebackValues: ["1"],
    pointsPerUnit: 1,
    maxPoints: 5000,
    // They require HTTP 200; the body itself is not checked.
    successBody: "ok",
  };
}

async function seedAyet(
  db: FirebaseFirestore.Firestore,
  secretsRef: FirebaseFirestore.DocumentReference,
  apiKey: string,
  wallUrl: string
) {
  const batch = db.batch();
  batch.set(secretsRef, {ayet: ayetConfig(apiKey)}, {merge: true});
  batch.set(
    db.collection("config").doc(OFFERWALL_WALLS_DOC),
    {
      walls: {
        ayet: {
          enabled: true,
          type: "web",
          name: "ayeT Offers",
          subtitle: "Apps, surveys and sign-ups",
          urlTemplate: wallUrl,
          sortOrder: 2,
          minLevel: 1,
        },
      },
    },
    {merge: true}
  );
  await batch.commit();

  console.log(`  ${OFFERWALL_SECRETS_COLLECTION}/${OFFERWALL_SECRETS_DOC}  ->  ayet (api key written)`);
  console.log(`  config/${OFFERWALL_WALLS_DOC}  ->  ayeT tile enabled`);
  console.log([
    "",
    "In the ayeT-Studios dashboard, set the offerwall callback to:",
    "",
    "  https://us-central1-pixelpayout-check.cloudfunctions.net/offerwallCallback/ayet",
    "",
    "NOTE THE PATH FORM - /ayet on the end, not ?network=ayet.",
    "ayeT hashes the query string it sends, so a parameter we add to the",
    "query would not be in their digest and every callback would fail. The",
    "path carries it outside the query entirely.",
    "",
    "Package name for the dashboard:  com.createbyte.lootlevel",
    "",
    "Then use their Callback Tester to fire a test conversion before",
    "trusting any of this.",
    "",
  ].join("\n"));
}

async function main() {
  const remove = process.argv.includes("--remove");
  const tapjoy = process.argv.includes("--tapjoy");
  const ayet = process.argv.includes("--ayet");

  const ayetKey = process.env.AYET_API_KEY;
  const ayetUrl = process.env.AYET_WALL_URL;

  // Env var first, so the secret need not appear in shell history at all.
  const tapjoySecret =
    process.env.TAPJOY_SECRET ||
    process.argv
      .find((a) => a.startsWith("--tapjoy-secret="))
      ?.split("=")
      .slice(1)
      .join("=");
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

  if (ayet) {
    if (!ayetKey || !ayetUrl) {
      console.error([
        "",
        "Needs both, from your ayeT-Studios dashboard:",
        "",
        "  AYET_API_KEY   - Settings > API key. Signs the callback; never",
        "                   goes in the app or this repo.",
        "  AYET_WALL_URL  - your offerwall link, with {uid} where the user",
        "                   identifier belongs. It must be https and must",
        "                   contain the literal {uid}.",
        "",
        "  AYET_API_KEY=... AYET_WALL_URL='https://...&external_identifier={uid}' \\",
        "    npm run seed:offerwall -- pixelpayout-check --ayet",
        "",
      ].join("\n"));
      process.exit(1);
    }
    if (!ayetUrl.startsWith("https://") || !ayetUrl.includes("{uid}")) {
      console.error([
        "",
        "AYET_WALL_URL must be https and must contain {uid}.",
        "",
        "The app drops any wall failing either check, so a bad one would",
        "simply never appear rather than appearing and opening nothing.",
        "",
      ].join("\n"));
      process.exit(1);
    }
    console.log(`Configuring ayeT-Studios in "${projectId}"
`);
    await seedAyet(db, secretsRef, ayetKey, ayetUrl);
    return;
  }

  if (tapjoy) {
    if (!tapjoySecret) {
      console.error([
        "",
        "No Tapjoy secret. Get it from the Tapjoy dashboard (it is NOT the",
        "SDK key - that one is public and lives in AppConfig.kt; this one",
        "signs the callback and must never reach the app or this repo).",
        "",
        "  TAPJOY_SECRET=<secret> npm run seed:offerwall -- pixelpayout-check --tapjoy",
        "",
      ].join("\n"));
      process.exit(1);
    }
    console.log(`Configuring Tapjoy in "${projectId}"\n`);
    await seedTapjoy(db, secretsRef, tapjoySecret);
    return;
  }

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
