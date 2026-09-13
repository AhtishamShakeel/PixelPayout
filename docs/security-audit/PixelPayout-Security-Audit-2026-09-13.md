# PixelPayout — Backend Security, Economy & Cost Audit

**Date:** 2026-09-13 · **Branch audited:** `economy-refactor-ui` (working tree, including uncommitted changes to `functions/src/index.ts`, `leaderboard.ts`, `rewardConfig.ts`)
**Mode:** read-only. No application code, rules, config or data was changed, and nothing was deployed. The outputs are this report and `docs/security-audit/audit.cjs`. `functions/lib` was rebuilt with `npm run build`; it is gitignored build output.

### Evidence levels used below

| Label | Meaning |
|---|---|
| **EMULATOR-PROVEN** | Reproduced by `audit.cjs` against the local Auth, Firestore and Functions emulators under project `demo-pixelpayout-audit`. The script refuses to run against any non-`demo-` project. |
| **UNIT-PROVEN** | Reproduced by calling the compiled pure function directly. |
| **CODE-REVIEWED** | Traced in source, not executed. |
| **UNKNOWN** | Depends on console configuration that is not in the repo. |

Nothing here is "manually verified" on a device, and nothing was checked against production.

Every emulator request ran without an App Check token. The emulator log recorded 180 accepted calls tagged `"verifications":{"app":"MISSING","auth":"VALID"}`.

---

## EXECUTIVE SUMMARY

The per-account mechanics are strong:

- Firestore rules deny every client write.
- Every reward amount is computed on the server.
- Every claim is a transaction keyed by a deterministic idempotency id.
- Day and week boundaries use server time.

I raced six parallel requests against each of these, and every one paid exactly once:

- level reward
- streak claim
- tournament entry
- same-session game claim
- double XP

A user cannot give themselves Stars directly.

**The system still breaks at the level above a single account.** Nothing ties an account to a real person or a real device. Nothing proves an ad was watched. Nothing stops the callables being driven by a script. So the per-account ceilings, which are the design's security boundary, can be multiplied by as many fake accounts as someone wants to create.

In the emulator, one script turned a brand-new, unverified account into all of this in **55 seconds**, with zero ads and no app:

- level 13
- 166★ earned
- a placed first-redeem order (150★ for the taster pack)
- 50★ referral paid to its "main" account

Two offerwall defects are serious enough to fix before any network goes live:

1. **Rejected postbacks log the correct signature.** Anyone who can read logs can mint up to 20,000★ per request. EMULATOR-PROVEN.
2. **A chargeback with the same transaction id is ignored as a duplicate.** Stars from reversed offers are never taken back. EMULATOR-PROVEN.

### Scores

| Area | Score | Why |
|---|---|---|
| Security | **5/10** | Excellent rules. No App Check, a logging signing oracle, email enumeration, single-factor admin. |
| Economy Integrity | **3/10** | Every Star source can be scripted per account, and there is no account/device binding. |
| Firestore Rules | **9/10** | Deny-by-default, zero client writes, owner-only reads. Only nit: `config/quizAnswerKey` is readable. |
| Cloud Functions | **6/10** | Transactional and idempotent. No App Check, chargeback bug, debug oracle, unauthenticated/unrestricted callables. |
| Abuse Resistance | **2/10** | Asserted ads, spoofable device id, unverified signups, parallel game sessions. |
| Reliability | **7/10** | Atomic transactions and an idempotent settlement. Redeem has no idempotency key; a game replay returns an error instead of the original result. |
| Scalability | **6/10** | One global `maxInstances: 10`, a hot user document, count queries per leaderboard view at scale. |
| Firebase Cost Efficiency | **8/10** | Thoughtful caching and indexes. Main risks are abuse-driven invocations, not normal use. |
| Code Architecture | **6/10** | Pure economy modules are good. A 3,750-line `index.ts`, logic mirrored in Kotlin, stale comments and compat leftovers. |
| Production Readiness | **4/10** | P0 items below; AdMob test unit IDs are still configured. |

---

## PHASE 1 — BACKEND ARCHITECTURE MAP

```
ANDROID CLIENT (no App Check, email/password + Google auth, no email verification)
 │  Firestore: READ ONLY
 │    - listener: users/{uid}
 │    - listener: redemptions where uid==me
 │    - listener: payoutFeed (top 10)
 │    - get: config/levelCurve, config/dailyGoals, redemptionOptions, rewardEvents (paged), gameProfiles
 │  Callables: completeSignup, checkEmailExists(unauth), startGameSession, claimReward,
 │    claimDoubleXp, grantBonusAttempt, claimDailyStreak, claimDailyGoalBonus,
 │    claimLevelReward, getLeaderboard, enterTournament, redeemReward,
 │    submitReferral, getReferralStats, getStreakConfig, (debug) bootstrapAdmin/grantPointsBuff
 │  WebViews:
 │    - games: pixelpayout-check.web.app/games/* → JS bridge onGameComplete(score)
 │    - offerwalls: URL from config/offerwallWalls
 │    - Tapjoy SDK: setUserID(uid)
 ▼
CLOUD FUNCTIONS v2 (functions/src/index.ts, maxInstances 10 each, Admin SDK)
 │  validation → pure modules in economy/*
 │  → writeAward(): points/xp/level increments + rewardEvents ledger + pendingLevelRewards
 │  Scheduled:
 │    - scheduledQuizAnswerKeySync (6h): fetch quizzes-b446b.web.app/quizzes.json → config/quizAnswerKey
 │    - settleWeeklyLeaderboard (Mon 00:05 UTC)
 │  HTTP: offerwallCallback (partner postbacks, signature from serverConfig/offerwall)
 │  Admin (custom claim): listRedemptions, resolveRedemption, settleLeaderboardNow, grantPointsBuff
 │  Admin bootstrap: bootstrapAdmin (email allowlist from functions/.env)
 ▼
FIRESTORE
 users/{uid}                       balance, xp, level, counters, streak, weekly, referral, flags
   rewardEvents/{id}               ledger (owner-readable)
   gameSessions/{id}               server-only, never deleted
   gameProfiles/{gameId}           prefill (owner-readable)
 redemptions/{id}                  orders (owner-readable by uid)
 payoutFeed/{id}                   public masked feed
 playerLinks/{game__playerId}      first-redeem-per-UID mark (server-only)
 offerwallTransactions/{net__tx}   postback idempotency (server-only)
 leaderboardSettlements/{week}     frozen board (server-only)
 config/*                          readable by any signed-in user (levelCurve, dailyGoals, tournament, offerwallWalls, quizAnswerKey)
 serverConfig/*                    secrets, server-only
 redemptionOptions/*               catalogue, readable
STORAGE: catalogue art via download URLs — storage.rules NOT in repo (UNKNOWN)
HOSTING: public/ → admin.html, games/*
```

