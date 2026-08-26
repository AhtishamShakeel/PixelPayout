/**
 * Verifies firestore.rules actually enforces what the economy design assumes:
 * a client can read its own user doc and its own reward ledger, and can never
 * write to either directly - only Cloud Functions (Admin SDK, which bypasses
 * rules entirely) are allowed to mutate points/xp/level/rewardEvents.
 *
 * smoke.ts never exercises this: its "client" calls only go through
 * httpsCallable (admin-privileged) or the Admin SDK directly (rules-exempt).
 * This is the only place a real rules-enforced client read/write happens.
 *
 * Run via: npm run test:rules   (from functions/)
 */
import * as fs from "fs";
import * as path from "path";
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, query, where,
} from "firebase/firestore";

const PROJECT_ID = "pixelpayout-check";
const RULES_PATH = path.resolve(__dirname, "../../../firestore.rules");

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(`  FAIL  ${desc}${detail !== undefined ? " -- " + String(detail) : ""}`);
}

async function expectSucceeds(desc: string, fn: () => Promise<unknown>) {
  try {
    await assertSucceeds(fn());
    ok(desc);
  } catch (e) {
    fail(desc, (e as Error).message);
  }
}

async function expectFails(desc: string, fn: () => Promise<unknown>) {
  try {
    await assertFails(fn());
    ok(desc);
  } catch (e) {
    fail(desc, (e as Error).message);
  }
}

