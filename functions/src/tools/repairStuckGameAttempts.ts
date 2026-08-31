/**
 * One-off repair for accounts locked out of games by checkAndResetQuizAttempts.
 *
 * That callable reset `quiz_attempts` and re-stamped `last_reset_time` while
 * leaving `game_attempts` alone - and the two counters share that stamp. From
 * the first cold start of a new day the stamp said "today" while the game
 * counter still held yesterday's total, so claimReward and startGameSession
 * both read a full allowance that no claim could ever reset. Anyone who had
 * used all ten game runs stayed locked out, permanently, one day rolling into
 * the next.
 *
 * Deleting the callable stops it happening again; it does not free the
 * accounts already stuck, because nothing rewrites `game_attempts` except a
 * claim they are not allowed to make. This does.
 *
 * WHAT IT CHANGES: `game_attempts` -> 0, and only on documents whose stamp is
 * stale (from an earlier UTC day than today). A stale stamp means both
 * counters are already spent as far as the server is concerned, so zeroing
 * one of them gives nothing away - the next claim would have zeroed it too.
 * Documents stamped today are left alone: their counters are genuinely
 * today's, and resetting them would hand out a second allowance.
 *
 * Idempotent, and safe to run more than once.
 *
 *   cd functions && npm run build
 *   node lib/tools/repairStuckGameAttempts.js <projectId>            # counts only
 *   node lib/tools/repairStuckGameAttempts.js <projectId> --apply    # writes
 *
 * WRITING IS OPT-IN, which is the opposite of the --dry-run convention the
 * other tools in this folder use, and deliberately so. Run through npm, the
 * flag does not arrive: `npm run <script> -- --dry-run` never forwards
 * --dry-run, because npm claims that option for itself and silently consumes
 * it. A tool that writes by default would then do a real run while its
 * operator believed they were dry-running it. Opt-in writing makes a
 * swallowed flag fail towards doing nothing.
 *
 * Run it directly with node rather than through npm if you are passing flags
 * at all - npm owns several of the obvious ones.
 */
import * as admin from "firebase-admin";

const BATCH_SIZE = 400;
const MILLIS_PER_DAY = 86_400_000;

/** Whole UTC days since the epoch - the same day number the server counts. */
function utcDayFor(epochMillis: number): number {
  return Math.floor(epochMillis / MILLIS_PER_DAY);
}

async function main() {
  const args = process.argv.slice(2);
  const dryRun = !args.includes("--apply");
  const explicitProjectId =
    process.env.GCLOUD_PROJECT ||
    args.find((a) => !a.startsWith("--"));

  if (!explicitProjectId && process.env.FIRESTORE_EMULATOR_HOST) {
    console.error(
      "Running against the emulator needs an explicit project id:\n" +
      "  node lib/tools/repairStuckGameAttempts.js <projectId> [--apply]"
    );
    process.exit(1);
  }

  admin.initializeApp(
    explicitProjectId ? {projectId: explicitProjectId} : undefined
  );

  const projectId = admin.app().options.projectId || explicitProjectId;
  const db = admin.firestore();

  // Stated plainly, because the whole point of the opt-in flag is that the
  // operator can see which mode they are actually in before anything moves.
  console.log(
    dryRun ?
      `Counting stuck game allowances in ${projectId}.\n` +
        "DRY RUN - nothing will be written. Add --apply to repair.\n" :
      `Repairing stuck game allowances in ${projectId}.\n` +
        "APPLYING - game_attempts will be reset where the day stamp is stale.\n"
  );

  const todayUtc = utcDayFor(Date.now());
  let cursor: admin.firestore.QueryDocumentSnapshot | undefined;
  let scanned = 0;
  let repaired = 0;

  for (;;) {
    // Ordered by document name so the page cursor is stable even while other
    // writes are landing. No index needed.
    let query = db.collection("users")
      .orderBy(admin.firestore.FieldPath.documentId())
      .limit(BATCH_SIZE);
    if (cursor) query = query.startAfter(cursor);

    const snap = await query.get();
    if (snap.empty) break;

    const batch = db.batch();
    let pending = 0;

    for (const doc of snap.docs) {
      scanned++;

      const gameAttempts = Number(doc.get("game_attempts") || 0);
      if (gameAttempts <= 0) continue;

      const stampedAt = doc.get("last_reset_time") as
        admin.firestore.Timestamp | undefined;
      // No stamp at all is already treated as stale by the server, so the
      // counter will reset itself on the next claim. Nothing to do.
      if (!stampedAt) continue;
      if (utcDayFor(stampedAt.toMillis()) >= todayUtc) continue;

      if (!dryRun) batch.set(doc.ref, {game_attempts: 0}, {merge: true});
      pending++;
      repaired++;
    }

    if (pending > 0 && !dryRun) await batch.commit();

    cursor = snap.docs[snap.docs.length - 1];
    if (snap.size < BATCH_SIZE) break;

    console.log(`  scanned ${scanned}, ${repaired} needed repair`);
  }

  console.log(
    `\nDone. Scanned ${scanned} users, ` +
    `${dryRun ? "would repair" : "repaired"} ${repaired}.` +
    (dryRun && repaired > 0 ? "\nRe-run with --apply to write." : "")
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