### Value flow (Stars = money)

| In / out | Path | Amount | Gate |
|---|---|---|---|
| **In** | Level-ups (XP from game/quiz/streak/double) → `pendingLevelRewards` → `claimLevelReward` | 1,282★ lifetime | asserted ad |
| **In** | `claimDailyStreak` | 30★/week | asserted ad |
| **In** | `claimDailyGoalBonus` | live 10★/day (fallback 30) | asserted ad |
| **In** | Referral level-10 milestone, paid to referrer | 50★ | — |
| **In** | Referral first full-price redeem, paid to referrer | 150★ | — |
| **In** | Weekly settlement | 2,450★ pool | — |
| **In** | Offerwall postback | ≤20,000★ per postback | signature |
| **In** | Redemption refund | the order's cost | — |
| **Out** | `enterTournament` | −fee | — |
| **Out** | `redeemReward` → pending order → admin approves or rejects+refunds | −order cost | admin |

---

## PHASE 2 — TRUST BOUNDARY AUDIT (key actions)

Replay, concurrency and double-pay results come from the emulator where the Evidence column says so.

| Action | Client sends / controls | Server independently verifies | Server trusts incorrectly | Replay / concurrent / double-pay | Crash midway |
|---|---|---|---|---|---|
| completeSignup | displayName, **androidId** | uid & email from token; create() race-safe | androidId drives `hasUsedReferral` and admin device badge (spoof proven); no length check (500 on >1.5KB, proven) | idempotent | atomic create |
| claimReward (game) | gameId, **score**, sessionId | known game, score ≤ cap, session exists/unconsumed/owned, server elapsed ≥ score/rate, daily cap in tx | score itself (unavoidable); sessions can be opened in parallel so elapsed time is not play time (proven) | same session ×6 parallel → paid once (proven) | single tx |
| claimReward (quiz) | category, quizId, questionIndex, selectedAnswer | graded vs server key, daily cap in tx | answer key readable by client (proven) and shipped in quizzes.json | auto-id ledger; cap bounds | single tx |
| claimDoubleXp | eventId | owner path, source GAME/QUIZ, applied, ≤10 min, amount from ledger, ceiling, `/` rejected | **adWatched implicit** | ×6 parallel → once (proven) | single tx |
| grantBonusAttempt | activity, **adWatched** | cap 3/day, server day | ad | cap | single tx |
| claimDailyStreak | **adWatched** | server UTC day, once/day | ad | ×6 → once (proven) | single tx |
| claimDailyGoalBonus | **adWatched** | goals recomputed from server counters | ad | day-keyed | single tx |
| claimLevelReward | nothing | queue + locked ledger entry, lowest first, amount from ledger | ad (implicit) | ×6 → once (proven) | single tx |
| enterTournament | expectedFee | fee from config, compare only, once/week | — | ×6 → once (proven); −1 rejected | single tx |
| submitReferral | referralCode | code lookup, not self, not used, level <10 | androidId-based `hasUsedReferral` is spoofable | tx (smoke.ts:2094) | single tx |
| redeemReward | optionId, packId, playerId, username, server, useFirstRedeem | price from catalogue, balance, first-redeem per account + per UID, format | referral 150★ paid at order time (proven not clawed back) | concurrent overdraw blocked (smoke.ts:1095); **sequential retry = duplicate order** (proven) | single tx |
| resolveRedemption | redemptionId, status, reason, restore | admin claim, status pending, owner from doc | admin is single-factor | status guard | single tx |
| offerwallCallback | everything (partner) | signature (timing-safe), cap, tx id once | **XFF leftmost** for allowlist (spoof proven); reversal with same tx id treated as duplicate (proven) | same tx → 200 no pay | single tx; 500 on write fail |
| settle (sched/admin) | weekKey (admin) | past week only, frozen board, ledger id per winner | tie-break = uid (grindable) | idempotent | per-winner tx + marker |

Across all of these:

- **No client timestamp is trusted anywhere.**
- **No caller-supplied uid is used** except in admin `grantPointsBuff` and the signed postback.
- **Negative, huge, NaN or path-injection inputs** were rejected on every reward path tested.

---

## CRITICAL FINDINGS

### C1 — The Star economy is fully scriptable and multipliable across unlimited fake accounts

- **Severity:** CRITICAL
- **Evidence:** EMULATOR-PROVEN (`BOT-FARM-ONE-DAY`, `DEVICE-CHECK-SPOOF`, `QUIZ-ANSWER-KEY-CLIENT-READABLE`, `GAME-SESSION-SPAM-UNBOUNDED`)
- **Files:**
  - `app/build.gradle` (no App Check dependency)
  - `app/src/main/java/com/pixelpayout/PixelPayoutApp.kt` (no App Check install)
  - `functions/src/index.ts`:
    - every `onCall` has no `enforceAppCheck`
    - `completeSignup` 446–538: `androidId` taken from the request
    - `startGameSession` 1963–1998: no limit on open sessions
    - `claimDailyStreak` 978, `claimDailyGoalBonus` 1172, `grantBonusAttempt` 2034, `claimDoubleXp`, `claimLevelReward`: asserted ads
  - `firestore.rules` 79–82 (`config/*` readable, including `quizAnswerKey`)
  - `functions/src/economy/redemption.ts` 342–348 (full-price redeem into any player ID)
- **Component:** auth, signup, games, quizzes, ads, level rewards, referrals, redemption

**Problem.** The per-account caps are the only thing bounding value, and nothing binds an account to a human or a device.

- Accounts are free and unverified. Email verification is never required, and Identity Toolkit sign-up works straight from the public API key.
- The "same device" check compares a string the client chooses.
- Every ad-gated reward accepts `adWatched: true` on trust.
- The quiz key can be read straight out of Firestore.
- `startGameSession` lets a script open all 13 sessions at once, wait out the 15s rate floor once, then claim all 13. A day of maximum game XP costs about 16 seconds instead of 13+ minutes of play.

**Exact attack scenario.** This is what `audit.cjs` does, all from Node with no APK:

