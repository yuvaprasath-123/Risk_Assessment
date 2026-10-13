# Developer 2 database design for review

Status: proposed design, 12 October 2026. This document contains no executable SQL. No tables, application code, or database objects are created by this task.

## Authority and verified environment

Business requirements come from [Scope Group 06.pdf](<C:/Users/CHANDANA K P/Downloads/Scope Group 06.pdf>), especially sections 1.5, 2, 3, 4, and 5. Ownership and the original table families come from [Supplier Payment Microservice Blueprint](<C:/Users/CHANDANA K P/Downloads/Supplier_Payment_Microservice_Blueprint.docx>), Developer 2 sections 4-6. Q&A material is excluded. The [working plan](../developer2-plan.md) records open decisions and the environment.

Oracle AI Database 26ai Free reports version 23.26.2.0.0, at localhost:1521, service/container FREEPDB1. The user verified that D2_CONFIGURATION, D2_VALIDATION, and D2_RISK exist, each with 100 MB on USERS and CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE. All three SQL Developer connections passed. Application/JDBC connectivity and persistence are unverified.

## Readable design summary

| Schema | Tables proposed | Main relationship |
|---|---|---|
| D2_CONFIGURATION | CONFIGURATION_VERSIONS, RISK_RULE_CONFIGS, RISK_BAND_CONFIGS, APPROVAL_THRESHOLD_CONFIGS, SLA_CONFIGS, CURRENT_CONFIGURATION | A version owns complete configuration; a single current-version pointer selects the active published version. The pointer table is an additional design proposal. |
| D2_VALIDATION | VALIDATION_RESULTS, VALIDATION_CHECKS | One logical validation result per agreed submission identity, with individually explained checks. |
| D2_RISK | RISK_ASSESSMENTS, RISK_ASSESSMENT_FACTORS, RISK_CONFIGURATION_SNAPSHOTS, RISK_INPUT_EVIDENCE | One logical assessment per agreed submission identity, with a private configuration copy, frozen payment/history evidence, and explained rule contributions. Input evidence is an additional table required by this design request. |

Developer 1 retains supplier/invoice masters and payment lifecycle/status history. Developer 3 retains approval routes/tasks/decisions, escalation/backup mappings, processing, and business audit. Developer 2 stores external IDs and decision evidence, not authoritative copies of those services' lifecycle records. All foreign keys below stay within one service schema. References to payment, supplier, invoice, user, validation, configuration version, and audit/event records owned elsewhere have **no database foreign keys**.

Internal relationships: configuration version -> rules/bands/thresholds/SLAs; validation result -> checks; assessment -> factors/snapshot/evidence. The risk configuration version is an external reference even though D2_CONFIGURATION and D2_RISK share one database. The services exchange data through agreed APIs/events.

## Common conventions and proposed assumptions

These conventions are proposed, not approved requirements. Business values remain versioned configuration, not hard-coded database constants.

- Physical identifiers are uppercase and unquoted; all business tables reside in their service's provisioned schema on USERS. No cross-schema grants or foreign keys are proposed.
- Internal and existing external numeric IDs use NUMBER(19,0). Positive IDs must fit Java Long (maximum 9223372036854775807). Primary IDs are generated; choosing identity versus explicit sequences is unresolved. Where noted below, external submission identity uses VARCHAR2(128 CHAR) provisionally because no upstream submission-attempt contract exists yet.
- Payment, invoice, and single-payment approval threshold amounts use **NUMBER(15,2)**, matching existing PaymentRequest and Invoice entity mappings (both precision 15, scale 2). Historical sums may use NUMBER(19,2) in structured evidence so an aggregate does not overflow a single-payment amount. No floating-point money. Ratios use exact decimal values in evidence/configuration. Currency must be agreed; optional CHAR(3) evidence is not an approved multicurrency feature.
- Proposed integer scores/weights use NUMBER(10,0), nonnegative. Existing payment riskScore is Integer, so values sent to Developer 1 must fit that range. Fractional scoring would require a contract change. Capping at 100 remains an open policy decision.
- Times use TIMESTAMP(6) WITH TIME ZONE, written in UTC, with a fixed as-of instant for each assessment. Invoice calendar dates use DATE with no time component. Existing services use LocalDateTime for lifecycle timestamps, so the upstream time-zone conversion must be agreed rather than guessed.
- Small flags use NUMBER(1,0), restricted to 0/1. All VARCHAR2 lengths below use CHAR semantics; empty strings are treated as null by Oracle, so required strings must also be nonblank at the service boundary.
- Structured configuration/evidence uses CLOB containing valid JSON. Apply JSON-validity constraints; JSON schema, required properties, numeric precision, canonical serialization, and size limits are enforced by the service. This is a proposed portable storage choice, not a claim that native Oracle JSON is unavailable. Do not index full CLOB content by default.
- Each table has a primary-key index. Each stated unique key has one supporting unique index; do not add an identical ordinary index. Additional indexes below serve actual reads, joins, or work recovery. Foreign-key indexes are stated explicitly.
- PK = primary key; FK = local foreign key; UK = unique key; NN = not null. All columns are NN unless explicitly marked nullable. Child rows are retained with their parent; do not use cascading deletion for published configuration or final decisions.
- Actor IDs refer to Developer 1 users. Service actions instead store a service actor code; at least one actor representation is required where both columns exist. Never store passwords, access tokens, SYSTEM credentials, or real financial data in evidence.

