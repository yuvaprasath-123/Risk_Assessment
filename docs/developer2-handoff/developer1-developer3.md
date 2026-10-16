# Developer 1 / Developer 3 integration contract

Implemented receivers and clients are listed in the OpenAPI files. The Facts/evidence routes are **Developer 2 provider protocols**, not discovered Developer 1 APIs. The service names/audiences/namespaces below are proposed provisioning examples, not identities silently enabled in the runtime. Receiver-owned external grants/namespace configuration determine actual access.

## Authentication and permissions

| Consumer → receiver | Minimum receiver-owned permission | Contract |
|---|---|---|
| Risk → Configuration | CONFIGURATION_ACTIVE_READ, CONFIGURATION_VERSION_READ | Active once on new acceptance; pinned version for explicit selection; verified V2 hash and versionRowVersion |
| Developer 1 submitter → Risk | RISK_ASSESSMENT_SUBMIT | Reference-only POST and same-namespace status; source namespace must belong to authenticated subject |
| Developer 3 → Risk | RISK_ASSESSMENT_READ, RISK_FACTORS_READ, RISK_CONFIGURATION_READ | Completed assessment, factors and engine configuration projection |
| Developer 3 → Configuration | CONFIGURATION_VERSION_READ | Full pinned published policy, bands, amount thresholds and SLAs; active permission is unnecessary for routing an existing assessment |
| Risk → authoritative Facts provider | RISK_FACTS_READ (provider-owned) | Versioned PASS certification and immutable validation/facts reference |
| Risk → upstream Evidence provider | RISK_EVIDENCE_READ (provider-owned) | Certified supplier-wide retained population, immutable receipts and complete pages |
| Human FINANCE_MANAGER → public D2 APIs | User FINANCE_MANAGER role | Separate issuer/key material and manager JWT validation; not an internal principal |

Example receiver grants: Configuration may allow `risk-assessment-service` both configuration reads and `approval-service` only version reads. Risk may allow `payment-request-service` submit and `approval-service` completed reads. Namespace ownership could be `{"payment-request-service":["payment-request"]}`. These external JSON settings must be explicitly provisioned; no built-in allowlist is populated by the package.

Separate service issuer/private signing material from user JWT material. Receivers trust explicitly provisioned RSA public keys ≥2048 bits by kid, RS256 only. Required issuer, exactly one receiver audience, allowlisted subject, expiry, `typ=service+jwt`, `principalType=SERVICE` and unique granted permissions; roles/userId are not accepted. A token cannot self-grant additional permissions or cross receiver audiences. Unknown keys/identities/claims fail closed. Agree secret distribution, token lifetime/refresh, overlapping-key rotation and HTTPS trust with both teams; D2 has no production issuer or refresh endpoint.

## Developer 1: stable identity and authoritative inputs

Assign one immutable **(submissionSource, submissionKey)** per logical validated submission. Payment ID is a separate association: never substitute it for the logical identity or use a transport event ID as identity. Retries reuse exactly the same logical pair, payment reference, validation reference/revision and explicit configuration choice (if supplied). Namespace text/key encoding limits are in the Risk schema. Conflicts return 409 without replacing frozen data.

A genuine corrected/resubmitted payment needs an externally agreed new linked submission identity. That lineage/retry-identity contract remains pending Developer 1 confirmation; D2 does not infer lineage from payment ID, invoice or requester. Provide the complete set of same-logical-submission retry identities so current/retry records can be excluded consistently.

Only submit after authoritative validation PASS. PASS is not a client boolean: the Facts provider verifies supplier/payment/invoice identity, requester ownership, validation/payment binding, eligibility and immutable validation reference/revision at the requested cutoff. A missing explicit revision is accepted only if the reference itself is certified immutable. FAIL or stale/mismatched references do not qualify. Reference-only submissions never accept caller-supplied hashes, scores or raw facts.

See [Facts protocol](../../risk-assessment-service/docs/authoritative-facts-v1.md) and [provider OpenAPI](openapi/facts-provider.openapi.json) for the exact message. It requires INR/exact decimal amounts, explicit-offset timestamps, actual supplier creation time, invoice number, current status and retry scope. Provider identity is matched to configured origin/trust, not just the JSON claim. A digest alone is not authority. Retain the original certified facts receipt; acceptance preserves the engine projection and receipt ID, not every upstream envelope field.

The [Evidence protocol](../../risk-assessment-service/docs/upstream-evidence-v1.md) requires:

- Supplier-wide history across **all requesters**, not a requester-facing list reused under a user's token. Cover the union of submitted-in-window or completed-in-window records so a recently completed old submission is not lost.
- Invoice numbers, exact amounts/INR, invoice/payment/supplier IDs, validated status, submitted/completed instants, supplier snapshot and actual ACTIVE/INACTIVE transitions. Supply all requested population records; engine rule eligibility is not a substitute for certified coverage.
- Explicit COMPLETE/INCOMPLETE/UNAVAILABLE metadata and covered-from times. COMPLETE empty is authoritative only after the entire certified population was queried. No history count/digest alone proves completeness.
- Exact original cutoff, supplier, facts receipt, configuration/acceptance hash bindings and current/retry exclusions. Payment history end is exclusive; transition end is inclusive. Reject ambiguous offsets/times and inconsistent reference echoes.
- Durable immutable population before page 0, with one replay reference/revision across all pages. All pages, totals, cursors, certified coverage and locally recomputed full-population digest must agree. Missing pages/capacity overflow cannot silently produce a partial completed assessment.
- Retention/restart replay at the original cutoff until collection succeeds. Do not combine changed snapshots after retries or answer a replay with today's history. HTTP 410 means original cutoff cannot be reproduced and requires intervention.