1. `createUserWithEmailAndPassword` using a random email.
2. `completeSignup({androidId: randomHex})`. Result: `hasUsedReferral=false`.
3. `submitReferral({referralCode: MAIN})`.
4. Read `config/quizAnswerKey` with the bot's own client SDK.
5. `grantBonusAttempt` ×6 with `adWatched:true`.
6. Open 13 `tower_game` sessions in parallel, sleep 16s, claim all with `score:1500`. Gives 780 XP.
7. 13 correct quiz claims (130 XP), then `claimDoubleXp` on all 26 entries (+910 XP).
8. `claimDailyStreak` and `claimDailyGoalBonus` with `adWatched:true`.
9. Loop `claimLevelReward` until empty: 12 releases, 136★.
10. `redeemReward({packId:"taster", useFirstRedeem:true, playerId: fresh})`.

**Measured result:**

| Metric | Value |
|---|---|
| Wall time | 55 s |
| XP | 1,830 → level 13 |
| Stars | 166★ |
| First-redeem order | accepted |
| Referrer paid | 50★ |
| Ads watched | 0 |
| `adlessStreakClaims` | 0 (the abuse signal stays clean) |
| Admin device badge | "device used ×1" (random id) |

**Why it works.**

- The game rate check measures time since session *start*, not time spent *playing*, and sessions overlap.
- Ads and device identity are client assertions.
- Callables accept requests with no App Check token.
- Full-price redemptions into any player ID mean many bots can pay out into one attacker game account.

**Impact (from the real curve and tables).** One bot account running the script once a day:

- reaches level 30 (33,471 XP) in about 18 days
- earns 1,282★ in level rewards, plus about 180★ in goals (live 10★/day) and about 77★ in streak rewards
- pays 50★ + 150★ to its referrer

That is about **1,740★ per bot per 18 days**, where 1,200★ buys a 60 UC pack. On top of that, each fresh game UID yields a 30 UC taster for 150★ on day 2.

At 500 bots, that is on the order of 700+ 60 UC packs a month, with **zero ad revenue behind them**. The weekly tournament pool (2,450★) also goes to bots, which all sit at the XP ceiling and tie (see L2). The only barrier is manual approval, and the admin tool's device signal is attacker-controlled.

**Recommended fix (layered; no single control is enough):**

1. **App Check with Play Integrity**:
   - Client: add `com.google.firebase:firebase-appcheck-playintegrity` and install `PlayIntegrityAppCheckProviderFactory` in `PixelPayoutApp.onCreate`. Use the debug provider in debug builds and emulator.
   - Server: create a shared `const CALLABLE_OPTS = {enforceAppCheck: true}` and use `onCall(CALLABLE_OPTS, handler)` on every callable.
   - Add `consumeAppCheckToken: true` on value-out calls: `redeemReward`, `enterTournament`, `claimLevelReward`.
   - Turn on App Check enforcement for Firestore in the console.
   - This stops pure scripting. Farms then need real, unrooted devices.
2. **Gate value-out, not just value-in.** In `redeemReward` (first redeem included), require:
   - `request.auth.token.email_verified === true` (Google accounts already are; email/password users get `sendEmailVerification`)
   - a minimum account age, e.g. `joinedDate` ≥ 72h
   - Record server-observed signals on the order: right-most `x-forwarded-for` IP and `/24`, App Check verdict. Show accounts-per-IP in `listRedemptions` next to (not instead of) the device id.
3. **One open game session at a time.** `startGameSession` runs in a transaction and refuses when an unconsumed session younger than `MAX_SESSION_AGE_MS` exists, or stores `openSessionId` on the user doc and replaces it. This restores "13 runs take 13 × real play time". Add `expireAt` and a Firestore TTL policy on `gameSessions`.
4. **Stop trusting the client for device identity.** Ignore `androidId` for `hasUsedReferral` (see L3). Referral anti-abuse should key on server-side signals (verified email, App Check, IP clustering, account age), and the referrer should be paid only after the referee's first **approved** redemption, or level 10 plus N active days.
5. **AdMob server-side verification (SSV)** for the Star-paying ad paths first: level reward, goal bonus, streak Stars. Then the XP paths. Until SSV exists, the per-account cap only bounds honest users.
6. **Move `quizAnswerKey` to `serverConfig`.** Optionally stop shipping `correctAnswer` in quizzes.json and grade on the server response. Lower priority, since quiz XP is capped.

- **Difficulty:** Medium. App Check and gating are about a day; SSV and session locking are a few days.
- **Regression risk:** Medium. App Check breaks emulator/dev flows until the debug provider is set; email verification adds onboarding friction. Scope email verification to redemption only.
- **Test to prove the fix:** re-run `BOT-FARM-ONE-DAY` with a script client that sends no App Check token → every callable returns `unauthenticated`/`failed-precondition`. With a debug token: 13 parallel `startGameSession` → 1 accepted. First redeem on an unverified or under-72h account → `failed-precondition`. Add these to `smoke.ts`.

---

### C2 → classified HIGH: see H1 and H2 below

(Both are money-minting defects, but H1 needs log-read access and H2 needs offer fraud plus a network that reuses transaction ids, so they sit one notch below C1.)

---

## HIGH PRIORITY FINDINGS

### H1 — Offerwall callback writes the valid signature for forged requests into Cloud Logging

- **Severity:** HIGH
- **Evidence:** EMULATOR-PROVEN (`OFFERWALL-DEBUG-LOG-SIGNING-ORACLE`)
- **File:** `functions/src/index.ts` 3588–3635 (block marked `TEMPORARY - REMOVE ONCE TAPJOY IS CREDITING`)
- **Component:** offerwallCallback

**Problem.** On `bad_signature`, the handler computes `buildSignature(query, config)` and logs it as `expected`, alongside the full query. The endpoint is unauthenticated, so anyone can choose the query.

**Exact attack scenario.** Someone with log read access, or anyone a log sink or export reaches (a `Viewer` role, a support contractor, BigQuery export), sends:

`GET /offerwallCallback?network=test&uid=<me>&tx=forged-1&amt=20000&sig=000…`

- Response: 403.
- The log contains `expected: '44dee1cc005eaa155e46c87abc4d0c4b'`.
- Replaying the same URL with that `sig` returns 200 and the balance goes from 100 to 20,100 (proven).

Repeat with new tx ids for unlimited Stars.

**Why it works.** The log is a signing oracle. The secret fingerprint is harmless; the computed signature is not.

**Impact.** Unlimited minting (20,000★ per request) for anyone with log access. Log access is much more widely granted than access to `serverConfig`.

**Recommended fix.**

- Delete the whole debug block.
- On rejection, log only `{network, rejection, sourceIp, txHash: sha256(tx).slice(0,12)}`.
- Never log `query` or `expected`.
- **Rotate every network secret** that was configured while this code was deployed, because log retention is 30 days by default.
- To debug a template, use a local unit test against a captured, redacted sample.