## D2_CONFIGURATION tables

### CONFIGURATION_VERSIONS

Purpose: a coherent version containing rules, bands, amount tiers, and SLAs. Separating publication from the current pointer allows a published historical version to remain intact after another version becomes active.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| VERSION_ID | NUMBER(19,0) | Generated PK. |
| VERSION_NUMBER | NUMBER(10,0) | Positive UK; human-readable revision, allocated safely under concurrency. |
| VERSION_LABEL | VARCHAR2(100 CHAR) | Nonblank label. |
| PUBLICATION_STATE | VARCHAR2(16 CHAR) | DRAFT or PUBLISHED. Published means content frozen; current activation is separate. |
| DESCRIPTION | VARCHAR2(1000 CHAR) | Nullable change description. |
| IS_DEMONSTRATION | NUMBER(1,0) | 0/1; marks provisional demonstration policy explicitly. |
| SCORE_POLICY_CODE | VARCHAR2(16 CHAR) | Proposed SUM or CAP_AT_MAX; approved score policy must be versioned, not evaluator hard-coding. |
| MAX_FINAL_SCORE | NUMBER(10,0) | Nullable for SUM; positive and required for CAP_AT_MAX. No value of 100 is assumed. |
| CREATED_AT | TIMESTAMP(6) WITH TIME ZONE | Creation instant. |
| CREATED_BY_USER_ID | NUMBER(19,0) | External author ID. |
| PUBLISHED_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable until publication. |
| PUBLISHED_BY_USER_ID | NUMBER(19,0) | Nullable until publication; external publisher ID. |
| CONTENT_HASH | CHAR(64) | Nullable until publication; canonical full-version SHA-256, hexadecimal. |
| ROW_VERSION | NUMBER(19,0) | Nonnegative optimistic-concurrency value for draft changes. |

Keys/constraints: PK VERSION_ID; UK VERSION_NUMBER. DRAFT has no publication metadata/hash; PUBLISHED requires all three publication fields and publication at or after creation. Score policy and optional maximum must be consistent. Validate the complete content atomically before publishing. No FK to users. Indexes: PK and version-number UK only initially; a state index is optional if measured version-list filtering needs it.

### RISK_RULE_CONFIGS

Purpose: enabled state, weight, and versioned parameter definitions for each agreed rule.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| RULE_CONFIG_ID | NUMBER(19,0) | Generated PK. |
| VERSION_ID | NUMBER(19,0) | FK to CONFIGURATION_VERSIONS. |
| RULE_CODE | VARCHAR2(64 CHAR) | Stable rule identifier, not a display label. |
| DISPLAY_NAME | VARCHAR2(120 CHAR) | Nonblank explanation label. |
| ENABLED | NUMBER(1,0) | 0/1. |
| WEIGHT | NUMBER(10,0) | Nonnegative integer contribution when triggered. |
| PARAMETER_SCHEMA_VERSION | VARCHAR2(32 CHAR) | Version of the parameter contract for this rule. |
| PARAMETERS_JSON | CLOB | Valid JSON; windows, count thresholds, baseline definitions, ratios, eligible statuses, etc. Numeric semantics validated per rule. |
| EVALUATION_ORDER | NUMBER(5,0) | Positive presentation/evaluation order, not score dependence. |

Keys/constraints: PK RULE_CONFIG_ID; FK VERSION_ID; UK (VERSION_ID, RULE_CODE); proposed UK (VERSION_ID, EVALUATION_ORDER) for stable ordering. Code normalization and supported rule/schema combinations are publication checks. No per-row hard-coded business window/default. Indexes: PK and the two UKs; their version-leading columns cover the local FK and version reads.

### RISK_BAND_CONFIGS

Purpose: map integer score ranges to Low/Medium/High and the risk-side approval level.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| BAND_CONFIG_ID | NUMBER(19,0) | Generated PK. |
| VERSION_ID | NUMBER(19,0) | FK to CONFIGURATION_VERSIONS. |
| BAND_CODE | VARCHAR2(8 CHAR) | LOW, MEDIUM, HIGH. |
| MIN_SCORE_INCLUSIVE | NUMBER(10,0) | Nonnegative lower bound. |
| MAX_SCORE_INCLUSIVE | NUMBER(10,0) | Nullable for open upper range; otherwise >= minimum. |
| APPROVAL_LEVEL | NUMBER(1,0) | 1, 2, or 3, representing risk-side routing level. |

