/**
 * Backfills `affectsPoints` onto ledger entries written before the field
 * existed.
 *
 * Why this is needed at all: the wallet's Star activity list queries
 * `where("affectsPoints", "==", true)`. Firestore equality filters do not
 * match documents that lack the field, so without this every reward event
 * written before the flag shipped is invisible in the wallet - not wrong, but
 * silently absent, which is worse.
 *
 * Idempotent: entries that already carry the flag are skipped, so it is safe
 * to run more than once, and safe to run again after a partial failure.
 *
 * Uses a collection-group query, so it covers every user's subcollection in
 * one pass. That needs no composite index - a single-field collection-group
 * index on `finalPoints` is created automatically.
 *
 *   cd functions && npm run build
 *   GOOGLE_APPLICATION_CREDENTIALS=key.json \
 *     node lib/tools/backfillAffectsPoints.js <projectId>
 *
 * Add --dry-run to count what would change without writing anything.
 */
import * as admin from "firebase-admin";

const BATCH_SIZE = 400;

async function main() {
  const args = process.argv.slice(2);
  const dryRun = args.includes("--dry-run");
  // Optional for the same reason as the seed tool: a service-account key
  // already names its project, and a mismatched id here can only do harm.
  const explicitProjectId =
    process.env.GCLOUD_PROJECT ||
    args.find((a) => !a.startsWith("--"));

  if (!explicitProjectId && process.env.FIRESTORE_EMULATOR_HOST) {
    console.error(
      "Running against the emulator needs an explicit project id:\n" +
      "  node lib/tools/backfillAffectsPoints.js <projectId> [--dry-run]"
    );
    process.exit(1);
  }

  admin.initializeApp(
    explicitProjectId ? {projectId: explicitProjectId} : undefined
  );

  const projectId = admin.app().options.projectId || explicitProjectId;
  const db = admin.firestore();

  console.log(
    `Backfilling affectsPoints in "${projectId}"` +
    (dryRun ? " (DRY RUN - nothing will be written)" : "")
  );

  let scanned = 0;
  let updated = 0;
  let cursor: admin.firestore.QueryDocumentSnapshot | undefined;

  // Paged rather than read whole: a ledger spans every user and every event
  // they have ever earned, which is the one collection here that has no
  // natural ceiling.
  for (;;) {
    let query = db.collectionGroup("rewardEvents")
      .orderBy(admin.firestore.FieldPath.documentId())
      .limit(BATCH_SIZE);
    if (cursor) query = query.startAfter(cursor);

    const snap = await query.get();
    if (snap.empty) break;

    const batch = db.batch();
    let pending = 0;

    for (const doc of snap.docs) {
      scanned++;
      if (doc.get("affectsPoints") !== undefined) continue;

      const points = Number(doc.get("finalPoints") || 0);
      if (!dryRun) {
        batch.set(doc.ref, {affectsPoints: points !== 0}, {merge: true});
      }
      pending++;
      updated++;
    }

    if (pending > 0 && !dryRun) await batch.commit();

    cursor = snap.docs[snap.docs.length - 1];
    if (snap.size < BATCH_SIZE) break;

    console.log(`  scanned ${scanned}, ${updated} needed the flag`);
  }

  console.log(
    `\nDone. Scanned ${scanned} ledger entries, ` +
    `${dryRun ? "would update" : "updated"} ${updated}.`
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