- **Difficulty:** Trivial
- **Regression risk:** None
- **Test:** add a unit test that `validatePostback` rejection logging never includes a 32/64-hex string, then re-run `OFFERWALL-DEBUG-LOG-SIGNING-ORACLE` and grep the emulator output: no `expected:`.
- **Deploy (when you choose):** `firebase deploy --only functions:offerwallCallback`

### H2 — A chargeback that reuses the credit's transaction id is ignored as a duplicate, so Stars are never reversed

- **Severity:** HIGH
- **Evidence:** EMULATOR-PROVEN (`OFFERWALL-CHARGEBACK-SAME-TXID-SWALLOWED`)
- **Files:** `functions/src/index.ts` 3651–3666 (the `txRef` is keyed `${network}__${transactionId}` for both credit and reversal); `functions/src/economy/offerwall.ts` 434–443
- **Component:** offerwallCallback

**Problem.** Credits and reversals share one idempotency document. The repo's own ayeT fixture (`offerwall.test.ts` ~419) sends the reversal with the **same** `transaction_id` and `is_chargeback=1`, which is how status-flag networks commonly work. The handler finds `txDoc.exists` and answers 200 "duplicate".

**Exact attack scenario.**

1. A user completes a high-value offer with fake details (or on an emulated or incentive-farmed install).
2. The network credits 100★ (tx-1).
3. The advertiser rejects it; the network posts the reversal for tx-1.
4. We reply 200 and change nothing. The network marks the reversal as delivered.
5. The user redeems the Stars.

Proven: after credit 100, a status-flag reversal and a negative-amount reversal both returned 200, and the balance stayed at 100. The reverse order is also broken: a reversal arriving first blocks the later credit.

**Impact.** Offerwall chargeback fraud, the most common offerwall abuse, pays out in full. It is invisible in our ledger because no reversal entry is written, and the partner believes it was applied.

**Recommended fix.**

- Separate documents: `…__credit` and `…__reversal`, or `status`-aware handling on one doc.
- A reversal transaction reads the original credit doc:
  - **If it exists and has not been reversed:** apply `-original.points`. Use the amount actually credited, *including any buff* (this also closes L5). Mark the original `reversed` and write `offerwall_reversal:{network}:{tx}`.
  - **If it has already been reversed:** 200, no-op.
  - **If there is no credit yet:** write the reversal doc as `pending_reversal` with the amount, and have the later credit check it and refuse.
- Keep networks that send a distinct reversal id working: a negative amount with an unknown original applies once, keyed by its own id.

- **Difficulty:** Low–Medium
- **Regression risk:** Low
- **Tests:** credit → same-tx reversal → balance 0; the reversal replayed → still 0; reversal-before-credit → balance 0 and credit refused; distinct-id negative postback applies once; buffed credit reverses the buffed amount.
- **Deploy:** `firebase deploy --only functions:offerwallCallback`

---

## MEDIUM FINDINGS

### M1 — Referral "first full-price redeem" bonus is paid when the order is placed and kept after rejection

- **Evidence:** EMULATOR-PROVEN (`REFERRAL-REDEEM-BONUS-NOT-CLAWED-BACK`)
- **Files:** `functions/src/index.ts` `redeemReward` 2886–2915 (`readReferrerForRedeemUnlock` / `payReferrer`); `resolveRedemption` 3250–3312 (refund does not touch the referrer)

**Attack.** A referee holding 1,200★ places a full-price order into junk ID `junk0000`. The referrer gets +150★ immediately. The admin rejects the order, the referee is refunded to 1,200★, and **the referrer keeps 150★** (proven). One per referee, but free, and it stacks with C1 farming.

**Fix.** Pay the milestone inside `resolveRedemption` when `status === "approved"`:

- read the referee doc and referrer doc inside that transaction
- keep the `referralRedeemRewardPaid` flag
- skip `firstRedeem` orders

Remove the payout from `redeemReward`.

- **Difficulty:** Low
- **Risk:** Low (the referrer is paid later)
- **Test:** order → referrer 0; reject → 0; approve another order → 150; approve a third → still 150.
- **Deploy:** `functions:redeemReward,functions:resolveRedemption`

### M2 — `redeemReward` has no idempotency key, so a retry after a lost response places a second order

- **Evidence:** EMULATOR-PROVEN (`REDEEM-NO-IDEMPOTENCY-KEY`: 2 identical requests → 2 orders, 2,400★ → 0)
- **Files:** `functions/src/index.ts` 2892 (`redemptions.doc()` auto id); `UserRepository.redeem` 708–742

**Scenario.** The network drops after commit, the user taps Redeem again, and gets a double debit plus two pending orders. Concurrent overdraw *is* blocked (smoke.ts:1095). This is retry duplication, not overspend.

**Fix.**

- Client generates `requestId = UUID` when the redeem sheet opens and sends it.
- Server validates `^[A-Za-z0-9-]{16,64}$` and sets the order id to `${uid}_${requestId}`.
- Read that doc first in the transaction; if it exists, return `{ok:true, redemptionId, remainingPoints, replayed:true}`.

- **Difficulty:** Low
- **Risk:** Low
- **Test:** same `requestId` twice → 1 order, 1 debit; different id → 2.
- **Deploy:** `functions:redeemReward` plus an app build

### M3 — `checkEmailExists` is unauthenticated account enumeration

- **Evidence:** EMULATOR-PROVEN (no sign-in: known email → `exists:true`, unknown → `false`; missing param → `internal` with raw Admin SDK message)
- **File:** `functions/src/index.ts` 423–436; `AuthViewModel.kt` 51–65

**Impact.** Anyone can test which emails hold PixelPayout accounts, which enables credential stuffing and targeted phishing ("your payout is pending"). It also defeats Firebase's own Email Enumeration Protection, and gives free unauthenticated invocations to spam.

**Fix.**

- Delete the function.
- Change the auth screen to explicit Sign in / Create account, and handle `ERROR_EMAIL_ALREADY_IN_USE` and `ERROR_INVALID_CREDENTIAL`.
- Enable Email Enumeration Protection in Authentication settings.

- **Difficulty:** Low
- **Risk:** UX change on the auth screen
- **Test:** an unauthenticated call → `not-found`.
- **Deploy:** removing a function is a delete; you would run `firebase functions:delete checkEmailExists` yourself.

### M4 — Offerwall IP allowlist trusts the left-most `X-Forwarded-For` entry

- **Evidence:** EMULATOR-PROVEN (allowlist `203.0.113.7`: no header → 403; header `203.0.113.7, 198.51.100.99` → 200)
- **File:** `functions/src/index.ts` 3542–3546