Keys/constraints: PK BAND_CONFIG_ID; FK VERSION_ID; UK (VERSION_ID, BAND_CODE). Publication must require exactly three bands with no gaps/overlaps and increasing approval levels consistent with the approved MAX matrix. An ordinary row check cannot enforce whole-version coverage. Integer inclusive bounds avoid ambiguous boundary comparisons; values are not seeded here. Indexes: PK and version/band UK, covering FK access. No score-range index for a three-row version.

### APPROVAL_THRESHOLD_CONFIGS

Purpose: amount tiers consumed by Developer 3; this table does not store approval assignments or decisions.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| THRESHOLD_CONFIG_ID | NUMBER(19,0) | Generated PK. |
| VERSION_ID | NUMBER(19,0) | FK to CONFIGURATION_VERSIONS. |
| APPROVAL_LEVEL | NUMBER(1,0) | 1/2/3. |
| MIN_AMOUNT_INCLUSIVE | NUMBER(15,2) | Nonnegative lower bound. |
| MAX_AMOUNT_EXCLUSIVE | NUMBER(15,2) | Nullable for highest open-ended tier; otherwise > minimum. |

Keys/constraints: PK THRESHOLD_CONFIG_ID; FK VERSION_ID; UK (VERSION_ID, APPROVAL_LEVEL). Publish only complete, ordered, contiguous, non-overlapping tiers in an agreed currency/unit. The inclusive lower/exclusive upper convention is proposed and must match Developer 3. No L1 automatic-approval flag is assumed. Indexes: PK and version/level UK, covering FK/version reads.

### SLA_CONFIGS

Purpose: versioned pending-approval durations and escalation policy supplied to Developer 3, which owns timers, approvers, and escalation records.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| SLA_CONFIG_ID | NUMBER(19,0) | Generated PK. |
| VERSION_ID | NUMBER(19,0) | FK to CONFIGURATION_VERSIONS. |
| APPROVAL_LEVEL | NUMBER(1,0) | 1/2/3. |
| DURATION_SECONDS | NUMBER(12,0) | Positive duration; maximum acceptable duration is a publication validation. |
| START_EVENT_CODE | VARCHAR2(32 CHAR) | Proposed PENDING_APPROVAL_ENTERED; final start event requires agreement. |
| ESCALATION_POLICY_CODE | VARCHAR2(32 CHAR) | Agreed next-level/backup policy; exact allowed codes unresolved. |
| TERMINAL_LEVEL_POLICY_CODE | VARCHAR2(32 CHAR) | Nullable except where terminal L3 handling requires it; semantics unresolved. |

Keys/constraints: PK SLA_CONFIG_ID; FK VERSION_ID; UK (VERSION_ID, APPROVAL_LEVEL). Published versions require the agreed SLA coverage and valid escalation/terminal combinations. No backup user IDs or mapping tables belong here. Indexes: PK and version/level UK, covering FK access.

### CURRENT_CONFIGURATION (additional proposal)

Purpose: one atomic current-version pointer; avoids multiple ACTIVE version rows during concurrent activation. Publication is immutable; activation changes this pointer, not historical version contents.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| POINTER_ID | NUMBER(1,0) | PK restricted to 1, allowing at most one pointer row. |
| VERSION_ID | NUMBER(19,0) | FK to CONFIGURATION_VERSIONS. |
| ACTIVATED_AT | TIMESTAMP(6) WITH TIME ZONE | Activation instant. |
| ACTIVATED_BY_USER_ID | NUMBER(19,0) | External actor ID. |
| ROW_VERSION | NUMBER(19,0) | Nonnegative concurrency token. |

Keys/constraints: PK POINTER_ID with value 1; FK VERSION_ID. No pointer is allowed before a valid first activation; at least one row after initial activation is a service invariant. A FK establishes version existence, not PUBLISHED status: activation must verify that state within its transaction. Indexes: PK and VERSION_ID for the FK. Activation history remains Developer 3 business audit; this pointer is not its substitute. Future scheduling is not assumed.

## D2_VALIDATION tables

### VALIDATION_RESULTS

