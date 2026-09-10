/**
 * Pure unit tests for the offerwall postback validation. No emulator.
 * Run via: npm run test:unit
 *
 * This is the only path that credits Points on an outsider's word, so the
 * cases below are weighted toward the ways a caller could be lying rather
 * than toward the happy path.
 */
import {createHash, createHmac} from "crypto";
import {
  buildSignature,
  sortedQueryFor,
  offerwallTransactionId,
  resolveNetwork,
  signaturesMatch,
  validatePostback,
  MAX_POINTS_PER_POSTBACK,
  NetworkConfig,
} from "../economy/offerwall";

let passed = 0;
let failed = 0;

function ok(desc: string) {
  passed++;
  console.log(`  PASS  ${desc}`);
}

function fail(desc: string, detail?: unknown) {
  failed++;
  console.log(
    `  FAIL  ${desc}${detail !== undefined ? " -- " + JSON.stringify(detail) : ""}`
  );
}

function assertEq(desc: string, actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) === JSON.stringify(expected)) {
    ok(desc);
  } else {
    fail(desc, {actual, expected});
  }
}

const SECRET = "s3cr3t-from-the-dashboard";

/** A config map shaped the way config/offerwall holds it. */
function configWith(overrides: Partial<NetworkConfig> = {}) {
  return {
    testnet: {
      enabled: true,
      secret: SECRET,
      scheme: "md5",
      signatureTemplate: "{uid}:{tid}:{amt}:{secret}",
      paramNames: {
        uid: "uid",
        transactionId: "tid",
        amount: "amt",
        signature: "sig",
      },
      ...overrides,
    },
  };
}

/** A correctly signed query for the config above. */
function signedQuery(
  fields: {uid?: string; tid?: string; amt?: string} = {},
  secret = SECRET
) {
  const uid = fields.uid ?? "user-1";
  const tid = fields.tid ?? "tx-1";
  const amt = fields.amt ?? "100";
  const sig = createHash("md5")
    .update(`${uid}:${tid}:${amt}:${secret}`)
    .digest("hex");
  return {uid, tid, amt, sig, network: "testnet"};
}

function run(
  query: Record<string, string>,
  config: unknown = configWith(),
  sourceIp: string | null = "1.2.3.4"
) {
  return validatePostback({network: "testnet", query, sourceIp, config});
}

// --- transaction ids ---------------------------------------------------------
{
  assertEq("the transaction id namespaces the network",
    offerwallTransactionId("ayet", "abc"), "ayet__abc");

  // Two networks issuing the same transaction id must not collide, which is
  // the whole reason the network is in the key.
  assertEq("the same id from two networks is two documents",
    offerwallTransactionId("ayet", "1") !== offerwallTransactionId("torox", "1"),
    true);
}

// --- config parsing ----------------------------------------------------------
{
  assertEq("a well-formed network resolves",
    resolveNetwork(configWith(), "testnet") !== null, true);

  assertEq("an unlisted network is null",
    resolveNetwork(configWith(), "nope"), null);

  // Each of these leaves the network unverifiable, and a network that cannot
  // be verified must never pay. All-or-nothing, like parseLevelRewards.
  const broken: Array<[string, Partial<NetworkConfig>]> = [
    ["no secret", {secret: ""}],
    ["no template", {signatureTemplate: undefined}],
    ["unknown scheme", {scheme: "rot13" as NetworkConfig["scheme"]}],
    ["no paramNames", {paramNames: undefined}],
  ];
  for (const [desc, override] of broken) {
    assertEq(`${desc} is rejected whole`,
      resolveNetwork(configWith(override), "testnet"), null);
  }

  assertEq("a network is off unless enabled is exactly true",
    resolveNetwork(configWith({enabled: undefined}), "testnet")?.enabled, false);

  assertEq("successBody defaults to \"1\"",
    resolveNetwork(configWith(), "testnet")?.successBody, "1");
}