**Problem.** Google's front end *appends* the real client IP to whatever `X-Forwarded-For` the caller sent, so the first entry is attacker-controlled. A valid signature is still required, so this breaks defense-in-depth rather than being a standalone mint. It becomes serious combined with H1 or a leaked secret.

**Fix.** Use the right-most entry (the one Google appended), or `request.ip`. Before enabling any allowlist in production, log the raw header from one real partner test postback to confirm the layout.

- **Test:** the spoofed-header case above → 403.
- **Deploy:** `functions:offerwallCallback`

### M5 — Unbounded `startGameSession`: unlimited documents, unlimited invocations, and parallel-session XP compression

- **Evidence:** EMULATOR-PROVEN (40 requests → 40 session docs with zero claims)
- **File:** `functions/src/index.ts` 1963–1998

**Impact.** One read, one write and one invocation per call, forever, with no TTL. It is the enabler of C1's 16-second game day, and an easy cost and availability lever against the `maxInstances: 10` ceiling (M7).

**Fix.** C1 #3: one open session per user, `expireAt` plus a TTL policy, App Check.

- **Test:** 40 parallel calls → 1 doc; a session older than 4h is TTL-deleted.
- **Deploy:** `functions:startGameSession,functions:claimReward`

### M6 — Admin model is single-factor and permanently bootstrappable

- **Evidence:** CODE-REVIEWED
- **Files:** `functions/src/index.ts` `bootstrapAdmin` 842–868; `resolveRedemption`, `settleLeaderboardNow`, `grantPointsBuff`; `public/admin.html`

**Problem.** Anyone who takes over the allowlisted Google account (or any session token) can:

- approve payouts
- reject and refund
- restore discounts
- grant 3× buffs to any uid
- settle weeks

`bootstrapAdmin` stays deployed and `admin.html` calls it on every sign-in. There is no recency check (`auth_time`) and no MFA requirement.

What is already good: HTML is escaped, and there is no rules carve-out.

**Fix.**

1. Enable 2-Step Verification on the admin Google account.
2. After the claim is granted, delete `bootstrapAdmin` (or empty `ADMIN_BOOTSTRAP_EMAILS`). Grant future claims via a one-off local script using `gcloud auth application-default login`, with no key file.
3. In admin callables, require `Date.now()/1000 - request.auth.token.auth_time < 900`.
4. Write each admin action to an `adminAudit` collection (server-only).

- **Test:** a token with `auth_time` older than 15 min → `permission-denied`; bootstrap → `not-found`.

### M7 — One global `maxInstances: 10` caps every function, including claims and partner postbacks

- **Evidence:** CODE-REVIEWED
- **File:** `functions/src/index.ts` 133

**Impact.** A single attacker with a few hundred concurrent requests (see M5, L1, M3) can saturate `claimReward`/`offerwallCallback` for everyone and produce 429s. Partners retry, and users see failures. The comment notes the regional CPU quota, so raise limits selectively:

- light read callables: `cpu: "gcf_gen1"`, lower memory
- hot paths (`claimReward`, `startGameSession`, `offerwallCallback`): higher `maxInstances` plus explicit `concurrency`
- per-uid token-bucket throttling for non-transactional callables (`getLeaderboard`, `submitReferral`)

- **Test:** load-test in the emulator or a staging project with k6 at 500 rps.

### M8 — Storage security rules are not in the repo (UNKNOWN)

- **Evidence:** UNKNOWN
- **Files:** `firebase.json` (no `storage` block); client uses Storage download URLs for catalogue art

**Risk if the console still has test-mode or `auth != null` write rules:**

- arbitrary uploads (bandwidth/storage bills, malware hosting on your domain)
- overwriting catalogue images

**Fix.** Check the console now. Commit a `storage.rules` file: `match /redemption-art/{f} { allow read: if true; allow write: if false; }` and deny everything else. Add it to `firebase.json`.

- **Test:** rules-unit-testing: an authenticated write → denied.
- **Deploy:** `firebase deploy --only storage`

---

## LOW / INFO FINDINGS

| ID | Sev | Evidence | Finding | Fix |
|---|---|---|---|---|
| L1 | LOW | EMULATOR-PROVEN | `syncQuizAnswerKey` is callable by **any** signed-in user. Each call does an external fetch plus 2 writes (answer key and `config/levelCurve`), so it is a cost/abuse lever (`index.ts` 806–813). | Require `admin` claim. |
| L2 | LOW | UNIT-PROVEN | Tie-break at equal weekly XP is **uid ascending** (`leaderboard.ts` 205–207). Bots all cap at ~12,740 XP/week, so rank is decided by uid; account-grinding for uids starting `0…` wins 1st (350★) over an honest player. | Store `weeklyXpReachedAt` (server time of the claim that set the current total) and order by `weeklyXp desc, weeklyXpReachedAt asc` (new composite index), or split tied prizes. |
| L3 | LOW | EMULATOR-PROVEN | `completeSignup` doesn't validate `androidId`. >1.5 KB crashes with 500 ("too large to be used in a query"). The client fallback `"UNKNOWN_ANDROID_ID"` is a real value, so every later no-ID account is marked `hasUsedReferral=true` and `listRedemptions` counts them as one shared device. | Validate `^[0-9a-f]{8,32}$`; treat missing or invalid as "no signal" (skip query, don't store the sentinel); cap `displayName` characters and strip control/zero-width chars. |
| L4 | LOW | CODE-REVIEWED | Offerwall `md5`/`sha256` template schemes allow `{secret}` anywhere and no delimiters. With a leaked valid sig, `"{uid}{tx}{amt}{secret}"` lets `tx=123,amt=50` be re-split as `tx=12,amt=350`; secret-prefix md5 is length-extendable. | Only allow the partner's documented scheme; for templates require delimiters between placeholders and prefer HMAC. |
| L5 | LOW (becomes HIGH once buffs ship) | CODE-REVIEWED | Offerwall credits apply the Points buff (`index.ts` 3699); a negative amount is never scaled (`awardReward.ts` 167). A 3× credit reversed at 1× nets +2× per fraudulent offer. | Covered by H2's "reverse what was credited". |
| L6 | INFO | EMULATOR-PROVEN (fields present) | Abuse signals (`adlessStreakClaims`, `bonusAttemptsGranted`, `androidId`, `referredBy`) sit on the owner-readable user doc, so a farmer can watch their own detection counters. | Move them to `users/{uid}/private/risk` (server-only). |
| L7 | INFO | CODE-REVIEWED | `sessionId`, `optionId`, `packId` are used as document ids unvalidated; a `/` becomes a deeper path. No exploit found (reads miss, writes only after validation). | Validate `^[A-Za-z0-9_-]{1,64}$`. |
| L8 | INFO | CODE-REVIEWED | Replaying a paid game claim throws `already claimed` instead of returning the original result, so a lost response shows "refused" for a paid run. | Return the stored ledger entry on replay. |
| L9 | INFO | CODE-REVIEWED | Inconsistencies: weekly XP from `claimReward` uses **pre-buff** XP while `claimDoubleXp` adds buffed XP; referral unlock uses pre-buff `xpAward` vs buffed final; `claimDailyStreak`/`claimDailyGoalBonus` never check the referral unlock. | Use `award.xpAwarded` consistently; call `readReferrerForLevelUnlock` in the streak path. |
| L10 | INFO | CODE-REVIEWED | `AppConfig.kt` uses **AdMob test unit IDs** (`ca-app-pub-3940256099942544/*`). Production readiness issue. | Swap to real IDs per build type. |
| L11 | INFO | CODE-REVIEWED | Settlement: if a week's settlement is delayed more than a week and the player enters the next week too, `lastWeekKey` is overwritten and they drop out of the late settlement. | Alert when the settlement marker for last week is missing on Monday noon. |