The retained population is the first coherent provider collection snapshot filtered by the specified cutoff rules; certification must establish the historical cutoff state, not pretend late commits were visible at acceptance. A certified immutable INCOMPLETE/UNAVAILABLE capture cannot be upgraded in place. A transient failure before capture can be retried; a permanent certified gap needs intervention even if the generic runtime status remains retryable. The provider contract, not a digest, must establish which records/coverage remain durably reconstructible.

Developer 2 computes/captures **prior risk history locally**, across requesters, using completed assessment timestamps in `[cutoff-window, cutoff)`. Original TRIGGERED factors—including weight-zero triggers—qualify; PREVIOUS_RISK_FLAGS itself is excluded. Count each prior submission once. D1 must not manufacture or replace this receipt. The stored local population is the first coherent collection snapshot's committed visibility, not a claim of acceptance-time commit visibility. Retries/takeovers reuse that persisted capture and then the complete frozen input.

The three opt-in production clients now exist, so no new Developer 2 adapter code is needed merely to point at compatible providers. Runtime flags, origins, credentials, certificates, versioned provider implementations and certified retention guarantees are still required. Incompatible Developer 1 APIs need a jointly agreed provider implementation/adapter; their existence is not assumed here.

## Developer 3: pinned approval contract

Read completed Risk summary/factors and the assessment's configuration snapshot. Retain assessment ID, logical submission association, configuration version/revision, evaluator identity and both distinct hash identities. Resolve `/internal/v1/configuration/versions/{configurationVersionId}` using CONFIGURATION_VERSION_READ. Verify its version ID, **versionRowVersion** and publication `contentHash` against the engine projection's `contentHash`. Risk's `configurationHash` is a separate local canonical engine-snapshot hash; do not compare it directly to publication contentHash.

The risk configuration endpoint returns the engine projection, which includes bands/boundaries but not the complete SLA/approval policy. The Configuration pinned endpoint supplies the complete immutable published V2 content. **Never substitute `/active`**, even if the current version has changed or looks equivalent. Historical V1 reads retain their historical meaning; new submissions reject unsupported legacy semantics instead of silently attaching V2 meanings to old hashes.

`configuration-content-v2` hash-covers `approval-policy-v1` and the SLA entries. Developer 3 must implement the following exact semantics:

| Decision | Frozen meaning |
|---|---|
| Routing | LOW→L1, MEDIUM→L2, HIGH→L3; final authority MAX(amount tier, risk level) |
| Authorization | Explicit human approval at every required level; no automatic approval/payment processing |
| Deadline start | Approval task enters PENDING |
| Viewing/ordinary reassignment | Retain existing deadline; viewing does not reset it |
| Formal escalation/new backup task | New configured timer from the **destination level's** duration |
| L1/L2 primary expiry | Escalate to L2/L3 respectively |
| L3 primary expiry | One configured eligible same-level L3 backup with same-level authority, otherwise manual review |
| Backup scope/expiry | At most one same-level backup over the approval chain; its expiry leads to manual review, not another backup |
| Audit | Developer 3 records escalation, reassignment, expiry and manual review |

The immutable policy selects these versioned meanings and duration settings; actual eligible backup assignment is Developer 3 runtime data. Do not invent a backup user from a policy code. Unknown versions/codes or hash mismatches must stop routing. Publication validates supported semantics but does not prove genuine team workflow/audit behavior or upstream evidence certification.

## Durable coordination proposal: polling and same-identity retries

This is the **proposed cross-team integration contract**, pending D1/D3 agreement. D2 has submission/status/read APIs, not a durable remote coordinator, callback, outbox, Kafka consumer or scheduler. No agreed requirement currently mandates callback delivery; polling can satisfy handoff only if its durable owners are implemented by the teams.

1. Developer 1 durably records the validated submission, namespaced key and exact references before first POST. Store retry/checkpoint state so a caller restart does not mint a new identity.
2. POST with a service token limited to the submitting namespace. Persist returned assessment ID/status. On uncertainty, GET the same identity's status; GET is read-only and cannot resume work.
3. If accepted/retryable or the lease has expired, drive recovery by bounded backoff **same-identity POST**. Active leases reject duplicate work safely. Completed retries read stored results without refetching/re-evaluating. Dependency failure before acceptance may return 503; preserve the same identity on retry.
4. BLOCKED, reference conflicts or irreproducible cutoff enter a durable intervention queue. There is no public unblock/reset endpoint; automatic retries must not fabricate new facts/cutoffs or delete acceptance data. Transient failures must not move payment to a “low risk/approved” state.
5. Developer 1/3 agree who owns result polling and handoff. Developer 1 updates authoritative payment risk status/summary using its own transaction; Developer 3 creates/deduplicates the approval task using assessment identity plus pinned configuration. Each records its own durable applied-result checkpoint and idempotent transition before acknowledging local completion.
6. D3 retries failed reads/routing using the same stored assessment/version. Handle at-least-once polling and crash-after-apply-before-checkpoint with recipient deduplication/unique workflow keys. No distributed exactly-once guarantee is implied.

If the teams later require push notification or audit delivery from D2, separately design a transactional outbox inserted with completion, delivery retries and recipient deduplication. None is implemented; do not treat the current `COMPLETED` flag as proof a callback/audit/payment transition occurred.

## Minimum joint sign-off

Agree stable submission/resubmission identity, namespace ownership, PASS reference semantics, original-cutoff retention/completeness, provider origins/protocol support, receiver audiences/grants/key rotation/TLS, the durable retry/polling owner and D3's pinned-policy/audit implementation. Exercise genuine MFA-issued manager tokens and genuine service issuer/provider credentials end to end. Contract-test providers and ephemeral keys are not that sign-off.