async function run() {
  console.log("=== Firestore rules test: users/{uid} + rewardEvents ===\n");

  const testEnv: RulesTestEnvironment = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync(RULES_PATH, "utf8"),
      host: "127.0.0.1",
      port: 8080,
    },
  });

  const OWNER_UID = "owner-uid";
  const OTHER_UID = "other-uid";
  const NEW_SIGNUP_UID = "new-signup-uid";

  // Seed data bypassing rules - equivalent to what the Admin SDK (Cloud
  // Functions) does today when it writes points/rewardEvents.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const seedDb = context.firestore();
    await setDoc(doc(seedDb, "users", OWNER_UID), {points: 42, displayName: "Owner"});
    await setDoc(doc(seedDb, "users", OTHER_UID), {points: 7, displayName: "Other"});
    await setDoc(doc(seedDb, "users", OWNER_UID, "rewardEvents", "event1"), {
      source: "QUIZ",
      basePoints: 10,
      finalPoints: 10,
    });
  });

  const ownerDb = testEnv.authenticatedContext(OWNER_UID).firestore();
  const otherDb = testEnv.authenticatedContext(OTHER_UID).firestore();
  const anonDb = testEnv.unauthenticatedContext().firestore();

  await expectSucceeds(
    "owner can read their own user doc",
    () => getDoc(doc(ownerDb, "users", OWNER_UID))
  );

  await expectFails(
    "a different signed-in user cannot read owner's user doc",
    () => getDoc(doc(otherDb, "users", OWNER_UID))
  );

  await expectFails(
    "unauthenticated request cannot read any user doc",
    () => getDoc(doc(anonDb, "users", OWNER_UID))
  );

  await expectFails(
    "owner cannot write points directly on their own doc",
    () => updateDoc(doc(ownerDb, "users", OWNER_UID), {points: 999999})
  );

  await expectFails(
    "owner cannot create a doc for someone else's uid",
    () => setDoc(doc(ownerDb, "users", "self-created-uid"), {points: 0})
  );

  await expectFails(
    "owner cannot delete their own user doc",
    () => deleteDoc(doc(ownerDb, "users", OWNER_UID))
  );

  // --- clients can no longer create user documents at all ---
  {
    // Signup goes through the completeSignup function now, so there is no
    // client-create carve-out left to get wrong.
    const signupDb = testEnv.authenticatedContext(NEW_SIGNUP_UID).firestore();

    await expectFails(
      "a client cannot create its own user document, even with correct starting values",
      () => setDoc(doc(signupDb, "users", NEW_SIGNUP_UID), {
        displayName: "New User",
        email: "new@test.local",
        androidId: "abc123",
        hasUsedReferral: false,
        points: 0,
        xp: 0,
        level: 1,
        referralCode: "ABCDEF",
        referralRewardClaimed: false,
      })
    );

    await expectFails(
      "a client certainly cannot create one with a fabricated balance",
      () => setDoc(doc(signupDb, "users", NEW_SIGNUP_UID), {
        points: 999999,
        xp: 999999,
        level: 30,
        referralRewardClaimed: false,
      })
    );
  }

  // --- the exact production vulnerability this replaces: cross-user points writes ---
  await expectFails(
    "a different signed-in user cannot increment another user's points directly " +
      "(the old production rules allowed exactly this)",
    () => updateDoc(doc(otherDb, "users", OWNER_UID), {points: 43}) // one more than seeded 42
  );

  // --- collection-wide listing, previously allowed for any signed-in user ---
  await expectFails(
    "a signed-in user cannot list/enumerate the whole users collection",
    () => getDocs(collection(otherDb, "users"))
  );

  await expectSucceeds(
    "owner can read their own rewardEvents",
    () => getDocs(collection(ownerDb, "users", OWNER_UID, "rewardEvents"))
  );

  await expectFails(
    "a different signed-in user cannot read owner's rewardEvents",
    () => getDocs(collection(otherDb, "users", OWNER_UID, "rewardEvents"))
  );

  await expectFails(
    "owner cannot fabricate their own rewardEvents entry",
    () => setDoc(doc(ownerDb, "users", OWNER_UID, "rewardEvents", "fake-event"), {
      source: "QUIZ",
      basePoints: 999999,
      finalPoints: 999999,
    })
  );

  await expectFails(
    "an unrelated top-level collection is denied entirely",
    () => getDocs(collection(ownerDb, "someOtherCollection"))
  );

  // --- redemptions (top-level): owner-readable, never client-writable ---
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const seedDb = context.firestore();
    await setDoc(doc(seedDb, "redemptions", "r1"), {
      uid: OWNER_UID,
      status: "pending",
      pointsCost: 1000,
      payoutNumber: "03001234567",
    });
    await setDoc(doc(seedDb, "redemptions", "r2"), {
      uid: OTHER_UID,
      status: "pending",
      pointsCost: 500,
      payoutNumber: "03009999999",
    });
    await setDoc(doc(seedDb, "users", OWNER_UID, "gameSessions", "s1"), {
      gameId: "floppy_bird",
      consumed: false,
    });
  });

  await expectSucceeds(
    "owner can read their own redemption",
    () => getDoc(doc(ownerDb, "redemptions", "r1"))
  );
  await expectFails(
    "a user cannot read someone else's redemption (contains a payout number)",
    () => getDoc(doc(otherDb, "redemptions", "r1"))
  );
  await expectSucceeds(
    "owner can list their own redemptions when the query is filtered by uid",
    () => getDocs(query(collection(ownerDb, "redemptions"), where("uid", "==", OWNER_UID)))
  );
  await expectFails(
    "an unfiltered listing of all redemptions is denied",
    () => getDocs(collection(ownerDb, "redemptions"))
  );
  await expectFails(
    "a user cannot list another user's redemptions",
    () => getDocs(query(collection(ownerDb, "redemptions"), where("uid", "==", OTHER_UID)))
  );
  await expectFails(
    "owner cannot create a redemption directly (would be a free payout)",
    () => setDoc(doc(ownerDb, "redemptions", "forged"), {
      uid: OWNER_UID,
      status: "approved",
      pointsCost: 0,
    })
  );
  await expectFails(
    "owner cannot approve their own pending redemption",
    () => updateDoc(doc(ownerDb, "redemptions", "r1"), {status: "approved"})
  );

  // --- admins get NO special access from the browser (least privilege) ---
  {
    // The admin tool reads through the listRedemptions function instead, so
    // holding the admin claim must buy a browser client nothing here.
    const adminDb = testEnv
      .authenticatedContext("admin-uid", {admin: true})
      .firestore();

    await expectFails(
      "even an admin cannot list all redemptions from the browser",
      () => getDocs(query(
        collection(adminDb, "redemptions"),
        where("status", "==", "pending")
      ))
    );
    await expectFails(
      "even an admin cannot read another user's redemption from the browser",
      () => getDoc(doc(adminDb, "redemptions", "r1"))
    );
    await expectFails(
      "even an admin cannot write a redemption directly (must use resolveRedemption)",
      () => updateDoc(doc(adminDb, "redemptions", "r1"), {status: "approved"})
    );
    await expectFails(
      "even an admin cannot read arbitrary user documents",
      () => getDoc(doc(adminDb, "users", OWNER_UID))
    );
  }

  await expectFails(
    "owner cannot read game sessions",
    () => getDocs(collection(ownerDb, "users", OWNER_UID, "gameSessions"))
  );
  await expectFails(
    "owner cannot forge a game session",
    () => setDoc(doc(ownerDb, "users", OWNER_UID, "gameSessions", "forged"), {
      gameId: "floppy_bird",
      consumed: false,
    })
  );
  await expectFails(
    "owner cannot un-consume a used game session",
    () => updateDoc(doc(ownerDb, "users", OWNER_UID, "gameSessions", "s1"), {consumed: false})
  );

  // --- config / redemptionOptions: read-only for authenticated users ---
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const seedDb = context.firestore();
    await setDoc(doc(seedDb, "config", "app"), {maintenanceMode: false});
    await setDoc(doc(seedDb, "redemptionOptions", "option1"), {pointsCost: 100});
  });

  await expectSucceeds(
    "authenticated user can read config",
    () => getDoc(doc(ownerDb, "config", "app"))
  );
  await expectFails(
    "unauthenticated user cannot read config",
    () => getDoc(doc(anonDb, "config", "app"))
  );
  await expectFails(
    "no client can write config",
    () => setDoc(doc(ownerDb, "config", "app"), {maintenanceMode: true})
  );

  await expectSucceeds(
    "authenticated user can read redemptionOptions",
    () => getDoc(doc(ownerDb, "redemptionOptions", "option1"))
  );
  await expectSucceeds(
    "authenticated user can LIST redemptionOptions (what the redemption screen does)",
    () => getDocs(collection(ownerDb, "redemptionOptions"))
  );
  await expectFails(
    "unauthenticated user cannot list redemptionOptions",
    () => getDocs(collection(anonDb, "redemptionOptions"))
  );
  await expectFails(
    "unauthenticated user cannot read redemptionOptions",
    () => getDoc(doc(anonDb, "redemptionOptions", "option1"))
  );
  await expectFails(
    "no client can write redemptionOptions",
    () => setDoc(doc(ownerDb, "redemptionOptions", "option1"), {pointsCost: 1})
  );

  await testEnv.cleanup();

  console.log(`\n=== ${passed} passed, ${failed} failed ===`);
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((e) => {
  console.error("Rules test crashed:", e);
  process.exit(1);
});