---

## PHASE 5 — ECONOMY EXPLOIT TABLE

| Action | Expected reward | Calculated by | Client alters amount? | Duplicate protection | Daily limit | Server validation | Exploit risk |
|---|---|---|---|---|---|---|---|
| Game run | ≤60 XP | server (score/divisor, cap) | score only, bounded | session single-use, tx | 10 + 3 | rate vs session-start time | **High via parallel sessions + bots (C1)** |
| Quiz answer | 10 XP | server key | no | cap | 10 + 3 | key lookup | Medium (key readable) |
| Double XP | = base | server ledger | no | `:double` doc, tx | per entry, 10 min | source/status/ceiling | High (asserted ad) |
| Bonus attempt | +1 attempt | server | no | tx | 3/activity | cap | High (asserted ad) |
| Level reward | 5–150★/level | ledger at lock time | no | queue + locked status | XP-bound | order enforced | High via bot XP |
| Streak | 10–60 XP / 10–20★ | server table | no | day key, tx | 1/day | server UTC | Medium (asserted ad; ×accounts) |
| Goal bonus | 10★ live | config (clamp 200) | no | day key | 1/day | recomputed goals | Medium (×accounts) |
| Referral L10 | 50★ | constant | no | flag on referee | per referee | window <L10 | **High (spoofable device, farmable L10)** |
| Referral redeem | 150★ | constant | no | flag on referee | per referee | full-price only | **Medium (M1: paid pre-approval)** |
| Leaderboard | 50–350★ | settlement bands | no | ledger per week | weekly | frozen board | Medium (bots at cap, uid tie-break) |
| Offerwall | partner | partner + cap 20k | no (signed) | tx doc | none | signature | **High (H1, H2)** |
| Tournament entry | −fee | config | compare-only | week field, tx | 1/week | fee match | Low (accepted: <30 entrants profit) |
| Redemption | −cost | catalogue | no | tx balance | none | price/UID/first redeem | Medium (M2 dup orders; bot consolidation into one UID) |
| Refund | +cost | order doc | no | status pending | — | admin | Low |

### Chained exploits

- **Referral farm.** Spoofed androidId → bot to L10 in one day (C1) → +50★ to main. A full-price order into junk ID → +150★ → admin rejects → bot refunded (M1). Net ≈200★ per bot, near-zero cost.
- **Bot consolidation.** N bots each redeem full-price into the attacker's single PUBG ID. No per-UID limit applies to full-price redemptions.
- **Offerwall fraud.** Fake offer completions → credit → reversal swallowed (H2) → redeem.
- **Tournament.** Bots enter on weeks with <30 entrants; every scorer profits (accepted), and uid-ground bots win ties (L2).

---

## PHASE 6 — COST & FIREBASE OPTIMIZATION

### Estimated per heavy user per day (13 games, 13 quizzes, doubles, streak, goals, a few levels)

| Metric | Estimate | Main contributors |
|---|---|---|
| Function invocations | 60–90 | startGameSession 13, claimReward 26, claimDoubleXp ≤26, grantBonusAttempt ≤6, streak/goal 2, getLeaderboard ~3–5, claimLevelReward 1–3 |
| Server reads | ~100 | user doc per tx; game session; 3 per double; config refresh ≤1/5 min per instance; leaderboard user doc + count |
| Client reads | ~70–100 | user-doc listener fires per write (~60); payoutFeed attach 10/launch; `config/dailyGoals` get per UserRepository instance; levelCurve 1/process |
| Writes | ~130–150 | ~2.5 per claim (user update + ledger + session), 2 per double, 1 per session start |
| Cost/heavy user/day | ≈ $0.001 | Compute dominates (~0.4 s × 1 vCPU per call); writes ≈ $0.00025; reads ≈ $0.0001 |

Normal-use cost is well controlled. **The largest real cost risks are abuse-driven:**

- unbounded unauthenticated or unrestricted invocations (`checkEmailExists`, `startGameSession`, `syncQuizAnswerKey`, invalid-code `submitReferral` queries)
- fake accounts generating a full day of writes each

### Optimizations

| # | Change | Current | Proposed | Savings | Complexity | Risk |
|---|---|---|---|---|---|---|
| O1 | App Check + one open session + admin-only sync + delete checkEmailExists | unbounded abuse invocations | bounded | Removes the only unbounded cost vectors | Low–Med | Low |
| O2 | TTL on `gameSessions` (`expireAt`) and on XP-only `rewardEvents` older than 90 days | grows forever (~20k docs/user/year) | bounded storage | Storage + backup size | Low | Low (keep Star-moving entries forever) |
| O3 | Make `UserRepository` a process singleton (or move listeners and `fetchGoalBonus` into stores like `LevelCurveStore`); remove AuthStateListeners on clear | 9 construction sites, each adds a never-removed AuthStateListener and 3 listeners plus a `config/dailyGoals` get | 1 of each | Several reads/session; memory leak per game/quiz screen (the SDK multiplexes identical listen queries, so the listener duplication mostly costs memory, while the `get()`s multiply reads) | Medium | Medium (many call sites) |
| O4 | Index exemptions: `users.dailyStats`, `activeBuff`, `activeXpBuff`, `lastLeaderboardPrize`, `pendingLevelRewards`, `email`; `rewardEvents.metadata` | all subfields auto-indexed on the hottest document | exempted | Lower write latency and index storage | Low | Low (none are queried) |
| O5 | `ensureLevelCurvePublished` on cold start of claimReward/claimDoubleXp → move to the 6-hourly job plus a deploy script | 1 read + 1 write per cold start | 0 | Small; removes writes to a doc every client reads | Low | Low |
| O6 | Cache the caller's rank for 60 s per uid, or only compute a count for users with XP > board 30th | count query per non-board call (1 read per 1k entries) | cached | Grows with entrants (100 reads/call at 100k) | Low | Low |
| O7 | Remove dev-compat paths (`getEarningHistory` legacy window, `backfillAffectsPoints`, `repairStuckGameAttempts`, "older admin build" default) per CLAUDE.md | wider fallback reads | none | Minor reads; cleaner code | Low | Low |

