# Authoritative Facts provider contract v1

This is a **Developer 2 integration contract to be implemented/provisioned by an authoritative provider**. It is not an existing Developer 1 endpoint. The Risk Assessment client performs a read-only resolution using `POST /internal/v1/risk-facts/resolve` at one configured trusted origin. This operation must not submit a payment, run validation, change payment state or issue credentials. No URL is taken from the submission or response.

## Request and response

Requests use UTF-8 `application/json`, `Authorization: Bearer <external service credential>` and this exact shape (synthetic example):

```json
{
  "contractVersion": "risk-authoritative-facts-v1",
  "submission": {"source": "developer1.payment.v1", "key": "submission-42"},
  "paymentId": "42",
  "validationReference": "validation-42",
  "validationRevision": "3",
  "cutoff": "2026-10-15T10:20:30.123456789Z"
}
```

`submission` is the stable namespaced logical identity; it is **not** the payment ID. `validationRevision` is explicitly null when absent in the accepted reference-only request. There are no caller-supplied facts, hashes, configuration choice or callback URLs in this lookup.

The sole successful response is HTTP 200 with this exact field set, including explicit nulls:

```json
{
  "contractVersion": "risk-authoritative-facts-v1",
  "submission": {"source": "developer1.payment.v1", "key": "submission-42"},
  "paymentId": "42",
  "requesterId": "7",
  "validation": {
    "reference": "validation-42",
    "revision": "3",
    "outcome": "PASS",
    "validatedAt": "2026-10-15T10:20:29.123456789Z",
    "immutableReference": false
  },
  "cutoff": "2026-10-15T10:20:30.123456789Z",
  "payment": {
    "invoiceId": "75",
    "supplierId": "456",
    "amount": "1250.25",
    "currencyCode": "INR",
    "submittedAt": "2026-10-15T10:20:28.123456789Z",
    "completedAt": null,
    "statusAsOfCutoff": "PENDING_APPROVAL"
  },
  "invoice": {
    "invoiceId": "75",
    "supplierId": "456",
    "invoiceNumber": "INV-42",
    "invoiceAmount": "1250.25",
    "currencyCode": "INR"
  },
  "supplier": {
    "supplierId": "456",
    "statusAsOfCutoff": "ACTIVE",
    "createdAt": "2026-01-01T00:00:00Z"
  },
  "currentAndRetryIdentities": [
    {"source": "developer1.payment.v1", "key": "submission-42"}
  ],
  "certification": {
    "code": "AUTHORITATIVE_VALIDATION_PASS_V1",
    "complete": true,
    "asOfCutoff": true,
    "immutable": true,
    "validationPaymentBindingVerified": true,
    "requesterOwnershipVerified": true,
    "eligibleForRiskAssessment": true,
    "retryIdentityScope": "SAME_LOGICAL_SUBMISSION"
  },
  "provenance": {
    "serviceId": "authoritative-facts-provider",
    "recordReference": "certified-facts-42",
    "recordRevision": "1"
  }
}
```

These assertions are obligations of the trusted provider, not authority inferred from a supplied hash. The provider must authenticate its caller with a dedicated service credential, authorize `risk-assessment-service` for provider-owned `RISK_FACTS_READ`, and certify the payment/requester/submission/validation binding using authoritative records. Use the agreed service-principal contract (RS256, trusted `kid`, issuer, exact provider audience, service subject/type, expiry and receiver-owned grants). Provision its audience/issuer/trust keys with the provider; D2 does not mint tokens or add this permission to its own receiver grants. HTTPS and trusted origin configuration authenticate the remote receiver; a self-declared `serviceId` alone does not. The configured provider identity must also equal `provenance.serviceId`.

## Validation and immutable identity

- Unknown/missing fields, duplicate keys, invalid UTF-8, trailing JSON, non-object sections and coercible-but-wrong types reject. Parsing depth is bounded to 16, number length to 64 and string length to 16,384. No payload or credential is logged or included in failures.
- Submission source/key, payment ID, validation reference/revision and cutoff must equal the request (cutoff compares instants). Positive IDs are canonical Java Long decimal **strings**. Namespaces are lowercase `[a-z][a-z0-9.-]{0,63}`; keys/reference/revision lengths are at most 128 Unicode code points. Provider service IDs use `[a-z][a-z0-9-]{0,63}`.
- Validation must already be authoritative PASS at the cutoff; FAIL is never usable. An explicit revision selects that exact immutable validation record; do not resolve the newest revision. With a null revision, the reference itself must be immutable (`immutableReference: true`), and the response revision must remain null. A mutable reference without a requested revision rejects. `certification.immutable` certifies the entire resolved receipt, including explicit reference/revision pairs.
- Every certification boolean must be the literal JSON `true`. A hash is neither a certification substitute nor a supported response field. A stale/mismatched record, inaccessible receipt, uncertain ownership, missing fields or irreproducible cutoff must fail rather than return guessed facts.
- Payment/invoice/supplier associations must agree. Supplier status must be ACTIVE. Money is a positive plain **decimal string**, exactly two fractional digits, at most 13 integer digits (`NUMBER(15,2)`); no Double, rounding, exponent, JSON numeric money or zero amount. Both currencies must be INR. Invoice number is nonblank, at most 120 code points. Payment/invoice amounts need not be equal: partial-payment eligibility remains an authoritative validation responsibility, not a new D2 policy.
- Timestamps require full ISO-8601 date/time with seconds and an explicit offset (`Z` or `+/-HH:MM`), zero to nine fractional digits and UTC year 1–9999. Offset-free times, zone IDs, unknown `-00:00`, excess precision and invalid offsets reject. Preserve the exact instant at nanosecond precision. Require supplier creation <= submission <= validation <= cutoff. Completion, when present, is between submission and cutoff.
- Current payment states may be PENDING_VALIDATION, PENDING_APPROVAL, APPROVED, PROCESSING or COMPLETE, with certified eligibility. PENDING_VALIDATION supports an already-PASS record before workflow status propagation. DRAFT/REJECTED/FAILED reject. COMPLETE requires `completedAt`; other states require null. This current-record rule does not redefine historical activity eligibility.
- `currentAndRetryIdentities` contains 1–1,000 unique pairs, including current. Certify all as retries of the **same logical submission**, never a genuine new linked resubmission. Retry lineage still requires Developer 1 agreement. These identities must use the same encoding in the future payment-history adapter, otherwise exclusion verification fails.
- Engine submission aliases are locally encoded as `submission-v1:<base64url(source)>:<base64url(key)>`, UTF-8 without padding. They do not equate payment and submission IDs. The current evidence reference is `facts-v1:<base64url(serviceId)>:<base64url(recordReference)>:<base64url(recordRevision)>`.

