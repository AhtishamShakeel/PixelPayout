/**
 * Offerwall server-to-server postbacks: the first money that arrives from
 * outside this app rather than being minted inside it.
 *
 * Pure, like the rest of this folder - it decides, the caller writes. That is
 * what lets the handler run verification, idempotency and the award inside
 * one transaction, and what makes the signature recipes testable without an
 * emulator and without a live network.
 *
 * THE CENTRAL DESIGN CHOICE IS THAT NETWORKS ARE CONFIGURATION, NOT CODE.
 * Every offerwall does the same three things - name the user, name the
 * transaction, sign the pair with a shared secret - and differs only in what
 * it calls those parameters and how it stacks them before hashing. Writing a
 * TypeScript module per network would mean a deploy to add ayeT-Studios
 * alongside Torox, and a deploy is exactly the wrong unit of work when the
 * blocker is a partner's approval email rather than an engineering decision.
 * So a network is a document in config/offerwall, and adding one is typing
 * its parameter names and its signature template into a console.
 *
 * What is deliberately NOT configurable is anything that bounds a loss: the
 * per-transaction cap has a hard ceiling in code, an unsigned request is
 * never honoured, and a transaction id is spent exactly once. Those are the
 * three properties that stop a leaked secret or a partner's bug from being
 * unbounded, and none of them should be one console typo away from off.
 */
import {createHash, createHmac, timingSafeEqual} from "crypto";

/**
 * One document per settled postback, keyed `{network}__{transactionId}`.
 *
 * A deterministic id rather than a query, for the same reason playerLinks
 * uses one: the check and the claim have to happen inside the award
 * transaction, and only a read of a known document id can take part in a
 * Firestore transaction. Two retries of the same postback arriving at once
 * must not both pay.
 *
 * Top-level rather than under the user, because the id has to be unique
 * across the whole app: a network's transaction id is unique to the network,
 * not to whichever account it happens to name.
 */
export const OFFERWALL_TRANSACTIONS_COLLECTION = "offerwallTransactions";

/**
 * Where the postback secrets live.
 *
 * A SEPARATE COLLECTION FROM `config`, and that is the whole point of it.
 * firestore.rules grants every signed-in user read on `config/{docId}` -
 * which is correct for the level curve and the goal bonus, whose values are
 * drawn on screen anyway - but this document holds shared secrets, and one
 * leaked secret is the difference between an offerwall and a mint.
 *
 * Putting it behind a rule exception (`docId != "offerwall"`) would work
 * today and fail silently the first time somebody adds a second secret-
 * bearing document to `config`. A separate collection makes the boundary
 * structural: everything in `serverConfig` is denied to clients outright, so
 * the safe default is the one you get by doing nothing.
 */
export const OFFERWALL_SECRETS_COLLECTION = "serverConfig";
export const OFFERWALL_SECRETS_DOC = "offerwall";

/**
 * The client-visible catalogue: which walls to draw, and where each one
 * lives. Deliberately in `config`, and deliberately holding no secret - an
 * offerwall URL is handed to the user's own WebView, so it was never private
 * to begin with.
 */
export const OFFERWALL_WALLS_DOC = "offerwallWalls";

/**
 * The hard ceiling on a single postback, whatever the config says.
 *
 * config/offerwall is hand-edited, and a leaked secret turns the endpoint
 * into a mint. Both failures are bounded by this number, and raising it is a
 * deploy - which is the point: the amount a single external request can
 * create should be harder to change than a field in a console.
 *
 * Set well above any real offer. A high-value CPI completion pays a few
 * hundred stars; nothing legitimate approaches this.
 */
export const MAX_POINTS_PER_POSTBACK = 20000;

/**
 * How a network builds the string it signs.
 *
 * Two schemes cover essentially every offerwall in this tier. Both are
 * driven by a template so the ORDER of the fields - which is the part that
 * actually differs between networks - is data rather than code.
 */
export type SignatureScheme =
  | "md5"
  | "sha256"
  | "hmac_sha256"
  /**
   * HMAC-SHA256 over the request's own query string, sorted alphabetically
   * by key - NOT over a template naming fields explicitly.
   *
   * ayeT-Studios signs this way, and it is a genuinely different shape: the
   * set of fields is whatever they chose to send, so there is nothing to
   * name in a template. Hashing the raw pairs rather than re-encoding a
   * parsed map is deliberate - "TEST+OFFER" and "TEST%20OFFER" are the same
   * value and different bytes, and re-encoding is how a verifier ends up
   * rejecting perfectly good callbacks.
   */
  | "hmac_sha256_sorted_query";

