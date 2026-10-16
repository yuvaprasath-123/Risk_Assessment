# Risk Assessment service

Java 21 / Spring Boot 4.1.1; configurable RISK_HTTP_PORT, default 8087. Developer 2 owns risk, while validation belongs to Developer 1. Existing Oracle D2_RISK tables are required. Automatic DDL/migrations/SQL initialization/seeding remain disabled; database/user/service credentials remain external.

Start with the current [integration handoff](../docs/developer2-handoff/README.md), [operations/recovery guide](../docs/developer2-handoff/operations.md) and [Risk OpenAPI](../docs/developer2-handoff/openapi/risk-assessment.openapi.json). They replace earlier incremental “engine-only/production adapter missing/readiness always blocked” statements without changing runtime code.

## Implemented HTTP contract

| Caller | Method / route | Meaning |
|---|---|---|
| FINANCE_MANAGER | GET /api/v1/risk-assessments/{assessmentId} | Verified stored summary; unfinished result null |
| FINANCE_MANAGER | GET /api/v1/risk-assessments/{assessmentId}/score-breakdown | Stored totals/contributions, no evaluation; unfinished result null |
| FINANCE_MANAGER | GET /api/v1/risk-assessments/{assessmentId}/factors | Seven completed outcomes/explanations, unfinished factors null |
| FINANCE_MANAGER | GET /api/v1/risk-assessments/{assessmentId}/configuration-snapshot | Pinned engine projection/hash, not unrestricted input evidence |
| Internal service | POST /internal/v1/risk-assessments | Reference-only risk-submission-v1; RISK_ASSESSMENT_SUBMIT and namespace ownership required |
| Internal service | GET /internal/v1/risk-assessment-submissions/status?submissionSource=...&submissionKey=... | Read-only status, same submit permission/namespace; **never progresses recovery** |
| Internal service | GET /internal/v1/risk-assessments/{assessmentId} | Completed only; RISK_ASSESSMENT_READ |
| Internal service | GET /internal/v1/risk-assessments/{assessmentId}/factors | Completed only; RISK_FACTORS_READ |
| Internal service | GET /internal/v1/risk-assessments/{assessmentId}/configuration-snapshot | Completed only; RISK_CONFIGURATION_READ |

Public manager authentication follows existing strict signature/expiry/userId/subject validation and case-insensitive Bearer/challenges. Separate opt-in internal RS256 trust requires issuer, exact receiver audience, allowed subject/kid/permissions/expiry/principal type; user tokens cannot submit. Missing enabled settings fail closed. No public POST, requester/approver user access, payment-ID lookup, token issuer, lease or raw diagnostics/evidence endpoint is added.

POST requires contractVersion, submissionSource/key, separate paymentReference and immutable validationReference, with optional nullable validationRevision/configurationVersionId. IDs/revisions in responses are strings; exact score integers remain numbers, money is decimal strings and timestamps UTC/nanosecond-capable. HTTP 200 means completed; 202 returns actual non-completed state, with Retry-After: 2 when retryAllowed. Missing prior acceptance can be 404 from status; same-identity POST recovers eligible work. BLOCKED requires intervention; no reset/unblock/scheduler API exists.

## Persistence and scoring

First acceptance atomically reserves a namespaced logical identity and associates an immutable configuration snapshot. Retries retain cutoff/configuration and compare accepted facts; omitted explicit configuration never selects a newer active version. Completed retries read verified stored results without dependencies/rescoring/timestamp changes. Genuine new linked resubmission identity remains a Developer 1 contract decision; payment ID is not the submission key.

Claim/renew/takeover use opaque token, epoch, attempt, revision and Oracle-clock expiry fencing. BLOCKED/COMPLETED cannot be reclaimed; expired/superseded workers cannot mutate/finalize. Required evidence is frozen all-or-nothing; technical failures withhold scores. Local supplier-wide prior COMPLETED history is separately captured immutably before reliance, preserving reconstructible records/coverage/hash. It uses COMPLETED_AT in [cutoff-window,cutoff), excludes current/retries, counts each submission once and preserves original triggered factors including weight-zero triggers. Visibility is the collection snapshot's committed population, not acceptance-time commit visibility. Retries/takeovers replay the stored capture.

Pinned risk-scoring-v1 evaluates outside DB locks, then finalizes seven verified outcomes/raw total/configured cap/band atomically under the final lease fence. INSUFFICIENT_HISTORY contributes zero while independent rules continue; missing/incomplete required evidence is technical failure, never zero-risk completion. [Demonstration examples](../docs/developer2-handoff/examples/README.md) specify all seven configurable approved policies without seeding.

## Production HTTP adapters

All three clients are implemented but **opt-in**, and absent/disabled provisioning remains unavailable with no normal-runtime demo fallback:

- Configuration: authenticated internal active/pinned reads, full publication hash verification, exact V2→engine mapping; versionRowVersion is used, not pointer revision.
- [Authoritative Facts](docs/authoritative-facts-v1.md): fixed trusted provider origin/path, PASS/reference/cutoff/identity certification, exact INR facts and immutable receipts.
- [Upstream Evidence](docs/upstream-evidence-v1.md): supplier-wide history/snapshot/transitions, retained same-snapshot pagination, coverage/exclusions/full-population digest, completeness/capacity handling. Developer 2 prior history is never accepted from upstream.

Facts/evidence protocols are Developer 2 provider contracts, **not existing Developer 1 endpoints**. Native clients use external credentials, strict exact parsing, bounded sizes/timeouts, no redirects and HTTPS outside loopback. External calls never run in write transactions. Certified original-cutoff retention/replay remains a provider duty until full freeze. Actual production issuer/TLS/provider availability and retention are not established by contract-test providers.

## Approval handoff and coordination

Developer 3 reads the assessment's engine snapshot to identify the pinned version/publication hash, then resolves that exact Configuration version for full approval bands/tiers/SLAs/policy. Risk configurationHash is distinct from publication contentHash. Never substitute current active configuration. V2 policy semantics are explicitly hash-covered; legacy reads retain old meaning and unsupported legacy new submissions fail closed.

Polling-based durable coordination is a proposal for D1/D3: durable submit identity/checkpoint, status GET for uncertainty and same-identity POST for recoverable work, then recipient deduplication of payment/workflow transitions. D2 has no callback/outbox/Kafka consumer/scheduler or cross-service exactly-once guarantee. D3 owns human approval, task timers/backup selection and business audit.

## Verification and operators

Latest retained evidence: **2,117 offline baseline;173 affected passes including 10 new safety tests** (overlap, do not add). **7 user-run configured-client Oracle tests passed**,zero failures/errors/skips. Real readiness publication/activation, all three production HTTP clients, submission, local prior capture, freezing/finalization were exercised with explicit contract-test providers and ephemeral issuer. All worker groups terminated and both schemas had zero fixture parents. Genuine D1/D3 integration remains pending.

The earlier D2_RISK 100M quota hit ORA-01536 during finalization verification; user increased it to 300M and the suite passed. Historical setup SQL retains original 100M and review stops. No new quota SQL is supplied here.

Use [configured-client Oracle verification](docs/configured-clients-oracle-verification.md) for the guarded opt-in full flow/cleanup command. Other isolated verification scripts are under [scripts](scripts/). The [completion checklist](../docs/developer2-handoff/completion-checklist.md) separates mocked transactions, native transport, genuine manager-token evidence and ephemeral-provider suites. No live tests/services were run for this handoff; actual Insomnia desktop import remains untested.
