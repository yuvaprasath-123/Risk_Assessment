# Developer 2 working plan — current backend handoff

Updated 16 October 2026 against implementation baseline `3031d878975c039e3d390e73afd4953e489a3829`. This current plan supersedes the earlier M2a/M2b-only progress narrative. Historical verification remains scoped to its checkpoint; repository SQL proposals retain original values and review stops. The Scope PDF is the business source, the blueprint supplied initial ownership, and subsequent user instructions approve the demonstration extensions and revised ownership. Q&A is excluded.

## Ownership and boundaries

| Owner | Responsibility and tables |
|---|---|
| Developer 2 — business-configuration-service, 8086 | Versioned risk/approval settings, readiness, publication, singleton activation and immutable pinned snapshots. D2_CONFIGURATION: CONFIGURATION_VERSIONS, RISK_RULE_CONFIGS, RISK_BAND_CONFIGS, APPROVAL_THRESHOLD_CONFIGS, SLA_CONFIGS, CURRENT_CONFIGURATION |
| Developer 2 — risk-assessment-service, 8087 | Seven-rule engine, accepted identity/configuration, leases, durable prior-history capture, complete frozen evidence, atomic factors/results, secured reads/submission and opt-in HTTP clients. D2_RISK: RISK_ASSESSMENTS, RISK_CONFIGURATION_SNAPSHOTS, RISK_INPUT_EVIDENCE, RISK_ASSESSMENT_FACTORS |
| Developer 1 | Payment lifecycle, payment validation, supplier/invoice/payment facts and authoritative upstream history. Validation tables/implementation are Developer 1-owned; D2 depends on immutable PASS/input contracts |
| Developer 3 | Approval tasks/authority/backup/SLA workflow, payment authorization and authoritative business audit; consumes pinned configuration and completed risk results |

D2_VALIDATION was already provisioned and is preserved; no account drop or ownership-driven DDL is authorized. External IDs are associations, never cross-service database foreign keys. UI work remains a separate frontend integration task; existing teammate UI is preserved and no D2 frontend integration is claimed.

## Environment and database progress

Java 21 (previously verified 21.0.12.1, `C:\jdk21`), Spring Boot 4.1.1 and installed Maven 3.9.16 (`C:\apache-maven-3.9.16`). Maven Wrapper remains unresolved. Oracle AI Database 26ai Free 23.26.2.0.0 uses localhost:1521/FREEPDB1 and USERS. D2_CONFIGURATION and the preserved D2_VALIDATION account were provisioned with 100M quotas; D2_RISK was originally 100M and later increased by the user to **300M** after ORA-01536. Historical setup SQL is not rewritten to imply the larger original quota.

All three owners have CREATE SESSION/TABLE/SEQUENCE/PROCEDURE and successful SQL Developer connections. Configuration's six tables plus IX_CFG_CURRENT_VERSION and risk's four reviewed tables were deployed by the user. JDBC and isolated persistence/HTTP-to-Oracle suites subsequently passed; earlier “connectivity/write behavior unverified” notes are historical, not current blanket statements. Automatic DDL, migrations and initialization remain disabled; credentials/signing material stay external. No SYSTEM application connection, seeding or runtime account creation is implemented.

## Milestones and meaningful completion criteria

| Milestone | Status and verification boundary |
|---|---|
| Database design/setup | Reviewed owner/schema SQL with deliberate stops; deployed metadata and isolated JDBC suites verified by user. No schema changes in this handoff |
| Configuration M2a–M2g | Manager list/create/details/draft rule/band/tier/SLA editing and coherent readiness implemented; strict JWT, exact values, identity preservation, stale edits and transaction regressions; user-run populated Oracle checks |
| Typed risk contracts and engine | Seven typed risk-policy-v1 contracts and pure deterministic scoring; exact arithmetic, current/retry exclusions, windows, insufficient history and technical failures tested |
| Risk persistence | Atomic acceptance/snapshot, fenced recoverable work, immutable evidence/capture and seven-factor finalization implemented; mock-transaction regressions and user-run Oracle concurrency/rollback/cleanup suites |
| Secured reads/submission | Manager reads, separate RS256 internal reads, reference-only submit and read-only status implemented. Caller-driven same-identity POST recovery; no scheduler/callback/outbox |
| Publication/activation | Real concrete readiness, configuration-content-v2 hash-covered approval-policy-v1, immutable publication and pointer concurrency implemented. Real-validator offline success and 3 user-run Oracle real-publication tests passed; controlled fixtures are no longer the only publication evidence |
| Production adapters | Configuration, Facts and upstream Evidence HTTP clients implemented, bounded/authenticated/strict and opt-in. Local prior risk remains D2-owned and durably replayable. Configured-client suite passed with contract-test upstreams; actual production endpoints/credentials/retention remain external |
| Integration/evaluation preparation | Current OpenAPI, validated examples, blank-secret Insomnia collection, consolidated setup/recovery and D1/D3 contract package prepared in [developer2-handoff](developer2-handoff/README.md). Genuine team workflow and client import remain pending |