/** What one network's document in config/offerwall holds. */
export interface NetworkConfig {
  /** A network is off unless explicitly enabled. */
  enabled: boolean;
  /** Shared secret from the network's dashboard. Never logged. */
  secret: string;
  scheme: SignatureScheme;
  /**
   * The string to hash, with {placeholders} for query parameters and
   * {secret} for the shared secret - for example
   * "{uid}:{transactionId}:{amount}:{secret}".
   *
   * Placeholders name QUERY parameters by the network's own names, so the
   * template and paramNames below are read from the same vocabulary.
   */
  signatureTemplate: string;
  /**
   * Header carrying the signature, when the network sends one there rather
   * than as a query parameter. ayeT-Studios uses
   * `X-Ayetstudios-Security-Hash`. When set, paramNames.signature is unused.
   */
  signatureHeader?: string;
  /**
   * Parameters to leave OUT of a sorted-query signature.
   *
   * The network hashes the parameters IT sent. Anything we added to the
   * callback URL ourselves - `network`, most obviously - was never part of
   * that, so including it guarantees a mismatch on every single callback.
   * Defaults to ["network"], which is the only one this app adds.
   */
  excludeFromSignature?: string[];
  /** Which query parameter carries each thing we need. */
  paramNames: {
    uid: string;
    transactionId: string;
    amount: string;
    /** Ignored when signatureHeader is set. */
    signature: string;
    /** Optional: some networks flag reversals with a field rather than a
     *  negative amount. */
    status?: string;
  };
  /** Values of the status parameter that mean "this is a reversal". */
  chargebackValues?: string[];
  /**
   * Stars per unit of the network's currency. Networks are configured to
   * send whole stars wherever possible, so this defaults to 1.
   */
  pointsPerUnit?: number;
  /** Per-postback cap, clamped to MAX_POINTS_PER_POSTBACK regardless. */
  maxPoints?: number;
  /**
   * Optional source-IP allowlist. Empty or absent means no IP check - which
   * is acceptable ONLY because the signature is mandatory; the allowlist is
   * defence in depth, not the defence.
   */
  allowedIps?: string[];
  /**
   * Exactly what to write back on success. Networks differ - "1", "OK",
   * "ok" - and several retry for hours against anything else.
   */
  successBody?: string;
}

export type OfferwallRejection =
  | "unknown_network"
  | "network_disabled"
  | "network_misconfigured"
  | "ip_not_allowed"
  | "missing_parameters"
  | "bad_signature"
  | "bad_amount"
  | "amount_too_large";

export interface ParsedPostback {
  network: string;
  uid: string;
  transactionId: string;
  /** Positive to credit, negative for a reversal. Never zero. */
  points: number;
  isChargeback: boolean;
  /** Everything the network sent, for the audit record. Secret-free. */
  raw: Record<string, string>;
}

export type PostbackValidation =
  | {ok: true; parsed: ParsedPostback; successBody: string}
  | {ok: false; rejection: OfferwallRejection};

/** The transaction document id for a network's transaction. */
export function offerwallTransactionId(
  network: string,
  transactionId: string
): string {
  return `${network}__${transactionId}`;
}

/**
 * Reads a network's document out of the config map.
 *
 * Strict: anything missing a secret, a template or the parameter names is
 * treated as misconfigured rather than half-honoured. A network that cannot
 * be verified must not pay, and a half-written config document is exactly
 * the state a network is in while somebody is still typing it.
 */
