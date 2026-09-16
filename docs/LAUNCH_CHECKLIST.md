# LootLevel — Launch Checklist

Everything that has to change or be checked before LootLevel goes live on
Google Play. Tick items off as they are done.

Written 2026-09-17. Longer-term deferred work (scaling, latency, analytics)
lives in [DEFERRED.md](DEFERRED.md); this file is only what stands between the
current build and a public release.

**Must-not-miss:** 1.1, 1.2, 1.4, 1.5, 2.1 and 3.2. They either lose money,
break the app in release, or put the ad accounts at risk.

---

## 1. App code and config

- [ ] **1.1 Real AdMob IDs.** Every ad ID is still Google's public test ID
  (`ca-app-pub-3940256099942544…`):
  - `admob_app_id` in [strings.xml](../app/src/main/res/values/strings.xml)
  - `ADMOB_REWARDED_AD_UNIT_ID` and `ADMOB_INTERSTITIAL_AD_UNIT_ID` in
    [AppConfig.kt](../app/src/main/java/com/createbyte/lootlevel/config/AppConfig.kt)
  - Never tap your own live ads on a test phone; add the phone as a test
    device in AdMob instead.
- [ ] **1.2 Unity Ads out of test mode.** `UNITY_TEST_MODE = false` in
  [AppConfig.kt](../app/src/main/java/com/createbyte/lootlevel/config/AppConfig.kt).
  Game ID `5816684` is already the Android one.
- [ ] **1.3 Offerwall keys.** Confirm `TAPJOY_SDK_KEY` in AppConfig.kt is the
  live key, and that the walls you want are `enabled` in Firestore
  `config/offerwallWalls`.
- [x] **1.4 Onboarding slide 3.** Was "Cash Out — Convert your points to real
  money rewards", which contradicted the Terms (game currency only) and
  AdMob's policy against paying users to watch ads. Now "Redeem Rewards — Swap
  your stars for in-game currency like UC and Diamonds". *Done 2026-09-17.*
  - [ ] Also review the Play Store description and screenshots for the same
    kind of wording ("earn money", "cash", "real money").
- [ ] **1.5 Test a signed RELEASE build on a real phone.** Release has
  `minifyEnabled true` and `shrinkResources true`; every test so far was on
  debug. Shrinking commonly breaks Gson models, Tapjoy, Unity Ads and the
  WebView JavaScript bridge (`window.LootLevel`). Walk through:
  - [ ] sign up / log in / log out / delete account
  - [ ] Play tutorial to level 2 and claiming the stars
  - [ ] a Neon Flap run and a Tower Building run (XP paid, results shown)
  - [ ] a quiz answer
  - [ ] daily check-in and double-XP ad
  - [ ] bonus attempt ad, level reward claim
  - [ ] redeem flow and Orders tab
  - [ ] offerwall opens
  - [ ] interstitials and rewarded ads (AdMob, and Unity fallback)
  - [ ] support ticket create / reply
- [ ] **1.6 Signing.** Create the upload keystore, back it up in two safe
  places (losing it blocks future updates), enable Play App Signing.
- [ ] **1.7 Version.** `versionCode` / `versionName` in
  [app/build.gradle](../app/build.gradle) (currently `1` / `"1.0"` — fine for
  the first release, must increase on every update).
- [ ] **1.8 Commit everything.** Make sure all work is committed and pushed
  before building the release.
- [ ] **1.9 (Optional) Remove noisy debug logs** such as `BUFF_DEBUG` and
  `ReferralDebug` in UserRepository / MainActivity.

## 2. Legal pages

- [ ] **2.1 Fill the placeholders** in
  [public/legal/legal-config.js](../public/legal/legal-config.js):
  - `operatorName` — e.g. "[Your full legal name], trading as CreateByte
    Studios" (an unregistered brand name alone does not identify who is
    responsible)
  - `country`, `address`, `contactEmail`, `effectiveDate`
- [ ] **2.2 Website URL.** `websiteUrl` is `https://pixelpayout-check.web.app`,
  which shows the old name. Consider a custom domain (e.g. lootlevel.app) on
  Firebase Hosting and update it here and in the Play listing.
- [ ] **2.3 Re-read Terms and Privacy** against what the app actually does
  (partners list, 18+, game currency only, 2-year retention, in-app support
  tickets, account deletion).
- [ ] **2.4 Deploy hosting:** `firebase deploy --only hosting`. This publishes
  the legal pages, the licenses page, and the game changes (pink Neon Flap
  pipes, Tower Building font and sounds).
