# Validated examples and demonstration flow

These are synthetic wire examples, not database seed data or executed requests. Server-generated IDs, actor IDs, rowVersions and times illustrate one isolated flow; fetch actual values in a real session. No fixture represents an existing user/payment. Never reuse the example submission key for unrelated real facts.

## Complete publishable configuration

[publishable-configuration.json](publishable-configuration.json) contains the create request and all sixteen child PUTs. Individual bodies are in [configuration](configuration/). The actual request parsers, real readiness validator, V2 hash implementation and production configuration mapper accepted this draft in the offline documentation verifier; readiness was **not mocked**.

1. POST `configuration/create-draft.json`; retain returned version ID. GET its details for `rowVersion`.
2. PUT all seven `rule-*.json` bodies, then three `band-*.json`, three `threshold-*.json` and three `sla-*.json`. Use each returned parent revision for the next operation. The isolated example moves from revision `"0"` through `"16"`; do not assume those values in a populated database.
3. GET publication readiness. The complete example is structurally valid and ready with no issues. Incomplete drafts are legal and return issues; readiness is read-only.
4. POST publish with the current `expectedRowVersion`. The example returns revision `"17"`, PUBLISHED and a locally computed V2 content hash. Publication changes no active pointer. A repeated publish is 409 `VERSION_ALREADY_PUBLISHED`; stale drafts are 409 `STALE_VERSION`.
5. GET active configuration. If 404 `NO_ACTIVE_CONFIGURATION`, first activation sends `{"expectedPointerRowVersion":null}`. Otherwise use the actual `pointerRowVersion`, e.g. `{"expectedPointerRowVersion":"4"}`. Repeated activation of the same version with the matching pointer revision preserves activation metadata; stale expectations conflict.
6. Read the active and pinned snapshots. The generated [pinned snapshot](configuration/pinned-snapshot.json) and [active snapshot](configuration/active-snapshot.json) contain the complete hash-covered configuration and approval semantics.

| Rule | Demonstration weight | Parameters/meaning |
|---|---:|---|
| STRUCTURING | 35 | 7 days; minimum 2 individually sub-boundary eligible payments including current once; selected L2 and L3 boundaries; contribute once even if both match |
| UNUSUAL_FREQUENCY | 15 | More than 3 validated eligible submissions in 7 days, current once |
| UNUSUAL_AMOUNT | 20 | Current exceeds 2 × mean of completed payments in 90 days; minimum 5 observations; zero mean or insufficient observations => INSUFFICIENT_HISTORY, zero contribution |
| RECENTLY_REACTIVATED_SUPPLIER | 15 | Actual INACTIVE→ACTIVE transition within 30 days |
| PREVIOUS_RISK_FLAGS | 10 | At least one distinct qualifying prior submission within 90 days; all six other original TRIGGERED factors, including zero-weight triggers; never itself |
| NEW_SUPPLIER | 15 | Supplier created within 30 days |
| NEAR_DUPLICATE_INVOICE | 25 | Same supplier/exact amount, different EXACT invoice number, within 30 days; exact duplicates remain Developer 1 validation failures |

`risk-policy-v1` types all parameters. Activity eligibility is validated PENDING_APPROVAL, APPROVED, PROCESSING or COMPLETE. History excludes the current logical submission and all certified retries; the engine adds current only for structuring/frequency. Payment history windows use `[cutoff-window, cutoff)`; supplier creation/reactivation tests include the cutoff. Prior risk uses **COMPLETED_AT** in `[cutoff-window, cutoff)`. The local reader captures the collection snapshot's committed population, not acceptance-time visibility. Frozen capture/replay prevents later commits changing a retry's input.

Raw weights can total 135; final score caps at 100. LOW 0–29→L1, MEDIUM 30–60→L2, HIGH 61–100→L3. Values remain configurable within validated supported semantics. INR tiers `[0,10000)`, `[10000,100000)`, `[100000,∞)` in these files are **synthetic demonstration amounts, not approved business thresholds**. The user-approved SLA demonstration duration is 86400 seconds per level, never auto-seeded.

Approval authority is MAX(amount tier, risk level); all levels need human authorization. SLA starts on task PENDING. L1/L2 use `NEXT_LEVEL_V1`; L3 uses `SAME_LEVEL_BACKUP_ONCE_V1` and `MANUAL_REVIEW_V1`. No automatic approval or payment; see the expanded immutable `approvalPolicy` in the snapshot and the Developer 3 handoff.