Existing acceptance canonicalization freezes the verified engine payment projection (including invoice number, supplier/invoice IDs, exact amount, status, timestamps), requester/payment IDs, validation reference/revision, cutoff, retry aliases and that source receipt identifier. It does **not** store the entire HTTP certification/supplier/invoice envelope. The provider must retain the complete certified receipt immutably and reconstructibly at `(serviceId, recordReference, recordRevision)`, including auxiliary supplier creation/invoice amount assertions. Do not reuse receipt revisions for changed facts. Local acceptance hashes remain separately computed; no persistence format or schema is changed.

## Opt-in runtime configuration

Normal runtime remains unavailable unless submission and this client are explicitly enabled. Existing manager reads remain usable without provisioning either. Under normal profiles, this client never falls back to demo facts. The existing explicit `risk-submission-demo` profile has its separate fixture adapters; do not enable it for genuine integration.

| Environment variable | Required when enabled |
|---|---|
| `RISK_FACTS_CLIENT_ENABLED` | `true`; default false |
| `RISK_FACTS_BASE_URL` | Trusted HTTPS origin only; no path, query, fragment or userinfo. Plain HTTP allowed only for explicit loopback testing. |
| `RISK_FACTS_PROVIDER_SERVICE_ID` | Exact trusted provider identity |
| `RISK_FACTS_CONNECT_TIMEOUT` | Positive ISO duration, 1 ms–30 s |
| `RISK_FACTS_HEADER_TIMEOUT` | Positive ISO duration, 1 ms–30 s |
| `RISK_FACTS_BODY_TIMEOUT` | Positive ISO duration, 1 ms–30 s |
| `RISK_FACTS_MAX_RESPONSE_BYTES` | Explicit integer 1–4,194,304; choose a suitable deployment bound |
| `RISK_FACTS_SERVICE_TOKEN` | Externally issued service JWT, separate from user signing material; never commit it |

Also retain existing `risk-submission.enabled`, source grants, dependency budget, lease and external database configuration. Enabled-but-incomplete client settings fail startup sanitized; disabled clients do not initialize native transport. The provider credential is read for every call, not embedded or copied from the submitting user's token. A replaceable external property source can rotate it; process-environment changes normally require restarting the process. No issuance, renewal or production credential storage is supplied.

The connect phase has its own bound; response headers and complete body have separate limits within the caller's overall dependency deadline. Streaming byte limits apply even without Content-Length. Only UTF-8 application/json and identity encoding are supported. No redirects, automatic retries, cache or payload logging. All non-200, unauthorized, malformed, oversized, corrupt or timeout results become sanitized DEPENDENCY_UNAVAILABLE; disabled/missing runtime credentials become ADAPTER_UNAVAILABLE. Calls inside an active transaction reject before network I/O.

Completed retries short-circuit in the existing orchestrator without facts/configuration/evidence calls. Accepted retries restore original facts/configuration/cutoff rather than querying this provider again. First-resolution failure occurs before acceptance and creates no assessment. The production upstream payment/supplier/transition evidence adapter remains unavailable; this Facts client alone does not make full genuine assessment integration operational.

## Offline verification and remaining interoperability

Native contract tests use a temporary loopback provider, real configured JDK client, real service-token verification and ephemeral test-only RSA credentials. They cover wiring, authentication, malformed authority/reference claims, exact money/Unicode/nanoseconds, bounded responses, header/body/deadline cancellation, no redirects, token replacement, transaction rejection and completed-retry short-circuiting. Listeners, workers and clients are shut down. No application service or Oracle is involved.

Genuine integration still requires an implemented provider with immutable receipt retention and cutoff guarantees, agreed stable/retry identities, provider-owned authorization and issued credentials, TLS/trust provisioning, and native HTTPS verification against that provider. These tests do not establish Developer 1 endpoint availability or genuine issuer interoperability.
