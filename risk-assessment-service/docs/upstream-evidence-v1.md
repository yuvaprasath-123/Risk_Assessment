# Upstream evidence provider v1

**Developer 2 integration contract; not an existing Developer 1 API.** The opt-in risk client uses only `POST /internal/v1/risk-evidence/resolve` at its configured trusted origin. This is a read-only lookup from the caller's perspective; the provider must durably capture an immutable receipt for replay. It must not submit payments, run validation, change workflow or return prior-risk assessments. Developer 2 captures and replays prior-risk history locally.

## Identity, authority and capture

The provider must authenticate `risk-assessment-service` with externally issued service credentials and receiver-owned `RISK_EVIDENCE_READ`. Use the agreed RS256 service-principal contract (trusted issuer/kid, exact provider audience, allowlisted subject/type, expiry and permission intersection). End-user credentials are not propagated. Provision keys/issuer/audience/grants with the provider; this client neither issues tokens nor adds this permission to D2 receivers.

HTTPS authenticates the configured receiver using JVM TLS trust and hostname verification. The response provider ID must also match the configured ID; it is not authority by itself. HTTP is accepted only for explicit loopback tests. Caller hashes and echoed references are preconditions, **not evidence certification**. The provider must resolve the immutable Facts receipt and certify the submission/payment/validation/supplier/cutoff binding from authoritative records. If it cannot, it must fail instead of asserting COMPLETE.

On the first request, collect the three sections coherently from one committed provider snapshot and retain that complete population **before returning page 0**. Historical states must be reconstructible at the requested evaluation cutoff. The population is the first collection snapshot's committed population filtered by the specified time rules; this does not claim acceptance-time commit visibility. All requesters are included. Late commits after capture cannot join that receipt on later pages/retries. A provider unable to certify historical cutoff state or complete source coverage must report UNAVAILABLE/INCOMPLETE, or HTTP 410 when original-cutoff replay is irreproducible. Do not use current mutable status as historical status.

The locally computed reference `ue1-<lowercase SHA-256>` hashes the UTF-8 JSON `request` below: recursively sorted object keys, compact JSON, preserved list order, explicit nulls, string IDs/hashes, UTC instants retaining nanoseconds. The existing locally computed `risk-canonical-v1` acceptance/configuration hashes bind the frozen input, configuration identity/content and cutoff; no caller-supplied hash is trusted. Exclusions sort by source then key using Unicode UTF-16 code-unit ordering (`String.compareTo`); object keys in this contract are ASCII. The protocol version is in the hashed request. Page size/cursor and client capacity limits are excluded, so changing transport limits cannot select a newer capture. Publish a new protocol version for incompatible population semantics.

For this v1 contract, the snapshot reference is that exact replay reference and snapshot revision is the literal string `1`. Reusing a reference/revision for changed records is forbidden. Retain immutable record receipts and the complete capture for the agreed risk retry/audit lifetime; operational retention duration still needs integration agreement. If a receipt expired, return 410, never recapture a newer population under the same key. Hash equality alone is not a retention guarantee.

Transient inability to establish the initial capture should return a non-200 failure (for example 503) without reserving a falsely authoritative population. Once a provider returns an explicit immutable INCOMPLETE/UNAVAILABLE capture, it cannot later upgrade that same receipt by changing records or assertions. Known irreproducible coverage should return 410 for intervention. Existing orchestration records other required-evidence gaps as retryable failures; an immutable permanently incomplete provider capture needs operational intervention, not endless attempts to recapture. Client-side capacity/page failures can recover by reading the retained complete receipt after limits/connectivity are repaired.

## Wire request

UTF-8 application/json with an external Bearer service token. All fields are required, with explicit nulls where shown. The adapter constructs them from stored acceptance/configuration, not the submission caller:

```json
{
  "request": {
    "contractVersion": "risk-upstream-evidence-v1",
    "submission": {"source": "developer1.payment.v1", "key": "submission-42"},
    "paymentId": "42",
    "requesterId": "7",
    "validationReference": "validation-42",
    "validationRevision": "3",
    "factsReceipt": {"serviceId": "facts-provider", "recordReference": "certified-facts-42", "recordRevision": "1"},
    "supplierId": "456",
    "currencyCode": "INR",
    "cutoff": "2026-10-15T10:20:30.123456789Z",
    "configuration": {"versionId": "9", "rowVersion": "12", "configurationHash": "<locally computed configuration hash>"},
    "acceptanceHash": "<locally computed acceptance hash>",
    "requiredCoverage": {
      "paymentFrom": "2026-07-17T10:20:30.123456789Z",
      "transitionFrom": "2026-09-15T10:20:30.123456789Z",
      "supplierSnapshotAt": "2026-10-15T10:20:30.123456789Z",
      "paymentPopulation": "SUBMITTED_OR_COMPLETED_V1",
      "transitionEnd": "INCLUSIVE"
    },
    "excludedIdentities": [{"source": "developer1.payment.v1", "key": "submission-42"}]
  },
  "replayReference": "ue1-<locally computed request hash>",
  "pageIndex": 0,
  "pageSize": 100,
  "cursor": null
}
```

The sample windows are illustrative, **not new defaults**: `paymentFrom` is cutoff minus the longest enabled STRUCTURING/FREQUENCY/AMOUNT/NEAR_DUPLICATE window; `transitionFrom` uses the enabled reactivation window. Null means that dependency is disabled; return it explicitly UNAVAILABLE with no rows. Supplier snapshot uses the cutoff. The client accepts only the frozen Facts-v1 receipt and reversible `submission-v1` aliases; legacy opaque aliases reject rather than guessing identity mappings. Genuine linked resubmission identity still needs Developer 1 confirmation.

## Response and pagination

HTTP 200 has exactly these root fields. `<request>` means the entire request object above, echoed exactly, including reference/revision, IDs, cutoff, configuration, coverage and exclusions. `<replay>` is the request's locally computed replay reference, not an arbitrary provider ID.

```json
{
  "contractVersion": "risk-upstream-evidence-v1",
  "request": "<request object, not a string>",
  "providerServiceId": "configured-provider-id",
  "replayReference": "<replay>",
  "snapshotRevision": "1",
  "populationHash": "<complete-population SHA-256>",
  "certification": {
    "immutable": true,
    "asOfCutoff": true,
    "supplierWideAcrossRequesters": true,
    "exclusionsApplied": true,
    "factsReceiptBindingVerified": true
  },
  "pagination": {
    "pageIndex": 0,
    "pageSize": 100,
    "totalPages": 1,
    "totalPayments": 0,
    "totalTransitions": 0,
    "nextCursor": null
  },
  "paymentHistory": {
    "availability": "COMPLETE",
    "sourceReference": "<replay>-paymentHistory",
    "sourceRevision": "1",
    "coveredFrom": "<paymentFrom offset timestamp>",
    "records": []
  },
  "supplier": {
    "availability": "COMPLETE",
    "sourceReference": "<replay>-supplier",
    "sourceRevision": "1",
    "coveredFrom": "<cutoff offset timestamp>",
    "records": [{"recordReference": "supplier-record-456", "supplierId": "456", "createdAt": "2026-01-01T00:00:00Z", "statusAsOfCutoff": "ACTIVE"}]
  },
  "supplierTransitions": {
    "availability": "COMPLETE",
    "sourceReference": "<replay>-supplierTransitions",
    "sourceRevision": "1",
    "coveredFrom": "<transitionFrom offset timestamp>",
    "records": []
  }
}
```

This template uses labelled substitutions; actual `request` is an object, and actual coverage fields are offset-aware timestamps or explicitly permitted nulls. Every certification flag must be literal true. Section availability is exactly COMPLETE, INCOMPLETE or UNAVAILABLE; it is not inferred from a nonempty list. COMPLETE requires coverage. UNAVAILABLE requires no records; incomplete coverage cannot be relabelled complete. Records may be partial only under INCOMPLETE. A COMPLETE empty payment/transition list with certified coverage is authoritative empty history; unavailable/partial lists are technical evidence gaps, never zero risk.

Every page echoes the same full request, replay/revision, certification, populationHash, section metadata, page size and population counts. Indexes start at zero and increment by one. `totalPages` includes the supplier-only/empty first page and is positive. `nextCursor` is required on each nonfinal page, null only on the final page, nonblank and at most 256 code points; a repeated cursor rejects. It is an opaque JSON value, never a URL. The client submits it to the same fixed path. Supplier rows appear only on page 0; COMPLETE supplier requires exactly one row. Each page may contain at most pageSize payments and pageSize transitions. The final accumulated counts must match. Duplicate submission/evidence identities, changed receipts/coverage/counts/cutoff or unsolicited prior-assessment sections reject.