### Scalability risks

1. `maxInstances: 10` everywhere (M7).
2. Hot `users/{uid}` document: every claim updates it. Parallel claims from one account hit the ~1 write/s sustained guidance and contention aborts; honest users are fine.
3. The leaderboard count query scales with entrants (O6).
4. `getReferralStats` truncates at 50 invitees, so counts are wrong for big referrers.
5. `index.ts` loads every dependency for every function instance, which lengthens cold starts (see memory note on cold-start latency).

---

## PHASE 7 — ARCHITECTURE / DUPLICATION

- **Monolith.** `functions/src/index.ts` is ~3,750 lines with 24 exports. Split by domain (`signup`, `play`, `rewards`, `leaderboard`, `redemption`, `offerwall`, `admin`) so each function loads less and reviews are scoped. The pure `economy/*` modules are the right pattern already.
- **Logic mirrored on the client (display only, but drift = support tickets):**
  - `UserRepository.LevelCurve.levelForXp`
  - `DailyGoalEngine` FNV-1a selection (must match `dailyGoals.ts`)
  - goal bonus clamp (`MAX_GOAL_BONUS_POINTS`)
  - attempt staleness rule (`quizAttemptsToday`)

  Keep them, but add one shared JSON fixture of cases (uid+day → goals, xp → level) consumed by both the TS unit tests and a Kotlin unit test.
- **Repeated server blocks.** `weeklyRollover` plus carry-field writing is copied in `claimReward`, `claimDoubleXp` and `enterTournament`; the referral unlock check is copied in two of three XP paths. Extract `applyWeeklyXp(userDoc, gain)` and `awardXp(...)` helpers.
- **Single sources of truth to fix:**
  - weekly XP should use the XP actually awarded (L9)
  - `hasUsedReferral` currently means both "device seen" and "code entered"; split it or drop the device half (L3)
  - abuse signals should not live on the user doc (L6)
- **Stale or misleading comments** (these will mislead the next change):
  - `index.ts` 281–284 says editing `config/levelCurve` "DOES NOT CHANGE WHAT IS PAID", but `ensureLevelRewardsFresh` (349–372) pays from that document.
  - `OfferwallActivity.kt` 153 says GamePlayActivity calls `proceed()` on SSL errors; it calls `cancel()`.
  - `rewardConfig.ts` 34–37 says every `MULTIPLIER_ELIGIBLE` entry is false; OFFERWALL/SURVEY/SPONSORED_APP are true.
  - `leaderboard.ts` 67–68 says "Nothing pays these out yet"; the settlement exists.
- **Magic numbers.** The game rules (`GAME_RULES`) are sensible constants. Economy numbers are split between code and console by design (see memory); the admin tool should display live config values.

---

## PHASE 8 — FAILURE / CHAOS RESULTS

| Scenario | Result |
|---|---|
| Claim tapped 20× quickly | Correct: game session single-use; level/streak/double/tournament keyed (6-way parallel proven). Quiz: each tap is a new attempt, capped. |
| Two phones claim simultaneously | Correct (transactions on the user doc). |
| Network drops after commit | Streak/goal/level/double/tournament retries are safe. **Redeem duplicates (M2).** A game replay shows an error though paid (L8). |
| Function executes twice / tx retries | Correct: all effects are inside the transaction; no side effects outside (no external calls in tx). |
| Force-close during redemption | Atomic: either order + debit or nothing. |
| Device time +24h / timezone hopping | No effect: server UTC everywhere. |
| Modified APK / direct API calls | **Works (C1)**: no App Check. |
| Missing / extra / huge / negative / NaN params | Rejected on reward paths (proven). `completeSignup` 500 on huge androidId (L3); `checkEmailExists` 500 on missing email. |
| 1,000 requests spam | Accepted up to `maxInstances` (M5, M7). |
| 100 accounts | **Works (C1)**. |
| Webhook delivered twice | Correct (tx doc). |
| Webhook reversal with same tx / out of order | **Wrong (H2)**. |
| History write succeeds, balance fails (or vice versa) | Impossible: same transaction. |
| Settlement dies midway | Resumes from frozen board; per-winner ledger id prevents double pay. |

---

## THINGS ALREADY DONE WELL

- **Firestore rules:** deny-by-default, *no client writes anywhere*, owner-only reads, a separate `serverConfig` for secrets, redemptions readable only through a `uid` filter. Emulator-confirmed: self points write, cross-user read, ledger forge and secret read are all denied.
- **Amounts never come from the client:** redemption price from the catalogue, tournament fee compare-only, double reads the ledger, level reward pays the locked amount.
- **Atomicity and idempotency** everywhere value moves: `streak:{day}`, `goals:{day}`, `game:{session}`, `levelup:{n}`, `{event}:double`, `tournament:{week}`, `leaderboard:{week}`, `refund:{id}`, offerwall tx doc. All proven under 6-way concurrency.
- **Server clock** for every day and week boundary; client `ServerClock` is display-only.
- **Settlement design:** freeze-then-pay, resumable, bounded pool, carried totals for players who moved on.
- **Offerwall basics:** mandatory signature, `timingSafeEqual`, hard code cap, redaction of secret-equal values, 200 on duplicates, 500 only on write failure.
- **Config guardrails:** clamps on goal bonus (200), entry fee (1000), postback (20,000); all-or-nothing validation of the level reward table.
- **Admin tool:** callable-only access, HTML escaping throughout, reasons prompt warns not to reveal detection.
- **Secrets hygiene:** no keys or secrets in git history (scanned); `.gitignore` covers service-account key patterns and `.env`.
- **Cost awareness:** board cache, count queries, `affectsPoints` index, cache-first catalogue and curve, settlement via index rather than sweeps.
- **Test culture:** `smoke.ts` (2,200 lines) and `rules.test.ts` already cover redemption race, referral race and the rules matrix.

---

## ATTACK PLAYBOOK (what I would try first)