export function resolveNetwork(
  raw: unknown,
  network: string
): NetworkConfig | null {
  if (!raw || typeof raw !== "object") return null;
  const entry = (raw as Record<string, unknown>)[network];
  if (!entry || typeof entry !== "object") return null;

  const config = entry as Partial<NetworkConfig>;
  const names = config.paramNames;

  if (typeof config.secret !== "string" || config.secret.length === 0) {
    return null;
  }
  const scheme = config.scheme;
  if (
    scheme !== "md5" &&
    scheme !== "sha256" &&
    scheme !== "hmac_sha256" &&
    scheme !== "hmac_sha256_sorted_query"
  ) {
    return null;
  }
  // The sorted-query scheme hashes whatever the network sent, so there is no
  // template to require. Every other scheme is unverifiable without one.
  const sortedQueryScheme = scheme === "hmac_sha256_sorted_query";
  if (!sortedQueryScheme && typeof config.signatureTemplate !== "string") {
    return null;
  }
  if (!names || typeof names !== "object") return null;
  if (
    typeof names.uid !== "string" ||
    typeof names.transactionId !== "string" ||
    typeof names.amount !== "string"
  ) {
    return null;
  }
  // A signature has to arrive SOMEWHERE. Either a header names it or a query
  // parameter does; neither means nothing can be verified, and a network
  // that cannot be verified must not pay.
  const signatureHeader = typeof config.signatureHeader === "string" &&
    config.signatureHeader.length > 0 ?
    config.signatureHeader :
    undefined;
  if (!signatureHeader && typeof names.signature !== "string") return null;

  return {
    enabled: config.enabled === true,
    secret: config.secret,
    scheme,
    signatureTemplate: config.signatureTemplate ?? "",
    signatureHeader,
    excludeFromSignature: Array.isArray(config.excludeFromSignature) ?
      config.excludeFromSignature.filter(
        (v): v is string => typeof v === "string"
      ) :
      ["network"],
    paramNames: {
      uid: names.uid,
      transactionId: names.transactionId,
      amount: names.amount,
      signature: typeof names.signature === "string" ? names.signature : "",
      status: typeof names.status === "string" ? names.status : undefined,
    },
    chargebackValues: Array.isArray(config.chargebackValues) ?
      config.chargebackValues.filter(
        (v): v is string => typeof v === "string"
      ) :
      undefined,
    pointsPerUnit: Number.isFinite(config.pointsPerUnit as number) ?
      (config.pointsPerUnit as number) :
      1,
    maxPoints: Number.isFinite(config.maxPoints as number) ?
      (config.maxPoints as number) :
      MAX_POINTS_PER_POSTBACK,
    allowedIps: Array.isArray(config.allowedIps) ?
      config.allowedIps.filter((v): v is string => typeof v === "string") :
      undefined,
    successBody: typeof config.successBody === "string" ?
      config.successBody :
      "1",
  };
}

/**
 * Fills the signature template from the query, then hashes it.
 *
 * A missing placeholder resolves to the empty string rather than throwing.
 * That is deliberate: a typo in the template must produce a signature
 * MISMATCH - a clean 403 and a log line - rather than a 500 that the network
 * will retry against for hours.
 */
/**
 * The exact bytes a sorted-query network signed.
 *
 * Built from the RAW pairs, not from a parsed map. Re-encoding is where this
 * goes wrong: "TEST+OFFER" and "TEST%20OFFER" decode to the same string and
 * hash to different digests, so a verifier that parses and re-encodes will
 * reject callbacks that were signed perfectly correctly. Splitting on "&"
 * and reordering the pairs untouched sidesteps the question entirely.
 *
 * Excluded keys are dropped because the network hashed only what IT sent -
 * `network` is ours, added to the callback URL, and including it would fail
 * every single callback.
 */
export function sortedQueryFor(rawQuery: string, exclude: string[]): string {
  return (rawQuery || "")
    .replace(/^\?/, "")
    .split("&")
    .filter((pair) => pair.length > 0)
    .filter((pair) => !exclude.includes(pair.split("=")[0]))
    .sort((a, b) => {
      const ka = a.split("=")[0];
      const kb = b.split("=")[0];
      return ka < kb ? -1 : ka > kb ? 1 : 0;
    })
    .join("&");
}

export function buildSignature(
  query: Record<string, string>,
  config: NetworkConfig,
  rawQuery = ""
): string {
  if (config.scheme === "hmac_sha256_sorted_query") {
    const sorted = sortedQueryFor(
      rawQuery,
      config.excludeFromSignature ?? ["network"]
    );
    return createHmac("sha256", config.secret).update(sorted).digest("hex");
  }

  const filled = config.signatureTemplate.replace(
    /\{(\w+)\}/g,
    (_match, key: string) =>
      key === "secret" ? config.secret : query[key] ?? ""
  );

  if (config.scheme === "hmac_sha256") {
    return createHmac("sha256", config.secret).update(filled).digest("hex");
  }
  return createHash(config.scheme).update(filled).digest("hex");
}

/**
 * Constant-time signature comparison.
 *
 * timingSafeEqual throws on a length mismatch, so the lengths are checked
 * first - and a differing length is itself a mismatch, which is safe to
 * answer immediately because the length of a hex digest is public.
 */