`populationHash` is a lowercase SHA-256 digest of compact UTF-8 JSON containing exactly the three section objects (`paymentHistory`, `supplier`, `supplierTransitions`), with each full record list concatenated in page order and all section metadata retained. Recursively sort object keys; preserve record-array order and original exact string values (including timestamp offset spelling). Numeric metadata counters are outside this digest; record IDs/money are strings. The client requires the same digest on every page and recomputes it after full collection, before exposing COMPLETE evidence, so unchanged snapshot labels cannot conceal changed page contents. Certification/TLS still establish authority; the digest provides integrity, not proof of truth. Existing frozen-input canonicalization independently normalizes engine timestamps/records and hashes them locally.

Per-response byte caps, record capacity (including the possible supplier row), page caps or a missing later page cannot silently truncate a COMPLETE population. Record/page limits and failed later-page retrieval produce INCOMPLETE envelopes with no retained partial rows; UNAVAILABLE stays unavailable. Malformed or inconsistent manifests/pages fail DEPENDENCY_UNAVAILABLE. Oversized/malformed first responses fail without manufacturing an evidence envelope. HTTP 410 on any page propagates CUTOFF_NOT_REPRODUCIBLE so orchestration blocks work for intervention. Other first-page failures remain sanitized dependency failures. No response body or source diagnostic is exposed.

## Exact records and population boundaries

Payment history records have exactly:

```json
{
  "recordReference": "immutable-payment-record-77",
  "submission": {"source": "developer1.payment.v1", "key": "prior-submission-77"},
  "paymentId": "77",
  "requesterId": "8",
  "invoiceId": "124",
  "invoiceNumber": "INV-Ω-77",
  "supplierId": "456",
  "currencyCode": "INR",
  "amount": "1250.25",
  "submittedAt": "2026-10-10T10:00:00.123456789+05:30",
  "completedAt": null,
  "statusAsOfCutoff": "APPROVED",
  "validated": true
}
```

Include the complete union of rows with submission in `[paymentFrom, cutoff)` **or** COMPLETE rows with completion in that interval, even if submitted earlier. This preserves the amount rule's completion-based baseline. Do not prefilter by requester or invent a single history eligibility policy: the pinned engine filters each rule using validated state and configured eligibility. Supported historical states are the existing DRAFT, PENDING_VALIDATION, PENDING_APPROVAL, APPROVED, REJECTED, PROCESSING, COMPLETE and FAILED; `validated` must be an actual boolean. COMPLETE requires completion time; other states require null. All timestamps must be no later than cutoff and completion cannot precede submission. Exclude current and every supplied retry identity before paging. Do not equate payment ID with submission identity or classify a genuine new resubmission as a retry.

Actual status-change records have exactly:

```json
{"recordReference":"transition-123","supplierId":"456","previousStatus":"INACTIVE","nextStatus":"ACTIVE","changedAt":"2026-10-15T10:20:30.123456789Z"}
```

Include all actual ACTIVE/INACTIVE changes in `[transitionFrom, cutoff]`, inclusive at both ends to preserve engine semantics. Previous and next must differ. A complete sequence must chain, have unambiguous distinct change instants, and end at the supplier snapshot state. Same-instant ordering needs a future explicit provider contract; v1 fails closed instead of inventing an order. Supplier snapshot ACTIVE must agree with the frozen Facts-v1 certification; creation precedes current/history submissions and changes. No invented reactivation timestamp is accepted.

All payment/invoice/requester/supplier IDs are positive canonical Java Long **strings**. Supplier IDs must agree throughout. Money uses nonnegative plain decimal strings with exactly two fractional digits and at most 13 integer digits (`NUMBER(15,2)`), including `0.00` to preserve the approved zero-baseline policy. Numeric JSON money, scientific money strings, rounding and Double conversions reject. INR is mandatory. Invoice numbers are nonblank, at most 120 code points; no normalization occurs in the adapter. Record references are nonblank, at most 128 code points; all aliases use the Facts-v1 reversible encoding. Timestamps require seconds and an explicit ISO offset, up to nine fractional digits, UTC year 1–9999; offset-free/unknown `-00:00`/excess-precision values reject. Metadata request echoes use the original exact UTC strings. Record instants retain nanoseconds and lists sort deterministically by submittedAt/submission alias or changedAt/record reference.

## Freezing, replay and deployment