// --- signatures --------------------------------------------------------------
{
  const network = resolveNetwork(configWith(), "testnet") as NetworkConfig;
  const query = signedQuery();

  assertEq("a correct md5 signature is rebuilt exactly",
    buildSignature(query, network), query.sig);

  assertEq("hmac_sha256 uses the secret as the key",
    buildSignature(query, {...network, scheme: "hmac_sha256"}),
    createHmac("sha256", SECRET)
      .update(`${query.uid}:${query.tid}:${query.amt}:${SECRET}`)
      .digest("hex"));

  assertEq("sha256 hashes the same filled template",
    buildSignature(query, {...network, scheme: "sha256"}),
    createHash("sha256")
      .update(`${query.uid}:${query.tid}:${query.amt}:${SECRET}`)
      .digest("hex"));

  // A template typo must degrade to a mismatch, not to a crash the network
  // would retry against for hours.
  assertEq("an unknown placeholder becomes empty rather than throwing",
    buildSignature(query, {...network, signatureTemplate: "{nope}{secret}"}),
    createHash("md5").update(SECRET).digest("hex"));

  assertEq("comparison is case-insensitive on hex",
    signaturesMatch("ABCDEF", "abcdef"), true);
  assertEq("a differing signature fails", signaturesMatch("abc", "abd"), false);
  assertEq("a length mismatch fails rather than throwing",
    signaturesMatch("abc", "abcdef"), false);
  assertEq("an empty signature never matches", signaturesMatch("", ""), false);
}

// --- rejection paths ---------------------------------------------------------
{
  assertEq("an unknown network is refused",
    run(signedQuery(), {}), {ok: false, rejection: "unknown_network"});

  assertEq("a listed but broken network is told apart from an absent one",
    run(signedQuery(), configWith({secret: ""})),
    {ok: false, rejection: "network_misconfigured"});

  assertEq("a disabled network is refused",
    run(signedQuery(), configWith({enabled: false})),
    {ok: false, rejection: "network_disabled"});

  // The single most important test in this file: a wrong secret must never
  // pay, however well-formed everything else is.
  assertEq("a signature from the wrong secret is refused",
    run(signedQuery({}, "wrong-secret")),
    {ok: false, rejection: "bad_signature"});

  assertEq("tampering with the amount after signing is refused",
    run({...signedQuery(), amt: "999999"}),
    {ok: false, rejection: "bad_signature"});

  assertEq("tampering with the uid after signing is refused",
    run({...signedQuery(), uid: "someone-else"}),
    {ok: false, rejection: "bad_signature"});

  for (const missing of ["uid", "tid", "sig"]) {
    const query: Record<string, string> = {...signedQuery()};
    delete query[missing];
    assertEq(`a missing ${missing} is refused before anything else`,
      run(query), {ok: false, rejection: "missing_parameters"});
  }

  // Checked before the amount is ever interpreted: an unsigned request must
  // not reach the code that decides what to pay.
  assertEq("a bad signature outranks a bad amount",
    run({...signedQuery({amt: "0"}), sig: "deadbeef"}),
    {ok: false, rejection: "bad_signature"});

  assertEq("a zero amount is refused",
    run(signedQuery({amt: "0"})), {ok: false, rejection: "bad_amount"});

  assertEq("a non-numeric amount is refused",
    run(signedQuery({amt: "lots"})), {ok: false, rejection: "bad_amount"});
}

// --- the caps ----------------------------------------------------------------
{
  const huge = String(MAX_POINTS_PER_POSTBACK + 1);
  assertEq("an amount above the hard ceiling is refused, not clamped",
    run(signedQuery({amt: huge})),
    {ok: false, rejection: "amount_too_large"});

  assertEq("a network's own lower cap is honoured",
    run(signedQuery({amt: "500"}), configWith({maxPoints: 100})),
    {ok: false, rejection: "amount_too_large"});

  // A console typo must not be able to raise the ceiling past the deployed
  // one - that is the whole point of having two.
  assertEq("a config cap above the hard ceiling cannot raise it",
    run(signedQuery({amt: huge}), configWith({maxPoints: 1e9})),
    {ok: false, rejection: "amount_too_large"});

  assertEq("exactly the cap is allowed (boundary)",
    run(signedQuery({amt: "100"}), configWith({maxPoints: 100})).ok, true);
}