Purpose: outcome and recovery state of one logical submission validation. Never use this state as the authoritative payment lifecycle status.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| VALIDATION_ID | NUMBER(19,0) | Generated PK. |
| PAYMENT_ID | NUMBER(19,0) | External Developer 1 payment ID. |
| SUBMISSION_KEY | VARCHAR2(128 CHAR) | External stable logical submission token; proposed contract, not a retry-generated token. |
| INVOICE_ID | NUMBER(19,0) | Nullable if missing/invalid input must itself be recorded. |
| SUPPLIER_ID | NUMBER(19,0) | Nullable if missing/invalid input. |
| REQUESTER_ID | NUMBER(19,0) | Nullable until identity is validated; external user ID. |
| REQUEST_HASH | CHAR(64) | Canonical immutable request identity/content hash. |
| REQUEST_EVIDENCE_JSON | CLOB | Valid JSON including raw missing fields, source IDs/revisions, and amount/date observations; no secrets. |
| WORK_STATE | VARCHAR2(24 CHAR) | IN_PROGRESS, RETRYABLE_FAILURE, COMPLETED. |
| VALIDATION_OUTCOME | VARCHAR2(8 CHAR) | Nullable until completion; PASS or FAIL for business validation only. |
| ATTEMPT_COUNT | NUMBER(10,0) | Positive technical-attempt count for this logical validation. |
| CREATED_AT | TIMESTAMP(6) WITH TIME ZONE | First received time. |
| EVIDENCE_AS_OF | TIMESTAMP(6) WITH TIME ZONE | Nullable until coherent input/check evidence is ready. |
| COMPLETED_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable until completion. |
| LEASE_TOKEN | VARCHAR2(64 CHAR) | Nullable worker claim token. |
| LEASE_EXPIRES_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable expiry, paired with token while claimed. |
| LAST_ERROR_CODE | VARCHAR2(64 CHAR) | Nullable technical error code. |
| LAST_ERROR_MESSAGE | VARCHAR2(1000 CHAR) | Nullable sanitized diagnostic; no credentials. |
| CORRELATION_ID | VARCHAR2(128 CHAR) | Delivery/workflow correlation; separate from submission identity. |
| ROW_VERSION | NUMBER(19,0) | Nonnegative concurrency token. |

Keys/constraints: PK VALIDATION_ID; proposed UK (PAYMENT_ID, SUBMISSION_KEY). External IDs have no FKs. COMPLETED requires outcome, completed time and frozen evidence time; unfinished states have no final outcome/completed time. PASS requires the agreed mandatory checks to pass, enforced transactionally. Nullable external fields let malformed requests be explained without fake zero IDs. Completion time >= creation time; lease token/time both present or absent. Request hash conflict under the same identity is an error, not an overwrite. Indexes: PK, logical-identity UK, (WORK_STATE, LEASE_EXPIRES_AT) for recovery. Add (SUPPLIER_ID, CREATED_AT) only if an agreed validation-history API needs it; validation history is not authoritative payment history.

### VALIDATION_CHECKS

Purpose: individually explain why a submission passed or failed, including duplicate evidence. This is not risk scoring.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| CHECK_ID | NUMBER(19,0) | Generated PK. |
| VALIDATION_ID | NUMBER(19,0) | FK to VALIDATION_RESULTS. |
| CHECK_CODE | VARCHAR2(64 CHAR) | Stable mandatory-fields, supplier-status, duplicate, or agreed consistency code. |
| CHECK_ORDER | NUMBER(5,0) | Positive display order. |
| CHECK_OUTCOME | VARCHAR2(16 CHAR) | PASS, FAIL, ERROR, NOT_RUN. ERROR is technical, not a business rejection. |
| REASON_CODE | VARCHAR2(64 CHAR) | Nullable for PASS; required for other outcomes. |
| EXPLANATION | VARCHAR2(2000 CHAR) | Nonblank readable explanation. |
| EVIDENCE_JSON | CLOB | Nullable for NOT_RUN; otherwise JSON observations, exclusions and external candidate IDs. |
| EVALUATED_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable for NOT_RUN; otherwise check time. |

Keys/constraints: PK CHECK_ID; FK VALIDATION_ID; UK (VALIDATION_ID, CHECK_CODE). Outcome-dependent evidence/time/reason constraints as above. A final PASS cannot coexist with FAIL/ERROR/required NOT_RUN checks. Do not incrementally mix check evidence from different technical attempts: replace unfinished check sets transactionally before finalization, and never mutate a finalized set. Indexes: PK and parent/check UK covering the FK. No independent reason/outcome index without a reporting requirement.

## D2_RISK tables

### RISK_ASSESSMENTS