Latest retained implementation evidence: **2,117 offline baseline plus 173 affected passes including 10 new safety cases; overlapping counts are not added**. The user-run configured-client Oracle suite passed **7 tests, zero failures/errors/skips**, exercised real readiness-approved config and all three production HTTP clients, terminated all worker groups and left zero fixture parents in both schemas. This used contract-test providers and an ephemeral issuer, not genuine D1/D3 integration. See [current checklist](developer2-handoff/completion-checklist.md) and [artifact validation report](developer2-handoff/validation/validation-report.json) for exact scope. No runtime regressions/live suites were rerun solely for documentation.

## Confirmed requirements and approved demonstration decisions

The original five explicit scope factors are structuring, frequency, unusual amount, recent reactivation and previous flags. NEW_SUPPLIER and NEAR_DUPLICATE_INVOICE are later user-approved demonstration additions; do not retroactively label them original scope requirements. FINANCE_MANAGER-only public access is an approved prototype policy, not a scope-document role mandate.

INR/exact money; typed configurable rule weights/windows/counts; each trigger contributes once; retain raw total and cap final score. Approved demonstration settings: weights 35/15/20/15/10/15/25, cap100, LOW0–29/MEDIUM30–60/HIGH61–100; validated PENDING_APPROVAL/APPROVED/PROCESSING/COMPLETE activity; zero baseline/under-five completed observations => insufficient history with zero contribution; EXACT invoice comparison; structuring L2/L3 boundaries; previous flags match the six other original factors. No values are seeded/activated automatically. Example monetary tier amounts are synthetic, not approved business thresholds.

Approval-policy-v1 fixes LOW/L1, MEDIUM/L2, HIGH/L3 and MAX(amount tier,risk level). All levels require human authorization. PENDING starts the configured timer; viewing and ordinary reassignment retain deadlines; formal escalation/new backup task uses the destination-level timer. L1→L2→L3; at most one eligible same-level L3 backup across the chain, then manual review. No automatic approval/payment. D3 audits escalation/reassignment/expiry/manual review. SLA demonstration default 86400 seconds remains configurable and unseeded. These semantics are explicitly versioned/hash-covered; unknown or unresolved policy data still blocks publication.

## Integration contracts: implemented versus proposed

Implemented D2 APIs, exact representations and all routes are in [OpenAPI](developer2-handoff/README.md). D2's Facts/evidence provider protocols and opt-in clients are implemented contracts; the provider URLs are **not claimed to exist on Developer 1**. Requester-facing list APIs are not assumed to provide supplier-wide service access.

Developer 1 must confirm stable namespaced identity/resubmission lineage, authoritative immutable PASS reference, original-cutoff facts and supplier-wide history/transition completeness/retention, and provision compatible trusted providers. Developer 3 consumes completed risk and its full pinned Configuration version/hash, never current active replacement, and implements task routing/timers/backup/audit. Separate service-principal issuer/audience/grants/TLS/rotation must be provisioned; user JWTs do not authorize internal submission.

Durable polling/retry is a proposed D1/D3 coordination contract: store identity/reference and local checkpoints, recover uncertainty via read-only GET status, progress eligible work via same-identity POST, deduplicate payment/approval transitions and stop BLOCKED for intervention. D2 does not implement cross-service exactly-once delivery, callback, outbox, Kafka consumer or coordinator scheduler. An agreed future push-delivery responsibility would require a separate transactional outbox/delivery design.

## Remaining decision/dependency register

| Item | Classification / next action |
|---|---|
| Production business thresholds/windows/weights | Demonstration values are configurable examples, not general production approval. Team validates actual deployment values |
| Stable retries versus genuine linked resubmission | D1 agreement pending; payment ID and logical key remain separate |
| Actual provider availability/certification/retention | D1/provider responsibility; test receipts do not establish production guarantees |
| Issuer/audiences/namespace ownership/key rotation/TLS | D1/D3/operator provisioning and genuine interoperability pending |
| D3 backup eligibility, task/audit execution | Frozen semantics complete; real workflow data/behavior belongs to D3 |
| Durable poll/retry/result handoff owner | Proposed contract needs adoption and durable caller/recipient implementation |
| UI and genuine Insomnia import/send | Separate integration verification; no frontend/runtime changes made |

Publication is no longer universally policy-blocked: concrete supported configurations pass real readiness. Upstream availability is not a publication prerequisite, but missing/unavailable/incomplete/uncertified required evidence is still a runtime technical failure, never zero risk. The smallest next activity is genuine cross-team contract/provisioning verification, not rewriting the tested engine or adding speculative callbacks.