- [ ] **2.5 (Recommended) Business-name check.** A software firm called
  "CreateBytes" exists; check for trademark conflicts, and ask a local
  lawyer/accountant whether the trading name needs registering.

## 3. Firebase

- [ ] **3.1 Functions are deployed.** Deploy only the functions you changed,
  by name — never `firebase deploy --only functions` for all of them.
  Recently added/changed: `completePlayTutorial`, `completeSignup`,
  `redeemReward`.
- [ ] **3.2 Wipe development data** before real users arrive:
  - [ ] test user accounts (Authentication + `users` documents)
  - [ ] test `redemptions`
  - [ ] **`payoutFeed`** — Home currently shows test entries such as
    "Abd*****iz received 20 Coins"; left in, it reads as fake social proof
  - [ ] leaderboard / tournament weeks and any test `supportTickets`
  - [ ] `deletedAccounts` tombstones from testing (they block the referral
    window and first-redeem discount on those phones)
- [ ] **3.3 Check live config documents** hold launch values:
  - `config/levelCurve` (level rewards)
  - `config/ads` (primary network, enabled switches, caps and gaps)
  - `config/attempts` (bonus attempts cap)
  - `config/dailyGoals` (goal bonus)
  - `redemptionOptions` (games, packs, prices, `enabled`, `minLevel`)
- [ ] **3.4 Reward artwork.** In `redemptionOptions`:
  - Call of Duty `imageUrl` returns **403 Forbidden** (and points at the UC
    picture) — upload a CP image and replace the link.
  - Free Fire, Delta Force and MLBB have no image — add `currencyImageUrl`
    or `imageUrl`. Keep uploads around 512–800 px.
- [ ] **3.5 Budget alerts** on the Blaze plan (Google Cloud → Billing →
  Budgets & alerts).
- [ ] **3.6 Security rules deployed** and the admin panel
  (`public/admin.html`) only works for your admin account.
- [ ] **3.7 Offerwall secrets** are the live ones in `serverConfig` (never in
  `config`, which every signed-in user can read).

## 4. Ad networks

- [ ] **4.1 AdMob**
  - [ ] link the app to its Play Store listing once published
  - [ ] create and publish the **GDPR consent message** (Privacy & messaging);
    the app already shows it through Google's consent SDK (UMP)
  - [ ] payment details and tax info
  - [ ] publish **app-ads.txt** on the developer website listed in Play
- [ ] **4.2 Unity Ads**
  - [ ] ask Unity for approval of the rewards model (their content policy
    needs prior approval for "real-world reward systems")
  - [ ] fill in the GDPR / privacy partners setup
  - [ ] payment details
- [ ] **4.3 Tapjoy / ayeT-Studios**
  - [ ] account/app approval
  - [ ] live postback (reward callback) URLs and secrets configured, test one
    real completion end to end

## 5. Google Play Console

- [ ] **5.1 Developer account** verified; developer name shown as
  "CreateByte Studios".
- [ ] **5.2 Store listing**
  - [ ] app icon 512×512 — [docs/Logo/play_store_icon_512.png](Logo/play_store_icon_512.png)
  - [ ] feature graphic 1024×500
  - [ ] phone screenshots
  - [ ] short and full description (no "earn money" / "cash" wording)
- [ ] **5.3 Policy declarations**
  - [ ] Privacy Policy URL
  - [ ] **Account deletion URL** (the web deletion page)
  - [ ] **Data safety** form — covers Firebase (Auth, Firestore, Functions,
    Crashlytics, Analytics), AdMob, Unity Ads, Tapjoy, ayeT
  - [ ] Ads: **contains ads**
  - [ ] Target audience: **18+**
  - [ ] Content rating questionnaire
- [ ] **5.4 App access** — give reviewers a working test account; everything
  is behind sign-in.
- [ ] **5.5 Closed testing** — new personal developer accounts must run a
  closed test (currently 12 testers for 14 days) before production access.

## 6. Nice to have (not blocking)

- [ ] The open items in [DEFERRED.md](DEFERRED.md): orders listener reading the
  full history each launch, cold start on the first game/quiz, offerwall
  analytics, `play-services-ads` version.
- [ ] Logo leftovers ("PX"/"P" fragments) in the launcher icon artwork.
- [ ] Keep tab screens alive to remove the remaining ~150 ms rebuild on tab
  switches (was measured and deliberately left for now).