Purpose: logical single assessment and its final score/band. External configuration/validation references connect the service contracts without cross-schema foreign keys.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| ASSESSMENT_ID | NUMBER(19,0) | Generated PK. |
| PAYMENT_ID | NUMBER(19,0) | External Developer 1 ID. |
| SUBMISSION_KEY | VARCHAR2(128 CHAR) | Stable agreed submission identity, matching validation. |
| VALIDATION_ID | NUMBER(19,0) | External D2_VALIDATION ID; no cross-schema FK. |
| SUPPLIER_ID | NUMBER(19,0) | External Developer 1 ID. |
| INVOICE_ID | NUMBER(19,0) | External Developer 1 ID. |
| REQUESTER_ID | NUMBER(19,0) | External user ID for traceability and downstream self-approval controls. |
| PAYMENT_AMOUNT | NUMBER(15,2) | Positive evaluated amount, not payment owner storage. |
| CONFIGURATION_VERSION_ID | NUMBER(19,0) | Nullable until pinned external version; required once input collection begins. |
| EVALUATOR_VERSION | VARCHAR2(64 CHAR) | Version of rule implementation used to interpret configuration. |
| INPUT_HASH | CHAR(64) | Nullable until input freezes; canonical complete evidence hash. |
| INPUT_FROZEN_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable until full input snapshot is committed. |
| EVIDENCE_AS_OF | TIMESTAMP(6) WITH TIME ZONE | Stable history cutoff; pinned on first logical attempt. |
| WORK_STATE | VARCHAR2(24 CHAR) | IN_PROGRESS, RETRYABLE_FAILURE, COMPLETED. |
| TOTAL_RAW_SCORE | NUMBER(10,0) | Nullable until complete; sum before any approved cap/normalization. |
| FINAL_SCORE | NUMBER(10,0) | Nullable until complete; nonnegative Integer-compatible result. |
| RISK_BAND | VARCHAR2(8 CHAR) | Nullable until complete; LOW, MEDIUM, HIGH from frozen version. |
| ATTEMPT_COUNT | NUMBER(10,0) | Positive count of technical attempts, not business rescoring. |
| CREATED_AT | TIMESTAMP(6) WITH TIME ZONE | First attempt time. |
| COMPLETED_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable until completion; >= creation time. |
| LEASE_TOKEN | VARCHAR2(64 CHAR) | Nullable worker claim token. |
| LEASE_EXPIRES_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable expiry paired with token. |
| LAST_ERROR_CODE | VARCHAR2(64 CHAR) | Nullable technical failure code. |
| LAST_ERROR_MESSAGE | VARCHAR2(1000 CHAR) | Nullable sanitized diagnostic. |
| CORRELATION_ID | VARCHAR2(128 CHAR) | Workflow/delivery correlation. |
| ROW_VERSION | NUMBER(19,0) | Nonnegative concurrency token. |

Keys/constraints: PK ASSESSMENT_ID; proposed UK (PAYMENT_ID, SUBMISSION_KEY). No external FKs. Frozen time/hash must be both present or both absent; completed assessment requires frozen input, configuration ID, scores, band and completed time. Unfinished state must not expose a final score/band. Scores >= 0 and <= Integer maximum; final/raw relationship follows approved score policy, not an assumed cap. Exactly one snapshot and complete required evidence are service finalization checks. Indexes: PK; logical-identity UK; (SUPPLIER_ID, COMPLETED_AT) for prior supplier risk; (WORK_STATE, LEASE_EXPIRES_AT) for recovery. The UK already covers payment lookup; do not duplicate it with a PAYMENT_ID index.

### RISK_CONFIGURATION_SNAPSHOTS

Purpose: immutable, local copy of the complete configuration selected for an assessment. Historical evaluation must not depend on the configuration service's current version.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| SNAPSHOT_ID | NUMBER(19,0) | Generated PK. |
| ASSESSMENT_ID | NUMBER(19,0) | FK to RISK_ASSESSMENTS; UK for one snapshot per assessment. |
| SOURCE_VERSION_ID | NUMBER(19,0) | External configuration version ID; no FK to D2_CONFIGURATION. |
| SOURCE_VERSION_NUMBER | NUMBER(10,0) | Positive external version number. |
| SNAPSHOT_FORMAT_VERSION | VARCHAR2(32 CHAR) | Schema of serialized configuration. |
| CONFIGURATION_JSON | CLOB | Valid JSON with rules/enabled state/weights/parameters, score policy/cap, bands, approval thresholds, SLA settings, and demo label. |
| CONTENT_HASH | CHAR(64) | Canonical full-snapshot SHA-256. |
| CAPTURED_AT | TIMESTAMP(6) WITH TIME ZONE | Capture instant. |

Keys/constraints: PK SNAPSHOT_ID; FK ASSESSMENT_ID; UK ASSESSMENT_ID. Positive external version fields and JSON validity; version must match the parent configuration ID, checked during finalization. Snapshot hash must match canonical payload and source content before accepting it. Indexes: PK and ASSESSMENT_ID UK covering the FK. No hash UK: multiple assessments may legitimately use identical content. Approval thresholds/SLAs in this snapshot are context, not proof that Developer 3 used the same version; retain its actual route/audit references externally when agreed.

### RISK_INPUT_EVIDENCE