export function signaturesMatch(expected: string, received: string): boolean {
  const a = Buffer.from(expected.toLowerCase(), "utf8");
  const b = Buffer.from((received || "").toLowerCase(), "utf8");
  if (a.length !== b.length || a.length === 0) return false;
  return timingSafeEqual(a, b);
}

/**
 * Turns a raw callback into something the handler can act on, or says why
 * not.
 *
 * The order of the checks matters and is cheapest-first, with one exception:
 * the signature is verified BEFORE the amount is interpreted. An unsigned
 * request should never reach code that reasons about how much to pay, even
 * though the amount check is the cheaper of the two.
 */
export function validatePostback(input: {
  network: string;
  query: Record<string, string>;
  sourceIp: string | null;
  config: unknown;
  /** Lower-cased header names to values. Only read when a network signs in
   *  a header rather than a parameter. */
  headers?: Record<string, string>;
  /** The request's query string exactly as received, for sorted-query
   *  signing. Unused by template schemes. */
  rawQuery?: string;
}): PostbackValidation {
  const network = resolveNetwork(input.config, input.network);
  if (!network) {
    // Told apart in the logs, not in the response: the reply to an
    // unverified caller says nothing about which of the two it was.
    return {
      ok: false,
      rejection: input.config && typeof input.config === "object" &&
        input.network in (input.config as Record<string, unknown>) ?
        "network_misconfigured" :
        "unknown_network",
    };
  }
  if (!network.enabled) return {ok: false, rejection: "network_disabled"};

  if (network.allowedIps && network.allowedIps.length > 0) {
    if (!input.sourceIp || !network.allowedIps.includes(input.sourceIp)) {
      return {ok: false, rejection: "ip_not_allowed"};
    }
  }

  const {query} = input;
  const uid = (query[network.paramNames.uid] || "").trim();
  const transactionId = (query[network.paramNames.transactionId] || "").trim();

  // A header-signed network puts nothing in the query for us to read, so the
  // signature is looked up wherever this network actually sends it.
  const signature = network.signatureHeader ?
    ((input.headers ?? {})[network.signatureHeader.toLowerCase()] || "").trim() :
    (query[network.paramNames.signature] || "").trim();

  if (!uid || !transactionId || !signature) {
    return {ok: false, rejection: "missing_parameters"};
  }

  const expected = buildSignature(query, network, input.rawQuery ?? "");
  if (!signaturesMatch(expected, signature)) {
    return {ok: false, rejection: "bad_signature"};
  }

  const rawAmount = Number(query[network.paramNames.amount]);
  if (!Number.isFinite(rawAmount) || rawAmount === 0) {
    return {ok: false, rejection: "bad_amount"};
  }

  // A reversal arrives either as a negative amount or as a status flag with
  // a positive one, depending on the network. Both are normalised to a
  // negative points figure here so the handler has one case to write.
  const statusValue = network.paramNames.status ?
    (query[network.paramNames.status] || "") :
    "";
  const flaggedChargeback = Boolean(
    network.chargebackValues?.includes(statusValue)
  );
  const isChargeback = flaggedChargeback || rawAmount < 0;

  const magnitude = Math.abs(rawAmount) * (network.pointsPerUnit ?? 1);
  const points = Math.trunc(magnitude);
  if (!Number.isFinite(points) || points <= 0) {
    return {ok: false, rejection: "bad_amount"};
  }

  // Rejected outright rather than clamped. Clamping would pay a partial
  // reward against a transaction the network believes settled in full, and
  // the two ledgers would disagree for ever with nothing to show why. A
  // refusal is loud, retried, and visible in both systems.
  const cap = Math.min(
    network.maxPoints ?? MAX_POINTS_PER_POSTBACK,
    MAX_POINTS_PER_POSTBACK
  );
  if (points > cap) return {ok: false, rejection: "amount_too_large"};

  return {
    ok: true,
    successBody: network.successBody ?? "1",
    parsed: {
      network: input.network,
      uid,
      transactionId,
      points: isChargeback ? -points : points,
      isChargeback,
      raw: redactSecrets(query, network.secret),
    },
  };
}

/**
 * Strips anything matching the shared secret out of the stored copy.
 *
 * The audit record is read by hand during disputes, and some networks echo
 * parameters that a misconfigured template could have had the secret
 * substituted into. Cheap insurance against writing the secret into a
 * document that outlives the incident.
 */
function redactSecrets(
  query: Record<string, string>,
  secret: string
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    out[key] = value === secret ? "[redacted]" : String(value).slice(0, 500);
  }
  return out;
}