// --- the happy path and conversions -----------------------------------------
{
  const result = run(signedQuery());
  assertEq("a correctly signed postback validates", result.ok, true);
  if (result.ok) {
    assertEq("the uid is carried through", result.parsed.uid, "user-1");
    assertEq("the transaction id is carried through",
      result.parsed.transactionId, "tx-1");
    assertEq("the points are positive", result.parsed.points, 100);
    assertEq("it is not a chargeback", result.parsed.isChargeback, false);
    assertEq("the success body comes from config",
      result.successBody, "1");
  }

  const scaled = run(signedQuery({amt: "10"}), configWith({pointsPerUnit: 25}));
  assertEq("pointsPerUnit scales the award",
    scaled.ok && scaled.parsed.points, 250);

  const fractional = run(
    signedQuery({amt: "10"}),
    configWith({pointsPerUnit: 0.35})
  );
  assertEq("a fractional result is truncated, never rounded up",
    fractional.ok && fractional.parsed.points, 3);

  // Truncating to nothing must be a refusal rather than a zero-value award,
  // which would spend the transaction id for no credit.
  assertEq("an award that truncates to zero is refused",
    run(signedQuery({amt: "1"}), configWith({pointsPerUnit: 0.4})),
    {ok: false, rejection: "bad_amount"});
}

// --- chargebacks -------------------------------------------------------------
{
  const negative = run(signedQuery({amt: "-50"}));
  assertEq("a negative amount is a chargeback",
    negative.ok && negative.parsed.isChargeback, true);
  assertEq("a chargeback carries negative points",
    negative.ok && negative.parsed.points, -50);

  // The other shape: a positive amount with a status flag saying reversed.
  const flagged = configWith({
    signatureTemplate: "{uid}:{tid}:{amt}:{secret}",
    paramNames: {
      uid: "uid",
      transactionId: "tid",
      amount: "amt",
      signature: "sig",
      status: "state",
    },
    chargebackValues: ["reversed", "2"],
  });

  const byFlag = run({...signedQuery(), state: "reversed"}, flagged);
  assertEq("a status flag makes a positive amount a chargeback",
    byFlag.ok && byFlag.parsed.points, -100);

  const notFlagged = run({...signedQuery(), state: "credited"}, flagged);
  assertEq("an unlisted status value credits normally",
    notFlagged.ok && notFlagged.parsed.points, 100);

  assertEq("a chargeback is still bounded by the cap",
    run(signedQuery({amt: String(-(MAX_POINTS_PER_POSTBACK + 1))})),
    {ok: false, rejection: "amount_too_large"});
}

// --- the ip allowlist --------------------------------------------------------
{
  const locked = configWith({allowedIps: ["9.9.9.9"]});

  assertEq("an ip outside the allowlist is refused",
    run(signedQuery(), locked, "1.2.3.4"),
    {ok: false, rejection: "ip_not_allowed"});

  assertEq("an ip inside the allowlist passes",
    run(signedQuery(), locked, "9.9.9.9").ok, true);

  assertEq("an unknown ip is refused when an allowlist exists",
    run(signedQuery(), locked, null),
    {ok: false, rejection: "ip_not_allowed"});

  // Absent allowlist means no IP check - acceptable only because the
  // signature is mandatory either way.
  assertEq("no allowlist means any ip passes",
    run(signedQuery(), configWith(), null).ok, true);
}

// --- the stored audit copy ---------------------------------------------------
{
  const result = run({...signedQuery(), echoed: SECRET});
  assertEq("the secret is redacted out of the stored copy",
    result.ok && result.parsed.raw.echoed, "[redacted]");
  assertEq("ordinary parameters are kept for the audit trail",
    result.ok && result.parsed.raw.uid, "user-1");
}