## Submission, uncertainty and retry

[risk/submission.json](risk/submission.json) contains references only. Namespace `payment-request` must be explicitly assigned to the genuine submitting service principal. Payment ID `"501"` is separate from the stable source/key pair. The validation reference/revision must identify authoritative PASS facts; callers cannot send scores, hashes or evidence in this request.

| Event | HTTP/status | Example/action |
|---|---|---|
| Completed synchronously | 200 COMPLETED | [completed](risk/completed.json); seven factors and pinned configuration available |
| Accepted but unfinished | 202 ACCEPTED/IN_PROGRESS | [accepted](risk/accepted.json); persist identity and poll status |
| Lost response | Status GET 200, or 404 if not accepted | Exactly `submissionSource` and `submissionKey`, one each; this GET makes no work progress |
| Transient dependency failure after acceptance | 202 RETRYABLE_FAILURE | [retryable](risk/retryable-failure.json); bounded same-identity POST retry after backoff |
| Dependency unavailable before acceptance | 503 | No assessment guaranteed; status lookup, then same-identity POST after fixing dependency |
| Irreproducible cutoff/capacity intervention | 202 BLOCKED | [blocked](risk/blocked.json); stop automatic retries; no public unblock endpoint |
| Matching retry, configuration omitted | Existing pinned version/cutoff | [retry](risk/retry-omitted-configuration.json); do not select a newer active version |
| Explicit matching pinned version | Same immutable result | [explicit retry](risk/retry-explicit-configuration.json) |
| Changed payment/reference/facts or different explicit version | 409 SUBMISSION_CONFLICT | [conflicting request](risk/conflicting-retry.json), [sanitized error](errors/conflict.json); no overwrite/new identity workaround |

POST returns `Retry-After: 2` when `retryAllowed` is true, including active work. This is a retry hint, not a lease deadline or promise of completion. Completed retries return stored results without dependency calls, rescoring or timestamp changes. Scoring or network operations may not be exactly-once, but the persisted logical identity/fenced finalization prevents duplicate committed finalization.

[summary](risk/summary.json), [factors](risk/factors.json), [breakdown](risk/breakdown.json) and [risk snapshot](risk/configuration-snapshot.json) agree on one synthetic result. The factor explanations were generated by the actual engine over the certified empty-history example, with insufficient amount history contributing zero. This demonstrates legitimate completed LOW; missing/incomplete evidence is never converted to LOW.

Manager reads expose non-completed status with null result/factors, not fabricated zeroes. Internal result reads require COMPLETED and otherwise return 409 `ASSESSMENT_NOT_COMPLETED`. A pinned risk snapshot contains the engine projection, not the full approval/SLA policy. Developer 3 resolves the full pinned Configuration version separately and verifies its publication hash.

## Provider requests/responses and hashes

[providers](providers/) contains paired Facts and Evidence messages accepted by the actual production decoders/collector offline. They are provider contract examples, **not a claim those endpoints exist on Developer 1**. Complete empty history is certified COMPLETE with records `[]`; incomplete/unavailable history is different.

Publication `contentHash` hashes complete `configuration-content-v2`, including approval semantics. Risk `configurationHash` hashes the `risk-canonical-v1` wrapper/engine projection. The engine projection's `contentHash` retains the publication hash. Acceptance, input, local history capture, evidence population and manifest hashes have separate scopes; do not compare them as though interchangeable. Example hashes were generated locally by real implementation code; real generated row IDs/timestamps change publication hashes.

## Representation and validation limits

Positive IDs and all response revisions are decimal strings within Java Long; request revisions also accept exact JSON integers. Monetary threshold requests accept plain decimal strings exactly representable at scale 2; responses use two decimals. `"1.2300"` is exact; `"1.234"` is rejected. Risk/facts money strings use two decimals. No exponent notation for money. SLA durations, rule weights/orders and band scores are exact integer JSON tokens, not quoted numbers or `1.0`. Rule parameter objects preserve exact decimals/large integers in drafts; typed publication contracts further restrict allowed shapes. Timestamps require explicit offset; responses normalize UTC and risk retains nanoseconds.

OpenAPI/JSON Schema validates object types, required fields, limits and references; relational checks (tier continuity, max>min, timestamps/reference equality, publication hashes and complete semantics) are enforced by code and the offline Java example verifier. Do not infer certification from schema validation alone. MFA/password/token templates intentionally remain blank until private local input; they are templates, not positive authentication examples sent to any server.