The adapter returns exactly three sources: PAYMENT_HISTORY, SUPPLIER, SUPPLIER_TRANSITIONS. Each source uses configured provider identity, `<replay>-<section>` reference and revision `1`. Engine records plus scope/availability/coverage/source references flow through the unchanged all-or-nothing evidence freezer and its local canonical hashes. Provider payment/requester IDs are verified but are not added to the engine's immutable Payment shape; retain their complete bindings at the original provider record receipts. No schema or canonical persistence format changes.

Before final freezing, retries restart page 0 against the same deterministic replay reference and immutable remote capture. No pages from a failed call are reused/mixed locally. Partial remote capture is retained by the provider, not mistaken for a frozen D2 manifest; local prior capture remains its independent existing transaction. Once frozen, orchestration reuses stored evidence without refetching. Differing evidence at an existing frozen identity conflicts through existing canonical checks; completed retries call no dependencies. Do not overwrite receipts, switch cutoff/configuration or recapture after expiry. No external calls run inside database transactions or acceptance/finalization locks.

Opt-in environment variables (all required when enabled):

| Variable | Meaning |
|---|---|
| `RISK_EVIDENCE_CLIENT_ENABLED` | Explicit true; default false |
| `RISK_EVIDENCE_BASE_URL` | Trusted HTTPS origin only; no path/query/fragment/userinfo. Explicit loopback HTTP for tests only. |
| `RISK_EVIDENCE_PROVIDER_SERVICE_ID` | Exact configured provider identity |
| `RISK_EVIDENCE_SERVICE_TOKEN` | External provider-audience service JWT; never commit or log |
| `RISK_EVIDENCE_CONNECT_TIMEOUT` | ISO duration, 1 ms–30 s |
| `RISK_EVIDENCE_HEADER_TIMEOUT` | ISO duration, 1 ms–30 s per page |
| `RISK_EVIDENCE_BODY_TIMEOUT` | ISO duration, 1 ms–30 s per page |
| `RISK_EVIDENCE_MAX_RESPONSE_BYTES` | 1–4,194,304 per response |
| `RISK_EVIDENCE_PAGE_SIZE` | Exact integer 1–1,000 |
| `RISK_EVIDENCE_MAX_PAGES` | Exact integer 1–100 |
| `RISK_EVIDENCE_MAX_RECORDS` | Exact integer 1–9,000; reserves orchestration capacity for local history |

Existing submission enablement/source grants, lease/budget, configuration/Facts clients, external database and security settings remain required. Missing opt-in returns ADAPTER_UNAVAILABLE with no native-client construction. Invalid enabled settings fail startup sanitized. This production adapter never falls back to fixtures; the separately explicit demo profile is not genuine integration. Credentials are reread per page; environment-backed rotation normally needs a process restart. No embedded issuer, key, renewal endpoint, cache, redirects, compression or automatic network retry. Header/body limits share the whole original dependency deadline across every page. Only bounded UTF-8 application/json with identity encoding is accepted. Shutdown cancels client work and waits at most five seconds.

Native tests exercise an explicit authenticated provider with ephemeral RSA credentials and an immutable in-memory receipt ledger, not Developer 1 infrastructure. Genuine integration still requires provider implementation, durable snapshot/record retention and restart-safe replay, certified as-of history across requesters, stable/retry identity agreement, dedicated issuer/grants/rotation and TLS/trust verification. D2 cannot prove those provider guarantees using a response hash or a contract-test ledger. Broad handoff documentation remains deferred.

Native verification covers the connect timeout with a stalled TLS handshake, header/body/whole-deadline cancellation, exact records, coherent pagination/replay, complete-population digest verification and local-only prior history. All 22 opt-in live Oracle test classes are explicitly excluded from offline regression commands. The run uses the existing test-JVM-only workspace Unix socket directory setting. No genuine provider, TLS certificate interoperability, Oracle or application service is exercised.

Checkpoint verification: **2,117-test offline baseline** and a separately reported **173 affected offline passes including 10 new safety cases**; these overlapping counts are not summed. All reported runs had zero failures/errors/skips. The user-run integrated configured-client Oracle suite passed **7 tests, zero failures/errors/skips**, exercising real readiness-approved publication/activation and all three production HTTP clients. All receivers/client/providers/dependency/concurrent workers terminated; guarded cleanup left **zero fixture parents in both schemas**. Implementation is unchanged, so results are reused. The agent did not run live Oracle tests, SQL or application services. Genuine Developer 1/3 providers, issuer/TLS interoperability and retention guarantees remain external integration checks; see [configured-client verification](configured-clients-oracle-verification.md).