// --- ayeT-Studios: header signature over a sorted query ----------------------
{
  const API_KEY = "publisher-api-key";

  /** ayeT's shape: header-signed, sorted-query HMAC, no template at all. */
  const ayet = {
    ayet: {
      enabled: true,
      secret: API_KEY,
      scheme: "hmac_sha256_sorted_query",
      signatureHeader: "X-Ayetstudios-Security-Hash",
      paramNames: {
        uid: "external_identifier",
        transactionId: "transaction_id",
        amount: "currency_amount",
        signature: "",
        status: "is_chargeback",
      },
      chargebackValues: ["1"],
      successBody: "ok",
    },
  };

  // The ordering example from ayeT's own documentation.
  assertEq("params are sorted alphabetically by key",
    sortedQueryFor(
      "user_id=testuser123456&amount=0.10&payout=1.50&click_id=1234abcd5678021",
      []
    ),
    "amount=0.10&click_id=1234abcd5678021&payout=1.50&user_id=testuser123456");

  assertEq("a leading question mark is ignored",
    sortedQueryFor("?b=2&a=1", []), "a=1&b=2");

  assertEq("excluded keys are dropped before hashing",
    sortedQueryFor("network=ayet&b=2&a=1", ["network"]), "a=1&b=2");

  // Re-encoding is the classic way to break this: "+" and "%20" decode alike
  // and hash differently, and only the bytes they signed will verify.
  assertEq("raw encoding is preserved, never normalised",
    sortedQueryFor("offer_name=TEST+OFFER&a=1", []),
    "a=1&offer_name=TEST+OFFER");

  const rawQuery =
    "network=ayet&external_identifier=user-9&transaction_id=tx-77" +
    "&currency_amount=40&is_chargeback=0";
  const hash = createHmac("sha256", API_KEY)
    .update(sortedQueryFor(rawQuery, ["network"]))
    .digest("hex");

  const query: Record<string, string> = {
    network: "ayet",
    external_identifier: "user-9",
    transaction_id: "tx-77",
    currency_amount: "40",
    is_chargeback: "0",
  };

  const runAyet = (
    headers: Record<string, string>,
    q: Record<string, string> = query,
    raw = rawQuery
  ) => validatePostback({
    network: "ayet", query: q, sourceIp: null, config: ayet, headers,
    rawQuery: raw,
  });

  assertEq("buildSignature reproduces the documented HMAC",
    buildSignature(query, resolveNetwork(ayet, "ayet") as NetworkConfig, rawQuery),
    hash);

  const good = runAyet({"x-ayetstudios-security-hash": hash});
  assertEq("a header-signed callback validates", good.ok, true);
  assertEq("external_identifier is read as the uid",
    good.ok && good.parsed.uid, "user-9");
  assertEq("currency_amount is read as the award",
    good.ok && good.parsed.points, 40);
  assertEq("successBody comes from config", good.ok && good.successBody, "ok");

  assertEq("no header at all is missing_parameters, not bad_signature",
    runAyet({}), {ok: false, rejection: "missing_parameters"});

  assertEq("a wrong header hash is refused",
    runAyet({"x-ayetstudios-security-hash": "0".repeat(64)}),
    {ok: false, rejection: "bad_signature"});

  // The whole reason excludeFromSignature exists: hashing our own `network`
  // parameter would fail every real ayeT callback.
  assertEq("including our own network param would break it",
    createHmac("sha256", API_KEY)
      .update(sortedQueryFor(rawQuery, []))
      .digest("hex") !== hash,
    true);

  const reversedRaw = rawQuery.replace("is_chargeback=0", "is_chargeback=1");
  const reversal = runAyet(
    {
      "x-ayetstudios-security-hash": createHmac("sha256", API_KEY)
        .update(sortedQueryFor(reversedRaw, ["network"]))
        .digest("hex"),
    },
    {...query, is_chargeback: "1"},
    reversedRaw
  );
  assertEq("is_chargeback=1 reverses the award",
    reversal.ok && reversal.parsed.points, -40);

  assertEq("a header-signed network parses without a signature param",
    resolveNetwork(ayet, "ayet") !== null, true);

  // Neither a header nor a parameter means nothing can be verified, and a
  // network that cannot be verified must never pay.
  const neither = JSON.parse(JSON.stringify(ayet));
  delete neither.ayet.signatureHeader;
  delete neither.ayet.paramNames.signature;
  assertEq("no header and no signature param is rejected whole",
    resolveNetwork(neither, "ayet"), null);
}

console.log(`\n=== ${passed} passed, ${failed} failed ===`);
if (failed > 0) process.exit(1);