Purpose: frozen evaluated facts/history, including the full historical population used and the input of every enabled rule. Makes reproduction possible after upstream facts change.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| EVIDENCE_ID | NUMBER(19,0) | Generated PK. |
| ASSESSMENT_ID | NUMBER(19,0) | FK to RISK_ASSESSMENTS. |
| EVIDENCE_KEY | VARCHAR2(128 CHAR) | Stable key such as PAYMENT, SUPPLIER, INVOICE, or a rule-specific input group. |
| EVIDENCE_KIND | VARCHAR2(24 CHAR) | PAYMENT, SUPPLIER, INVOICE, HISTORY, RULE_INPUT. |
| SOURCE_SERVICE | VARCHAR2(64 CHAR) | Service supplying facts; aggregation evidence can identify risk-assessment-service. |
| SOURCE_RECORD_ID | NUMBER(19,0) | Nullable external single-record ID; history groups reference IDs inside their payload. |
| SOURCE_REVISION | VARCHAR2(128 CHAR) | Nullable provider revision/ETag if supported. |
| EVIDENCE_FORMAT_VERSION | VARCHAR2(32 CHAR) | Versioned envelope/JSON contract. |
| AS_OF_AT | TIMESTAMP(6) WITH TIME ZONE | Fixed evaluation cutoff matching assessment. |
| WINDOW_START_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable; history lower bound. |
| WINDOW_END_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable; history upper bound. |
| RECORD_COUNT | NUMBER(10,0) | Nonnegative observed records; 0 is legitimate and distinct from unavailable history. |
| COMPLETENESS | VARCHAR2(16 CHAR) | COMPLETE or INSUFFICIENT; unavailable fetches prevent freezing, not an empty population. |
| EVIDENCE_JSON | CLOB | Valid JSON with selected values, candidate IDs/status/timestamps/amounts, inclusion/exclusion decisions, aggregates, ratios and baseline calculations. |
| CONTENT_HASH | CHAR(64) | Canonical evidence SHA-256. |
| CAPTURED_AT | TIMESTAMP(6) WITH TIME ZONE | Fetch/assembly instant, distinct from source timestamp/as-of time. |

Keys/constraints: PK EVIDENCE_ID; FK ASSESSMENT_ID; UK (ASSESSMENT_ID, EVIDENCE_KEY); additional UK (ASSESSMENT_ID, EVIDENCE_ID) supports the composite factor FK below. Both window bounds present or absent, end >= start; record count >= 0; JSON valid. Cross-row equality of as-of times and input completeness rules are finalization checks. Indexes: PK and both UKs (version-leading parent access covers FK). No full-payload or external-ID indexes unless a concrete query contract needs them.

History payload must specify the window endpoint convention, accepted payment statuses, current-request exclusion/inclusion, currency/unit, pagination completion, and records used in each aggregate. IDs or aggregates alone are not enough if source records can change. If full populations exceed a single evidence payload, chunk keys and a completeness manifest can extend this table; the canonical manifest/hash must cover every chunk before freezing. Chunking is not assumed necessary for the synthetic prototype.

### RISK_ASSESSMENT_FACTORS

Purpose: explain every rule evaluation and contribution. Although the blueprint calls these contributing factors, retaining non-triggered and disabled-rule outcomes makes skipped versus false results explicit.

| Column | Oracle type | Meaning / constraints |
|---|---|---|
| FACTOR_ID | NUMBER(19,0) | Generated PK. |
| ASSESSMENT_ID | NUMBER(19,0) | FK to RISK_ASSESSMENTS. |
| RULE_CODE | VARCHAR2(64 CHAR) | Code from the frozen configuration; external rule ID has no FK. |
| RULE_CONFIG_SOURCE_ID | NUMBER(19,0) | Nullable external configuration row ID for traceability. |
| FACTOR_ORDER | NUMBER(5,0) | Positive order matching the snapshot. |
| EVALUATION_OUTCOME | VARCHAR2(16 CHAR) | TRIGGERED, NOT_TRIGGERED, DISABLED, INSUFFICIENT. Technical errors prevent completed assessment rather than masquerading as NOT_TRIGGERED. |
| CONFIGURED_WEIGHT | NUMBER(10,0) | Nonnegative snapshot weight. |
| SCORE_CONTRIBUTION | NUMBER(10,0) | Nonnegative contribution; TRIGGERED uses weight, other outcomes use zero under the proposed binary-rule model. |
| PRIMARY_EVIDENCE_ID | NUMBER(19,0) | Nullable for DISABLED; otherwise points to this assessment's rule input evidence group. |
| REASON_CODE | VARCHAR2(64 CHAR) | Stable rule result code. |
| EXPLANATION | VARCHAR2(2000 CHAR) | Nonblank readable reason. |
| EVALUATED_AT | TIMESTAMP(6) WITH TIME ZONE | Evaluation instant. |

