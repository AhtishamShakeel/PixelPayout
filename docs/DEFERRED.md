# Deferred work

Things we decided **not** to do yet, and why. Not a backlog of ideas — every
item here is something already investigated, costed, and consciously put down,
so that picking it up later does not start from scratch and does not
accidentally undo the reasoning that deferred it.

**Keeping this file useful**

- Add an item when work is deliberately deferred, not when it is merely
  imagined. If it was never investigated, it belongs in an issue, not here.
- Record the **cost of leaving it**, not just the fix. That is the part that
  decides when it stops being deferrable.
- Give concrete `file:line` anchors — they are what make an item actionable a
  month later.
- When one is done, move it to **Resolved** with the commit, rather than
  deleting it. The reasoning is worth keeping.
- Convert relative dates to absolute ones.

---

## Status

| # | Item | Kind | Blocks release? |
|---|------|------|-----------------|
| 1 | AdMob test ad unit IDs still in the build | Revenue | **Yes** |
| 2 | Redemptions listener loads full history on every launch | Scaling | No |
| 3 | Cloud Functions cold start on first game/quiz | Latency | No |
| 4 | 5-star reward for sharing a paid payout | Feature | No |
| 5 | `play-services-ads` version | Open question | No |

---

## 1. AdMob test ad unit IDs are still in the build

**Deferred:** ongoing, for as long as the app is in testing.
**Blocks release: yes.** This is the one item here that loses money rather than
just costing tidiness.

Every ad unit is currently Google's public **test** ID (`ca-app-pub-3940256099942544`):

