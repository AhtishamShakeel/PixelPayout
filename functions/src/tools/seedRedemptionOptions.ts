/**
 * Seeds the redemption catalogue: one document per game, denominations
 * nested as `packs`.
 *
 * Run against the emulator or against the real project - see the README block
 * at the bottom of this file, and the npm scripts `seed:options:emulator` and
 * `seed:options:live`.
 *
 * The catalogue below is the four games from the Wallet design handoff, with
 * its prices. Treat every number here as a STARTING POINT, not a decision:
 * the design's tiers (1,200 - 28,000 points) were drawn before this app's
 * economy existed, and today the only recurring star sources are the daily
 * streak, the daily-goals bonus and referrals. Check these against what a
 * player can actually earn in a week before this goes anywhere near
 * production.
 *
 * Each document is written with a FULL set, not a merge: a seed should leave
 * the catalogue exactly as this file describes it, so re-running after
 * deleting a pack here actually removes it. That also means any edit made by
 * hand in the console is overwritten - once you start tuning in the console,
 * stop running this against that project.
 */
import * as admin from "firebase-admin";
import {
  REDEMPTION_CONFIG_DOC,
  REDEMPTION_OPTIONS_COLLECTION,
  RedemptionGame,
} from "../economy/redemption";

/** Level at which the discounted first redeem unlocks. */
const FIRST_REDEEM_MIN_LEVEL = 10;

const GAMES: Record<string, RedemptionGame> = {
  pubg_mobile: {
    name: "PUBG Mobile",
    code: "UC",
    subtitle: "Unknown Cash for PUBG Mobile",
    enabled: true,
    sortOrder: 10,
    idLabel: "Player ID",
    idHint: "Enter your PUBG Mobile numeric player ID.",
    idMinLength: 6,
    requiresUsername: true,
    usernameLabel: "In-game username",
    servers: ["Global", "Korea", "Vietnam"],
    packs: {
      // The first-redeem pack. Cheap at list price and cheaper still on the
      // discount, because its job is to prove the payout works.
      uc_30: {
        amount: "30 UC", pointsCost: 600, note: "Taster pack",
        firstRedeemCost: 150, sortOrder: 0,
      },
      uc_60: {amount: "60 UC", pointsCost: 1200, note: "Starter pack", sortOrder: 1},
      uc_325: {
        amount: "325 UC", pointsCost: 5800, note: "Best value per point",
        tag: "Popular", sortOrder: 2,
      },
      uc_660: {amount: "660 UC", pointsCost: 11000, note: "Season pass ready", sortOrder: 3},
      uc_1800: {amount: "1800 UC", pointsCost: 28000, note: "Crate opener", sortOrder: 4},
    },
  },

  free_fire: {
    name: "Free Fire Diamonds",
    code: "FF",
    subtitle: "Diamonds for Garena Free Fire",
    enabled: true,
    sortOrder: 20,
    idLabel: "Free Fire UID",
    idHint: "Enter your Free Fire UID.",
    idMinLength: 6,
    requiresUsername: true,
    usernameLabel: "In-game username",
    servers: ["India", "Global", "SEA"],
    packs: {
      ff_20: {
        amount: "20 Diamonds", pointsCost: 700, note: "Taster pack",
        firstRedeemCost: 150, sortOrder: 0,
      },
      ff_100: {amount: "100 Diamonds", pointsCost: 1400, note: "Starter pack", sortOrder: 1},
      ff_310: {
        amount: "310 Diamonds", pointsCost: 4200, note: "Weekly membership",
        tag: "Popular", sortOrder: 2,
      },
      ff_520: {amount: "520 Diamonds", pointsCost: 7000, note: "Elite pass", sortOrder: 3},
      ff_1060: {amount: "1060 Diamonds", pointsCost: 14200, note: "Bundle hunter", sortOrder: 4},
    },
  },

  delta_force: {
    name: "Delta Force Coins",
    code: "DF",
    subtitle: "Coins for Delta Force",
    enabled: true,
    sortOrder: 30,
    idLabel: "Account ID",
    idHint: "Enter the account ID shown in your profile.",
    idMinLength: 5,
    requiresUsername: true,
    usernameLabel: "In-game username",
    servers: ["Global", "Asia"],
    packs: {
      df_20: {
        amount: "20 Coins", pointsCost: 800, note: "Taster pack",
        firstRedeemCost: 150, sortOrder: 0,
      },
      df_300: {amount: "300 Coins", pointsCost: 1800, note: "Starter pack", sortOrder: 1},
      df_980: {
        amount: "980 Coins", pointsCost: 5600, note: "Operator unlock",
        tag: "Popular", sortOrder: 2,
      },
      df_1980: {amount: "1980 Coins", pointsCost: 11200, note: "Battle pass", sortOrder: 3},
      df_3280: {amount: "3280 Coins", pointsCost: 18400, note: "Full loadout", sortOrder: 4},
    },
  },

  mobile_legends: {
    name: "MLBB Diamonds",
    code: "ML",
    subtitle: "Diamonds for Mobile Legends",
    enabled: true,
    sortOrder: 40,
    // MLBB identifies an account by user ID AND zone, so the zone goes in the
    // same field rather than becoming a fifth input used by one game.
    idLabel: "User ID (Zone ID)",
    idHint: "Enter your MLBB user ID and zone, e.g. 118392(2201).",
    idMinLength: 6,
    requiresUsername: true,
    usernameLabel: "In-game username",
    servers: ["Global", "Asia", "EU"],
    packs: {
      ml_20: {
        amount: "20 Diamonds", pointsCost: 700, note: "Taster pack",
        firstRedeemCost: 150, sortOrder: 0,
      },
      ml_86: {amount: "86 Diamonds", pointsCost: 1300, note: "Starter pack", sortOrder: 1},
      ml_172: {
        amount: "172 Diamonds", pointsCost: 2500, note: "Skin fund",
        tag: "Popular", sortOrder: 2,
      },
      ml_257: {
        amount: "257 Diamonds", pointsCost: 3700,
        note: "Weekly diamond pass", sortOrder: 3,
      },
      ml_706: {amount: "706 Diamonds", pointsCost: 9800, note: "Collector skin", sortOrder: 4},
    },
  },
};