Keys/constraints: PK FACTOR_ID; FK ASSESSMENT_ID; UK (ASSESSMENT_ID, RULE_CODE). Composite FK (ASSESSMENT_ID, PRIMARY_EVIDENCE_ID) -> RISK_INPUT_EVIDENCE (ASSESSMENT_ID, EVIDENCE_ID) prevents linking another assessment's evidence. Non-disabled rows require primary evidence. Each rule group may contain multiple source facts, with common PAYMENT/SUPPLIER/INVOICE context from the same assessment. Weight/contribution and rule-list membership must match snapshot. Final TOTAL_RAW_SCORE equals sum of contributions; this cross-row invariant is checked in the completion transaction. Handling INSUFFICIENT requires approved policy; recording zero does not alone authorize completing as low risk. Indexes: PK, assessment/rule UK, and (ASSESSMENT_ID, PRIMARY_EVIDENCE_ID) for composite FK joins. No isolated triggered-flag index initially.

## Configuration immutability and publication

1. Only DRAFT versions can change. Child rows must be changed with version-level concurrency control to avoid publishing while another transaction edits them.
2. Publication validates rule parameters, complete band/amount-tier coverage, SLA consistency and supported evaluator contracts, then computes a canonical content hash and freezes the complete version in one transaction.
3. Activation references a PUBLISHED version through CURRENT_CONFIGURATION. Compare-and-set/row locking prevents lost concurrent activations. Historical versions and their children are not relabeled or overwritten to activate a newer version.
4. New policy means a new draft copied from an existing version. Existing assessments continue to use their pinned snapshot. A retry cannot replace its version with today's active configuration.
5. Database row checks and FKs cannot enforce all parent-state and cross-row rules. Proposed separate runtime identities and a reviewed write boundary must protect published content. If applications log in as owners, they retain broad ownership rights and the database cannot claim privilege-enforced immutability. Decide whether restricted packages, scoped grants, or additional database guards are required before DDL; no such code is supplied here.
6. Publication/activation actor and state changes are sent to Developer 3 audit through its contract. Config tables are not a substitute for append-only business audit.

## Retry handling and single assessment semantics

Proposed logical identity is (PAYMENT_ID, SUBMISSION_KEY), subject to agreement with Developer 1. HTTP retry IDs, event IDs, and correlation IDs are transport metadata; they must not create a new assessment for the same logical submission. A unique key alone does not make completion or downstream delivery exactly-once.

- Completed duplicate delivery returns the existing final result. Never recalculate a completed payment just because configuration or history changed.
- An in-progress duplicate obtains the existing ID/status; it does not launch an independent evaluation. A lease plus optimistic concurrency controls recovery. On takeover, a new lease token prevents the expired worker from finalizing stale work.
- Pin validation eligibility, as-of time, evaluator version, and configuration version before evaluation. Collect authoritative inputs at that cutoff and freeze all evidence/snapshot together. Once frozen, a technical retry uses the same facts. If evidence cannot be collected consistently, retain RETRYABLE_FAILURE rather than fabricate missing history or accept the payment.
- A failed collection before freeze may retry collection at the same cutoff/version. If upstream cannot serve a consistent historical view, resolution is an explicit contract decision; it is not permission to silently score against current data.
- Write factors, final score/band and completion atomically, checking their totals and snapshot linkage. A crash must not expose a final result without complete evidence. Failed unfinished factors may be replaced transactionally; finalized factors/evidence are immutable.
- Business validation FAIL is terminal for that submission. A provider outage is technical failure, not a payment rejection. Developer 1 alone updates payment lifecycle status using the agreed result callback/event.
- Downstream result and audit delivery must be deduplicated and recoverable. The transport is unresolved: if events are selected, a service-owned transactional outbox will need an additional design review; if HTTP is selected, durable delivery/reconciliation must be specified. This document does not claim delivery durability from the tables above alone.

## Historical reproducibility

A historical assessment can be reconstructed from its frozen configuration, evaluator version, fixed as-of time, full evaluated request/supplier/invoice facts, selected prior payment/risk observations, and every rule's inputs/outcome/contribution. Persist observed current supplier status and the actual INACTIVE-to-ACTIVE transitions used, not just today's supplier ID. Preserve aggregate inputs and inclusion/exclusion rules, including a current-request exclusion where applicable.

Canonical JSON serialization must define sorted object keys, array ordering, decimal strings/precision, null representation, UTC timestamp formatting and format version before hashes are computed. Hashes detect payload inconsistency; they are not signatures or independently tamper-proof audit. Reproduction requires retaining or reproducing the evaluator implementation as well as its version label.

To verify later, replay an assessment after changing active configuration, supplier status and source payment history; its stored raw score, contributions, band and explanation must remain reproducible. Developer 3 retains authoritative approval/audit history, including the configuration actually used for routing/SLA. Developer 2 evidence establishes risk calculations only.