| Where | Unit |
|-------|------|
| [`AppConfig.kt:7`](../app/src/main/java/com/example/pixelpayout/config/AppConfig.kt#L7) | Rewarded |
| [`AppConfig.kt:8`](../app/src/main/java/com/example/pixelpayout/config/AppConfig.kt#L8) | Game banner |
| [`AppConfig.kt:19`](../app/src/main/java/com/example/pixelpayout/config/AppConfig.kt#L19) | Interstitial |
| [`strings.xml:215`](../app/src/main/res/values/strings.xml#L215) | `admob_app_id` |

**Why deferred:** deliberate while testing — real IDs on a test device risk the
AdMob account, and test ads always fill, which is what makes ad-gated flows
(level-reward claims, bonus attempts) testable at all.

**Cost of leaving it:** test ads pay nothing. Shipping with these earns £0 while
looking completely normal, which is exactly why it is easy to miss.

**Doing it:** swap all four, and keep the test IDs reachable for debug builds —
a `buildConfigField` per build type is the usual shape, so debug never touches
live inventory. Verify with a release build that ads still fill before shipping.

---

## 2. Redemptions listener loads the user's full history on every launch

**Deferred:** 2026-09-03. Correctness is fine; this is purely how it scales.

[`UserRepository.kt:1241`](../app/src/main/java/com/example/pixelpayout/data/repository/UserRepository.kt#L1241)

```kotlin
firestore.collection(COLLECTION_REDEMPTIONS)
    .whereEqualTo(FIELD_UID, userId)   // no .limit()
```

**Cost of leaving it:** Firestore bills per document delivered, not per
connection — an idle listener is free. The charge is **per cold start**: one
read for every redemption that user has ever made. Reconnects within ~30
minutes ride a resume token and only pay for changes; past that it is a full
re-read.

A user redeeming monthly for two years carries 24 documents. At ~5 spread-out
opens a day that is ~120 reads/day **from this listener alone**, and it grows
forever. The 50k/day free tier covers roughly 400 such users. Fine now, not
fine later.

For comparison, `listenToPayoutFeed` at
[`UserRepository.kt:1453`](../app/src/main/java/com/example/pixelpayout/data/repository/UserRepository.kt#L1453)
is already capped at 3 — the same thinking, already applied once.

**Doing it — and why it is not a one-liner.** The obvious
`.orderBy(createdAt).limit(20)` collides with two things:

1. The missing ordering is **deliberate**. The comment above the query notes
   that one equality filter with no ordering is served by the automatic
   single-field indexes, so no composite index has to be deployed. Adding
   `orderBy` + `limit` requires a composite index on `(uid, createdAt)`.
2. The **Orders tab reads the same listener** for full history, so a `limit`
   would silently truncate it.

The clean shape is to split the two jobs: a small bounded listener for recent
and unsettled payouts (all the popup and the Home pending row need), plus a
paged one-off fetch for Orders history when that tab is actually opened.

---

## 3. Cloud Functions cold start on the first game/quiz

**Deferred:** 2026-09-01. User plans to add a splash screen first, then revisit.

**Update 2026-09-03 — the symptoms are handled, the latency is not.** The cold
start used to surface as two UI faults, both now fixed: the quiz froze on a
live-looking question with no feedback, and the game hung outright (a LiveData
`setValue` from the WebView's JavaBridge thread, thrown away inside the
bridge). A finished run also now waits up to 10s for an in-flight session
rather than being refused for want of one still on its way. So a cold start is
a spinner and a couple of seconds now, not a hang or a lost run — which lowers
the urgency here but does not remove it.

"First attempt is slow, the rest are swift" is **Cloud Run cold start**,
measured at ~1.4–2.9s from `firebase functions:log`. Not the function bodies,
not the answer-key fetch, not App Check. The log signature is
`Starting new instance. Reason: AUTOSCALING` → `STARTUP TCP probe succeeded`.
Observed: 3 minutes apart stayed warm; ~59 minutes and ~2 hours both went cold.

It bites twice because all 21 functions are separate Cloud Run services, so
`startGameSession` (game open) and `claimReward` (quiz submit) cold-start
independently. `claimReward` additionally pays `ensureLevelCurvePublished()`
once per instance.

**Options already costed:**

- **Client-side warm ping at app open** — free at current scale (~6% of the 2M
  invocation tier at 1k DAU), but the first user of a quiet stretch still pays.
- **~5-minute cron warm-up** — covers everyone, would sit at 3 of 3 free Cloud
  Scheduler jobs.
- **`minInstances`** — *not* free. An always-on instance is ~2.6M
  vCPU-seconds/month. Note the existing comment at `setGlobalOptions`: raising
  instance counts previously caused *"Container Healthcheck failed. Quota
  exceeded for total allowable CPU"*, so any fix here must be scoped to two or
  three hot functions, never applied broadly.

**If the warm ping is built:** it must be fire-and-forget and must never block
the splash — blocking adds the cold start to *every* app open to save it only
for users who then play. It also needs a `warm: true` early return that fires
before any side effects, since `startGameSession` creates a session doc and
checks the daily cap.

---

## 4. Five-star reward for sharing a paid payout

**Deferred:** 2026-09-03 — "ship the popup first". The share button on the paid
takeover currently fires a plain `ACTION_SEND` and pays nothing.

[`RedemptionNotifier.kt`](../app/src/main/java/com/example/pixelpayout/utils/RedemptionNotifier.kt) → `shareWin()`

**The design, if built.** Android gives no callback proving a message was
actually sent, so the tap is unverifiable by construction and must never be
trusted. What *is* verifiable server-side is that the caller owns an
**approved** payout and that this payout has not paid a share bonus before.

That turns an unbounded "press for stars" into a bonus capped by the number of
payouts we have approved by hand — the scarcest event in the whole economy. A
share that never happened costs five stars against a payout worth hundreds, and
no amount of tapping produces a second one.

Same shape as the bonus-attempt ads: **the cap is the security, not the
trigger.** Use a deterministic ledger id (`share:{redemptionId}`) so the ledger
entry *is* the already-paid record and there is no second field to keep in step
with it.

---

## 5. `play-services-ads` version

**Open question**, carried forward — the reasoning was never written down and
is not recoverable from the repo.

Currently `23.6.0` ([`app/build.gradle:96`](../app/build.gradle#L96), commented
"use a compatible version"), set in commit `9d89fa1`. A revert to `22.4.0` was
raised at some point but the motivation is not recorded.

**Before acting:** establish what the actual symptom was. Downgrading an ads
SDK on a hunch is a bad trade — newer versions carry mediation and policy
fixes. If nothing is currently broken, close this item rather than doing it.

---

## Resolved

_Nothing yet. Move items here with their commit when done._
