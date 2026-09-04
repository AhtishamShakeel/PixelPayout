# Offerwalls

How the offerwall integration works, and what you actually have to do when a
network approves you.

**The design goal was that approving a network costs no release.** Every wall
in the accessible tier is a hosted web page opened with the user's id in the
query string, and every postback is a signed HTTP call. Both are described by
data, so adding RevU, ayeT-Studios or Torox is two Firestore documents.

---

## The two halves

| | Who talks to whom | Where it is configured |
|---|---|---|
| **The wall** | User → network's web page | `config/offerwallWalls` |
| **The postback** | Network's server → us | `serverConfig/offerwall` |

They are configured separately because only one of them holds a secret, and
that distinction is the security boundary of the whole feature.

---

## The security boundary — read this before editing either document

`firestore.rules` grants **every signed-in user** read access to
`config/{docId}`. That is correct for the level curve and the goal bonus,
whose values are drawn on screen anyway.

It would be catastrophic for a postback secret. Anyone holding one can forge a
completion and mint stars.

So the secrets live in **`serverConfig/offerwall`**, a collection denied to
clients outright:

```
match /serverConfig/{docId} {
  allow read, write: if false;
}
```

A separate collection rather than a rule exception (`docId != "offerwall"`),
because the exception works today and fails silently the first time somebody
adds a second secret-bearing document to `config`. Here, the safe default is
the one you get by doing nothing.

**Never put a secret in `config/`. Never put a wall URL in `serverConfig/` —**
it is handed to the user's own WebView, so it was never private, and hiding it
there only means the app cannot read it.

---

## Adding a network

### 1. The wall — `config/offerwallWalls`

```json
{
  "walls": {
    "revu": {
      "enabled": true,
      "name": "RevU Offers",
      "subtitle": "Surveys and app installs",
      "urlTemplate": "https://offerwall.example.com/wall?pub=123&uid={uid}",
      "sortOrder": 1,
      "minLevel": 1
    }
  }
}
```

| Field | Notes |
|---|---|
| `enabled` | A wall is hidden unless this is exactly `true`. |
| `urlTemplate` | Must be `https://` and must contain `{uid}`. Anything else is dropped silently — see below. |
| `subtitle` | Optional. The row collapses it when empty. |
| `minLevel` | Gated walls are **hidden**, not shown locked. A locked offerwall teaches nothing and is dead weight on the one screen that has to look like it pays. |
| `sortOrder` | Ascending, then by name. |

A malformed entry is **dropped rather than shown**. One missing tile beats a
list that refuses to draw — but it also means a typo is invisible, so check the
wall actually appears after editing.

### 2. The postback — `serverConfig/offerwall`

```json
{
  "revu": {
    "enabled": true,
    "secret": "<from the network dashboard>",
    "scheme": "md5",
    "signatureTemplate": "{uid}:{transactionId}:{amount}:{secret}",
    "paramNames": {
      "uid": "uid",
      "transactionId": "transactionId",
      "amount": "amount",
      "signature": "signature",
      "status": "status"
    },
    "chargebackValues": ["2", "reversed"],
    "pointsPerUnit": 1,
    "maxPoints": 5000,
    "allowedIps": [],
    "successBody": "1"
  }
}
```

| Field | Notes |
|---|---|
| `scheme` | `md5`, `sha256` or `hmac_sha256`. Anything else makes the network unverifiable, and it is then rejected whole rather than half-honoured. |
| `signatureTemplate` | The string the network hashes, with `{placeholders}` naming **its own** query parameters plus `{secret}`. This is the only part that genuinely differs between networks — get it from their docs. |
| `paramNames` | What that network calls each thing. `status` is optional. |
| `chargebackValues` | Values of `status` meaning "reversed". Networks that send a negative `amount` instead need nothing here. |
| `pointsPerUnit` | Stars per unit of the network's currency. Results are **truncated**, never rounded up. |
| `maxPoints` | Per-postback ceiling. Clamped to `MAX_POINTS_PER_POSTBACK` (20,000) in code regardless — a console typo cannot raise it. |
| `allowedIps` | Optional. Defence in depth; the signature is the actual defence. |
| `successBody` | Exactly what the network treats as success — `"1"`, `"OK"`. Several retry for hours against anything else. |