async function main() {
  // Optional on purpose. With a service-account key the project is already
  // fixed by the credential, and passing a DIFFERENT id here can only be
  // ignored or fail on permissions - so the safe default is to let the
  // credential decide, and only require an explicit id for the emulator,
  // which has no credential to read a project from.
  const explicitProjectId =
    process.env.GCLOUD_PROJECT ||
    process.env.FIREBASE_PROJECT ||
    process.argv.slice(2).find((a) => !a.startsWith("--"));

  const emulator = process.env.FIRESTORE_EMULATOR_HOST;

  if (!explicitProjectId && emulator) {
    console.error(
      "Running against the emulator needs an explicit project id:\n" +
      "  node lib/tools/seedRedemptionOptions.js <projectId>"
    );
    process.exit(1);
  }

  admin.initializeApp(
    explicitProjectId ? {projectId: explicitProjectId} : undefined
  );

  const projectId = admin.app().options.projectId || explicitProjectId;
  console.log(
    `Seeding redemptionOptions into "${projectId}" ` +
    `(${emulator ? "emulator " + emulator : "LIVE Firestore"})`
  );
  const db = admin.firestore();

  const batch = db.batch();

  for (const [id, game] of Object.entries(GAMES)) {
    batch.set(db.collection(REDEMPTION_OPTIONS_COLLECTION).doc(id), game);
    const packCount = Object.keys(game.packs ?? {}).length;
    const offers = Object.values(game.packs ?? {})
      .filter((p) => p.firstRedeemCost !== undefined).length;
    console.log(`  ${id}  ${packCount} packs, ${offers} on the first-redeem offer`);
  }

  // The level gate for the discounted first redeem. Read by redeemReward and
  // by the app, so the card and the server can never disagree about it.
  batch.set(
    db.collection("config").doc(REDEMPTION_CONFIG_DOC),
    {firstRedeemMinLevel: FIRST_REDEEM_MIN_LEVEL},
    {merge: true}
  );
  console.log(`  config/${REDEMPTION_CONFIG_DOC}  firstRedeemMinLevel=${FIRST_REDEEM_MIN_LEVEL}`);

  await batch.commit();
  console.log(`\nDone. ${Object.keys(GAMES).length} games written.`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