| # | Attack | Status |
|---|---|---|
| 1 | Script fake accounts: spoofed androidId, parallel game sessions, stolen quiz key, asserted ads, level rewards, first-redeem taster per fresh game UID | **VULNERABLE** (proven) |
| 2 | Referral farm into one main account; full-price orders into junk IDs to trigger 150★, then get rejected and refunded | **VULNERABLE** (proven) |
| 3 | Offerwall fraud, keep Stars after advertiser chargeback | **VULNERABLE** (proven, for same-tx-id networks) |
| 4 | Forge offerwall postbacks | **PROTECTED** without the secret (403 proven) / **VULNERABLE** to anyone with log read access (H1 proven) |
| 5 | Write balance/XP/ledger directly via Firestore SDK/REST | **PROTECTED** (proven) |
| 6 | Race claims (level, streak, double, tournament, same session) | **PROTECTED** (proven) |
| 7 | Double-spend concurrent redemptions | **PROTECTED** (smoke.ts:1095 + tx review); sequential retry duplicates orders (M2) |
| 8 | Clock / timezone / daily-reset manipulation | **PROTECTED** (code-reviewed: server UTC) |
| 9 | Take over admin (phish the Google account, reuse old token) | **PARTIALLY PROTECTED** (claims-based, but single-factor, no recency, bootstrap always on) |
| 10 | Tournament capture with bots at XP ceiling + uid-ground accounts | **VULNERABLE** (tie-break unit-proven; pool economics accepted by you) |
| + | Email enumeration for credential stuffing | **VULNERABLE** (proven) |
| + | Storage upload abuse | **UNKNOWN / REQUIRES TESTING** (rules not in repo) |

---

## FIX ORDER

### P0 — must fix before launch

1. **H1:** delete the offerwall signature debug block; rotate any network secret that existed while it was deployed. → `functions:offerwallCallback`
2. **H2:** separate credit and reversal handling; reverse the credited amount. → `functions:offerwallCallback`
3. **C1a:** App Check (Play Integrity) on all callables and Firestore; debug provider for dev and emulator. → every callable plus an app build
4. **C1b:** gate value-out: verified email + minimum account age on `redeemReward` (first redeem included); record server-side IP signals on orders. → `functions:redeemReward,functions:listRedemptions`
5. **C1c:** one open game session per user + `gameSessions` TTL. → `functions:startGameSession,functions:claimReward`
6. **M1:** pay the referral redeem milestone on approval. → `functions:redeemReward,functions:resolveRedemption`
7. **M8:** verify and commit Storage rules. → `storage`

### P1 — strongly recommended before launch

8. **M2:** redemption idempotency key. → `functions:redeemReward` + app
9. **M3:** delete `checkEmailExists`; enable Email Enumeration Protection. → you run `functions:delete`
10. **M6:** admin 2SV, remove `bootstrapAdmin`, `auth_time` recency, admin audit log. → admin callables
11. **C1d:** AdMob SSV for Star-paying ad paths (level reward, goal bonus, streak Stars), then XP paths.
12. **C1e:** referral anti-abuse on server signals; stop using client androidId (with L3).
13. **M4:** right-most XFF. → `functions:offerwallCallback`
14. **L1:** `syncQuizAnswerKey` admin-only; move `quizAnswerKey` to `serverConfig`. → `functions:syncQuizAnswerKey,functions:scheduledQuizAnswerKeySync,functions:claimReward` + rules
15. **M7:** per-function instance/CPU/concurrency tuning; per-uid throttles on read callables.
16. **L10:** real AdMob unit IDs.

### P2 — shortly after launch

17. **L2:** tie-break by time reached (new index).
18. **L4 + L5:** HMAC/delimiter templates; buff-aware reversal (done with H2).
19. **L6:** move abuse signals off the user doc.
20. **L7 + L8 + L9:** id validation, replay returns the original result, consistent buffed XP, referral check in the streak path.
21. **O2:** TTL for XP-only ledger entries.

### P3 — optimization / cleanup

22. **O3:** `UserRepository` singleton / listener cleanup.
23. **O4:** index exemptions.
24. **O5, O6, O7:** cold-start publish, rank caching, remove dev-compat paths.
25. Split `index.ts`; shared TS/Kotlin fixtures; fix the stale comments listed in Phase 7.

---

## PHASE 9 — TESTS

`docs/security-audit/audit.cjs` is a self-contained emulator suite. To run it from the repo root after `npm --prefix functions run build`:

```bash
firebase emulators:exec --project demo-pixelpayout-audit --only auth,firestore,functions "node docs/security-audit/audit.cjs"
```

The script resolves `functions/` via an absolute path (`FN` at the top of the file). Change that constant if the repo moves.

`AUDIT_ONLY=<stepName>` runs a single section.

| Scenario ID | Current verdict | After the fix it should report |
|---|---|---|
| BOT-FARM-ONE-DAY | VULNERABLE | callables rejected without App Check; parallel sessions → 1; first redeem refused for new/unverified account |
| DEVICE-CHECK-SPOOF | VULNERABLE | not applicable once androidId is ignored |
| QUIZ-ANSWER-KEY-CLIENT-READABLE | VULNERABLE | denied |
| REFERRAL-REDEEM-BONUS-NOT-CLAWED-BACK | VULNERABLE | referrer 0 after rejection |
| REDEEM-NO-IDEMPOTENCY-KEY | VULNERABLE | 1 order |
| OFFERWALL-CHARGEBACK-SAME-TXID-SWALLOWED | VULNERABLE | balance 0 after reversal |
| OFFERWALL-DEBUG-LOG-SIGNING-ORACLE | log leaks `expected` | no `expected:` in log |
| OFFERWALL-IP-ALLOWLIST-XFF-SPOOF | VULNERABLE | 403 |
| EMAIL-ENUMERATION-UNAUTHENTICATED | VULNERABLE | not-found |
| GAME-SESSION-SPAM-UNBOUNDED | VULNERABLE | ≤1 doc |
| SIGNUP-ANDROIDID-UNVALIDATED / UNKNOWN-ANDROID-ID-COLLISION | VULNERABLE | invalid-argument / not flagged |
| SYNC-ANSWER-KEY-ANY-USER | VULNERABLE | permission-denied |
| LEADERBOARD-TIE-BREAK-BY-UID | VULNERABLE | earlier-reached wins |
| CONCURRENCY-AND-MALFORMED | PROTECTED | stays PROTECTED (regression guard) |
| RULES-SANITY | PROTECTED | stays PROTECTED |

When fixes are implemented, the scenarios should move into `functions/src/test/smoke.ts` so `npm run test:all` guards them permanently. That is a project-file change, so it needs your go-ahead.
