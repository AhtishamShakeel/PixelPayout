LootLevel — Claude Code Project Instructions

Project Status

LootLevel is currently in ACTIVE DEVELOPMENT and has NOT launched publicly.

There are currently no real production users.

Only the developer is using/testing the application.

Therefore:

- DO NOT assume the app is live.
- DO NOT preserve backward compatibility unless explicitly requested.
- DO NOT create migrations solely to protect old development builds.
- DO NOT keep obsolete code just because an older APK might depend on it.
- Breaking previous development builds is acceptable.
- The developer can uninstall/reinstall the app and clear local data.
- Prefer clean architecture and the correct final implementation over compatibility hacks for old test builds.

If a change would only be necessary because existing users might have an old app version installed, DO NOT implement it unless explicitly requested.

---

Firebase Cloud Functions — CRITICAL DEPLOYMENT RULE

NEVER recommend or execute:

firebase deploy --only functions

unless the developer explicitly asks to deploy ALL functions.

The Firebase project contains many Cloud Functions and deploying all functions is undesirable and may fail because of Firebase/project limitations.

When functions are changed, deploy ONLY the exact functions affected by the current work.

Example:

firebase deploy --only functions:functionName

Multiple affected functions may be deployed explicitly:

firebase deploy --only functions:functionOne,functions:functionTwo

Before suggesting a deployment:

1. Identify exactly which Cloud Functions were modified.
2. Deploy only those functions.
3. Do not deploy unrelated functions.
4. Do not deploy every function "to be safe."

If NO Cloud Function was modified, do not suggest a Functions deployment.

---

Do Not Assume Production Constraints

Because the app is not publicly released:

Do NOT automatically add:

- legacy compatibility layers
- temporary fallback paths
- dual-write systems
- database migrations for test users
- old-schema support
- version-gated logic for old APKs
- staged rollout infrastructure
- production rollout safeguards designed for an existing userbase

unless there is another technical reason they are genuinely needed.

When choosing between:

A) a cleaner implementation that breaks the previous development APK

and

B) a more complicated implementation that keeps the previous APK working

prefer A.

The developer can reinstall the app.

---

Database / Firestore Changes

Development data is disposable unless the developer explicitly says otherwise.

When changing Firestore structures:

- Prefer the structure that is best for the final product.
- Do not preserve bad schemas just to protect existing test data.
- Do not build migration systems for development-only data unless necessary.
- Ask before performing destructive changes to important project configuration or anything that may affect non-test resources.

Security Rules and Cloud Functions should remain secure even during development.

"Development" does NOT mean security can be ignored.

---

Security

LootLevel involves rewards with real-world value, so assume users will eventually attempt to exploit the application.

Sensitive reward/economy logic should be server-authoritative whenever practical.

Never trust the Android client for security-critical values such as:

- reward amounts
- currency balances
- XP awards where exploitable
- payout eligibility
- referral eligibility
- leaderboard-sensitive submissions
- purchase/reward verification
- offer/reward completion where server verification exists

Do not weaken security merely to simplify development.

---

Cost Awareness

LootLevel uses Firebase on the Blaze plan.

When designing features, consider:

- Firestore reads
- Firestore writes
- Cloud Function invocations
- unnecessary listeners
- repeated queries
- duplicated reads
- inefficient leaderboard queries
- excessive client refreshes

However, DO NOT prematurely create complicated caching systems unless they provide a meaningful benefit.

Prefer simple, secure, scalable solutions.

---

Before Making Large Architectural Changes

Do not silently redesign working systems.

If the requested task can be completed with a focused change, prefer that over rewriting unrelated architecture.

Before making a major architectural change:

1. Explain what problem the existing architecture has.
2. Explain why the larger change is necessary.
3. Identify what existing features it affects.
4. Prefer the smallest correct change unless a refactor clearly improves the project.

Do not turn a small bug fix into a project-wide refactor without a strong reason.

---

Existing Features

Assume existing working functionality should remain functional unless the task specifically changes it.

Before changing shared systems, check their callers/usages.

Pay particular attention to systems involving:

- authentication
- economy/currencies
- XP
- Stars/rewards
- games
- quizzes
- streaks
- referrals
- leaderboard
- wallet
- payouts
- offerwalls
- Firebase Security Rules
- Cloud Functions

---

Testing Expectations

After implementing a change:

1. Run relevant static analysis/build checks.
2. Test or describe tests for the modified feature.
3. Check obvious abuse/error cases.
4. Check loading/error/retry behavior where relevant.
5. Check that unrelated core behavior was not unintentionally changed.

Do not claim something is tested if it was only inspected.

Clearly distinguish:

- code reviewed
- build verified
- automated test verified
- emulator verified
- manually verified

---

Git Safety

Do not:

- force push
- delete branches
- reset commits destructively
- discard unrelated working-tree changes
- rewrite Git history

unless explicitly instructed.

Do not modify unrelated user changes just to make the working tree clean.

---

General Decision Rule

When uncertain, optimize for:

1. Security
2. Correctness
3. Clean final architecture
4. Firebase cost efficiency
5. Maintainability
6. Development convenience

Do NOT optimize for compatibility with development APKs that nobody except the developer has installed.

Most importantly:

THIS APP IS NOT CURRENTLY LIVE.

Do not design around imaginary production users.