### 3. Give the network your postback URL

```
https://<region>-<project>.cloudfunctions.net/offerwallCallback?network=revu
```

The network appends its own parameters. `network` must match the key in both
documents.

---

## How a completion is paid

1. Network calls `offerwallCallback`.
2. Signature verified **before** the amount is read — an unsigned request never
   reaches the code that decides what to pay.
3. Idempotency: `offerwallTransactions/{network}__{transactionId}`, a
   deterministic id read **inside** the award transaction. A pre-flight query
   would let two simultaneous retries both pass.
4. Award through `buildAward` with source `OFFERWALL`, which is
   `MULTIPLIER_ELIGIBLE` — the user's active Points buff applies here. This is
   the earning path that buff was designed for.
5. Ledger entry `offerwall:{network}:{transactionId}`.

### Status codes, and why

Networks retry anything that is not a 200, often for hours, so each reply is
chosen by whether retrying could ever help.

| Situation | Reply | Why |
|---|---|---|
| Paid | 200 + `successBody` | Done. |
| Duplicate | 200 | Already paid. A second payment is exactly what must not happen. |
| Unknown uid | 200, recorded `rejected_unknown_user` | Retrying cannot conjure the account. The audit row is settleable by hand. |
| Bad signature / IP | 403 | The partner's bug. Silence would hide it. |
| Malformed | 400 | As above. |
| Write failed | 500 | The **only** case a retry can fix. |

### Chargebacks

A reversal arrives either as a negative `amount` or as a `status` flag, and both
normalise to negative points. **A chargeback may take a balance negative, and
that is correct** — clamping at zero would let somebody redeem against a
completion and keep the stars when the advertiser reversed it.

---

## Testing without a network

The endpoint does not care who calls it. Put a test network in
`serverConfig/offerwall`, then:

```bash
UID=<a real firebase uid>; TID=test-1; AMT=25; SECRET=<your test secret>
SIG=$(printf '%s:%s:%s:%s' "$UID" "$TID" "$AMT" "$SECRET" | md5sum | cut -d' ' -f1)
curl "https://<region>-<project>.cloudfunctions.net/offerwallCallback?network=testnet&uid=$UID&transactionId=$TID&amount=$AMT&signature=$SIG"
```

Expect `1`. Run it twice — the second call must also return `1` and must **not**
award again. Then check the user's `rewardEvents` for
`offerwall:testnet:test-1`.

`functions/src/test/offerwall.test.ts` covers the validation logic without an
emulator: 54 cases, run by `npm run test:unit`.

---

## What is built, and what is not

**Built.** The postback endpoint, signature verification (md5 / sha256 /
hmac_sha256), idempotency, chargebacks, the double cap, the client wall list,
and the WebView host.

**Not built.**

- **No network is approved yet.** Both documents are empty, so the Rewards tab
  shows its empty state. That is deliberate — a dead button is a plausible Play
  review rejection.
- **Tapjoy.** The AAR is still in the build but nothing calls it. The old wiring
  was placeholder grade: `onRewardRequest` only logged, so no completion ever
  credited anybody; `onRequestSuccess` re-entered `requestContent()`; and
  `Tapjoy.connect` ran on every visit while the placement held an Activity
  across rotation. If Tapjoy is ever approved it should return through the
  catalogue, not as bespoke code on the Rewards screen — and its
  post-ironSource status should be confirmed first.
- **No analytics** on wall opens or completions. Worth adding before the first
  network goes live, or you will not know which wall earns.

---

## Notes on picking networks

Most of the accessible tier is **SDK-free and URL-based**, which is the shape
this integration handles: RevU, ayeT-Studios, Torox, OfferToro, Adscend,
Lootably, Notik, Revlum, and the survey routers (CPX Research, BitLabs,
Pollfish). SDK-based walls — Tapjoy, ironSource, Fyber — need a release to add
and do not fit the catalogue.

The barrier that actually blocks you is **"app must be live"**, not a DAU
minimum. That resolves by shipping.

**Run two or three at once, not five.** Fill is the whole game, but more than
three is clutter, splits attention, and makes it impossible to tell which one
is earning. `enabled` toggles them individually.