## Unresolved business decisions and proposed assumptions

| Decision | Confirmed requirement | Unresolved / proposed treatment |
|---|---|---|
| Exact duplicate | Same supplier/invoice submission must be blocked before approval. | Scope figure shows duplicate +40 but prose blocks exact duplicates. Proposed: exact duplicates only appear as validation FAIL, never as a risk score. Confirm whether the figure intended near duplicates. Do not adopt +40 as approved policy. |
| Resubmission identity | Rejected payment does not proceed to processing; scope permits a new request for the invoice. | Existing payment service has one-request-per-invoice checks/uniqueness. Agree new payment ID versus same payment with a new submission attempt and lineage. Proposed submission token and both logical UKs must be revised if the contract differs. Developer 1 owns lineage and its schema changes. |
| New supplier / near duplicate | Scope explicitly includes structuring, frequency, unusual amount, reactivation and prior flags. | Blueprint additionally proposes new-supplier and near-duplicate rules. Confirm inventory and tolerances; optional rules are not silently enabled. |
| Scoring | Independent additive explainable rules, configurable weights/bands, one evaluation rather than continuous rescoring. | Proposed integer binary contributions, integer bands and cap behavior require approval. Scope diagram's 0-100 and 30/60 cutoffs are not assumed approved defaults. |
| Historical population | Supplier/payment history and previous flags inform risk. | Define statuses, exact time windows/bounds, current-record inclusion, minimum sample, flagged versus overridden/resolved history and missing-history policy. |
| Invoice consistency | Mandatory supplier/payment details, valid active supplier, exact duplicate prevention. | Partial payments, overpayment, quantity/account mismatch fields and currency/unit require Developer 1/business agreement. Missing fields must not be invented by this design. |
| Time and IDs | Existing numeric Long IDs and money precision are observable code contracts. | Proposed numeric external IDs and UTC zoned times need an upstream conversion agreement; submission IDs and event IDs are not yet specified. |
| Activation and SLA | Configurable bands/thresholds/SLAs; MAX routing; delayed pending approvals escalate. | Approve current pointer design, permission boundary, SLA start event, L1 human/automatic handling, escalation/backup order, L3 terminal behavior and risk/routing/SLA version pinning. |
| Evidence size / retention | Explainable historical decisions and audit required. | Full input retention, CLOB payload limits, expiry/archiving and 100 MB capacity budget need approval. No purge/cascade deletion is proposed. |
| Configuration demo values | Configuration is editable/versioned. | Proposed defaults remain in the working plan only; no business values or seed rows are generated here. Demonstration flag distinguishes provisional settings. |

## Decisions required before creating tables

1. Agree the authoritative payment/submission identity and corrected-request lineage with Developer 1; resolve invoice uniqueness versus resubmission. Finalize the unique keys before DDL.
2. Approve the table inventory, including CURRENT_CONFIGURATION and RISK_INPUT_EVIDENCE; generated-ID strategy, UTC conversion, JSON/CLOB evidence format and numeric limits.
3. Settle which rules and invoice fields are in scope, exact duplicate blocking versus scoring, history eligibility/completeness, and whether integer binary rule outcomes fit all agreed rules. Exact weights can remain configurable, but the input/outcome shape cannot remain ambiguous.
4. Agree publication/activation immutability enforcement and owner versus runtime identities. Decide whether PL/SQL-mediated writes or other guards are needed; existing CREATE PROCEDURE permission does not itself enforce policy.
5. Agree validation/assessment work states, retry/lease ownership, configuration/evidence freeze point, downstream status/audit transport and durability. If an outbox is required, design it before declaring the schema complete.
6. Agree Developer 3 threshold-boundary/SLA configuration contracts and actual version pinning. Do not put approval tasks, backup mappings, decisions or business audit in these schemas.
7. Approve evidence retention, JSON size limits and storage/index budget for each 100 MB quota. Validate JDBC types and isolated verification connectivity before implementation acceptance; SQL Developer connections do not satisfy that verification.

## Review and later verification criteria

This review should trace every required validation/risk factor to its input fields and stored explanation, confirm all foreign keys are local, and check each proposed index against an actual query. Verify on paper that concurrent publication and duplicate delivery cannot produce competing final decisions and that retries cannot silently switch configuration.

Later, after separately authorized DDL and implementation, meaningful checks include local FK/unique/check enforcement, score and money boundary round trips, rollback of incomplete result writes, stale-worker rejection, immutable published versions, complete history pagination, and historical replay after source/configuration changes. Measure representative synthetic JSON/LOB and index storage against the quotas; no claim of fitting 100 MB is made without measurement. These are planned checks, not tests performed for this document.

Only this design Markdown file is added by this task. Existing services, the working plan, and schema setup SQL are preserved. No SQL is generated/executed, services started, application code written, commits made, or pushes performed.
