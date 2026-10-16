# Developer 2 database design — current implementation

Reconciled 16 October 2026 against the reviewed repository schemas and implemented persistence. This replaces the old proposal-only narrative; it does not change or execute SQL. Scope requirements exclude Q&A; later approved demonstration policies and ownership instructions are recorded in the [current plan](../developer2-plan.md).

## Deployment and ownership

Oracle AI Database 26ai Free 23.26.2.0.0, localhost:1521/FREEPDB1, tablespace USERS. D2_CONFIGURATION's six tables and D2_RISK's four tables were deployed by the user. SQL Developer, JDBC metadata, isolated persistence and configured-client end-to-end suites have passed within the [recorded verification scope](../developer2-handoff/completion-checklist.md).

D2_CONFIGURATION retains 100M. D2_RISK was originally 100M; a later ORA-01536 was resolved by the user increasing its USERS quota to 300M. D2_VALIDATION's originally 100M account is preserved, but payment validation and its tables/implementation belong to Developer 1. No D2 validation DDL/implementation is planned. The owner accounts retain CREATE SESSION/TABLE/SEQUENCE/PROCEDURE; no runtime account/grant redesign is implied.

[Owner setup](dev2-schema-setup.sql), [configuration schema](configuration-schema.sql), [risk schema](risk-schema.sql) and [Developer 1 setup proposal](developer1-schema-setup.sql) remain historical **review-only repository copies** with password placeholders/stops. They are not safe blind rerun scripts. Original 100M quotas and preparation-time “future/pending/not executed” comments are preserved; this current document records the later deployment. No new SQL, seeds or automatic migration is introduced.

## Type and integrity conventions

Positive identity/external IDs use NUMBER(19,0) bounded to Java Long, while response IDs/revisions are strings. Money is exact NUMBER(15,2), maximum 9999999999999.99; JDBC/application validation rejects rounding before Oracle binding. Configuration timestamps are TIMESTAMP(6) WITH TIME ZONE; risk timestamps are TIMESTAMP(9) WITH TIME ZONE. Reads/writes normalize UTC and risk nanoseconds are not rounded to configuration precision. CLOB payloads use strict JSON checks plus typed/canonical application validation. Oracle VARCHAR2 CHAR limits are Unicode code-point validated where applicable.

Foreign keys are local to each owner/service. Payment, supplier, requester, invoice, validation reference and source configuration IDs are external references, not cross-service database FKs. SQL CHECK/NOT NULL/unique constraints enforce row shapes; they do not certify upstream authority, immutability, canonical hash correctness, complete populations or matching child totals.

The column inventory below is extracted from the unchanged reviewed DDL. NOT NULL/identity are declared properties; conditional CHECK requirements still apply to nullable result/reference fields. Full constraint definitions and identity options remain in the SQL sources.

## D2_CONFIGURATION

### CONFIGURATION_VERSIONS

Draft/published parent, server version number, score policy, actor/time/hash metadata and optimistic revision.

| Column | Oracle type | Declared property |
|---|---|---|
| VERSION_ID | NUMBER(19,0) | Identity; positive Java Long |
| VERSION_NUMBER | NUMBER(10,0) | NOT NULL |
| VERSION_LABEL | VARCHAR2(100 CHAR) | NOT NULL |
| PUBLICATION_STATE | VARCHAR2(16 CHAR) | NOT NULL |
| DESCRIPTION | VARCHAR2(1000 CHAR) | Nullable; conditional constraints may require it |
| IS_DEMONSTRATION | NUMBER(1,0) | NOT NULL |
| SCORE_POLICY_CODE | VARCHAR2(16 CHAR) | NOT NULL |
| MAX_FINAL_SCORE | NUMBER(10,0) | Nullable; conditional constraints may require it |
| CREATED_AT | TIMESTAMP(6) WITH TIME ZONE | NOT NULL |
| CREATED_BY_USER_ID | NUMBER(19,0) | NOT NULL |
| PUBLISHED_AT | TIMESTAMP(6) WITH TIME ZONE | Nullable; conditional constraints may require it |
| PUBLISHED_BY_USER_ID | NUMBER(19,0) | Nullable; conditional constraints may require it |
| CONTENT_HASH | CHAR(64) | Nullable; conditional constraints may require it |
| ROW_VERSION | NUMBER(19,0) | NOT NULL; default 0 |

Declared constraints: `PK_CFG_VERSIONS`, `UK_CFG_VERSION_NUMBER`, `CK_CFG_VERSION_ID`, `CK_CFG_VERSION_NUMBER`, `CK_CFG_VERSION_LABEL`, `CK_CFG_DEMO`, `CK_CFG_CREATED_USER`, `CK_CFG_PUBLISHED_USER`, `CK_CFG_ROW_VERSION`, `CK_CFG_SCORE_POLICY`, `CK_CFG_HASH`, `CK_CFG_PUBLICATION`. See the reviewed SQL for exact column sets/checks.

### RISK_RULE_CONFIGS

One rule per code and evaluation order per parent, exact parameter JSON and configurable contribution.

| Column | Oracle type | Declared property |
|---|---|---|
| RULE_CONFIG_ID | NUMBER(19,0) | Identity; positive Java Long |
| VERSION_ID | NUMBER(19,0) | NOT NULL |
| RULE_CODE | VARCHAR2(64 CHAR) | NOT NULL |
| DISPLAY_NAME | VARCHAR2(120 CHAR) | NOT NULL |
| ENABLED | NUMBER(1,0) | NOT NULL |
| WEIGHT | NUMBER(10,0) | NOT NULL |
| PARAMETER_SCHEMA_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| PARAMETERS_JSON | CLOB | NOT NULL |
| EVALUATION_ORDER | NUMBER(5,0) | NOT NULL |

Declared constraints: `PK_CFG_RULES`, `FK_CFG_RULE_VERSION`, `UK_CFG_RULE_CODE`, `UK_CFG_RULE_ORDER`, `CK_CFG_RULE_ID`, `CK_CFG_RULE_CODE`, `CK_CFG_RULE_NAME`, `CK_CFG_RULE_ENABLED`, `CK_CFG_RULE_WEIGHT`, `CK_CFG_RULE_PARAM_VER`, `CK_CFG_RULE_JSON`, `CK_CFG_RULE_ORDER`. See the reviewed SQL for exact column sets/checks.

### RISK_BAND_CONFIGS

Version-specific integer score intervals and approved risk-level mapping.

| Column | Oracle type | Declared property |
|---|---|---|
| BAND_CONFIG_ID | NUMBER(19,0) | Identity; positive Java Long |
| VERSION_ID | NUMBER(19,0) | NOT NULL |
| BAND_CODE | VARCHAR2(8 CHAR) | NOT NULL |
| MIN_SCORE_INCLUSIVE | NUMBER(10,0) | NOT NULL |
| MAX_SCORE_INCLUSIVE | NUMBER(10,0) | Nullable; conditional constraints may require it |
| APPROVAL_LEVEL | NUMBER(1,0) | NOT NULL |

Declared constraints: `PK_CFG_BANDS`, `FK_CFG_BAND_VERSION`, `UK_CFG_BAND_CODE`, `CK_CFG_BAND_ID`, `CK_CFG_BAND_CODE`, `CK_CFG_BAND_MIN`, `CK_CFG_BAND_MAX`, `CK_CFG_BAND_LEVEL`. See the reviewed SQL for exact column sets/checks.

### APPROVAL_THRESHOLD_CONFIGS

Version-specific exact half-open amount tiers and approval levels.

| Column | Oracle type | Declared property |
|---|---|---|
| THRESHOLD_CONFIG_ID | NUMBER(19,0) | Identity; positive Java Long |
| VERSION_ID | NUMBER(19,0) | NOT NULL |
| APPROVAL_LEVEL | NUMBER(1,0) | NOT NULL |
| MIN_AMOUNT_INCLUSIVE | NUMBER(15,2) | NOT NULL |
| MAX_AMOUNT_EXCLUSIVE | NUMBER(15,2) | Nullable; conditional constraints may require it |

Declared constraints: `PK_CFG_THRESHOLDS`, `FK_CFG_THRESHOLD_VERSION`, `UK_CFG_THRESHOLD_LEVEL`, `CK_CFG_THRESHOLD_ID`, `CK_CFG_THRESHOLD_LEVEL`, `CK_CFG_THRESHOLD_MIN`, `CK_CFG_THRESHOLD_MAX`. See the reviewed SQL for exact column sets/checks.

### SLA_CONFIGS

Level-specific duration and versioned timer/escalation/terminal policy codes.

| Column | Oracle type | Declared property |
|---|---|---|
| SLA_CONFIG_ID | NUMBER(19,0) | Identity; positive Java Long |
| VERSION_ID | NUMBER(19,0) | NOT NULL |
| APPROVAL_LEVEL | NUMBER(1,0) | NOT NULL |
| DURATION_SECONDS | NUMBER(12,0) | NOT NULL |
| START_EVENT_CODE | VARCHAR2(32 CHAR) | NOT NULL |
| ESCALATION_POLICY_CODE | VARCHAR2(32 CHAR) | NOT NULL |
| TERMINAL_LEVEL_POLICY_CODE | VARCHAR2(32 CHAR) | Nullable; conditional constraints may require it |

Declared constraints: `PK_CFG_SLAS`, `FK_CFG_SLA_VERSION`, `UK_CFG_SLA_LEVEL`, `CK_CFG_SLA_ID`, `CK_CFG_SLA_LEVEL`, `CK_CFG_SLA_DURATION`, `CK_CFG_SLA_START`, `CK_CFG_SLA_ESCALATION`, `CK_CFG_SLA_TERMINAL`. See the reviewed SQL for exact column sets/checks.

### CURRENT_CONFIGURATION

Singleton current pointer; activation actor/time/revision is separate from version revision.

| Column | Oracle type | Declared property |
|---|---|---|
| POINTER_ID | NUMBER(1,0) | NOT NULL |
| VERSION_ID | NUMBER(19,0) | NOT NULL |
| ACTIVATED_AT | TIMESTAMP(6) WITH TIME ZONE | NOT NULL |
| ACTIVATED_BY_USER_ID | NUMBER(19,0) | NOT NULL |
| ROW_VERSION | NUMBER(19,0) | NOT NULL; default 0 |

Declared constraints: `PK_CFG_CURRENT`, `FK_CFG_CURRENT_VERSION`, `CK_CFG_SINGLE_POINTER`, `CK_CFG_ACTIVATED_USER`, `CK_CFG_CURRENT_ROW_VERSION`. See the reviewed SQL for exact column sets/checks.

## D2_RISK

### RISK_ASSESSMENTS

One immutable accepted logical submission plus fenced recoverable work and eventual verified final summary.

| Column | Oracle type | Declared property |
|---|---|---|
| ASSESSMENT_ID | NUMBER(19,0) | Identity; positive Java Long |
| SUBMISSION_SOURCE | VARCHAR2(64 CHAR) | NOT NULL |
| SUBMISSION_KEY | VARCHAR2(128 CHAR) | NOT NULL |
| PAYMENT_ID | NUMBER(19,0) | NOT NULL |
| SUPPLIER_ID | NUMBER(19,0) | NOT NULL |
| INVOICE_ID | NUMBER(19,0) | NOT NULL |
| REQUESTER_ID | NUMBER(19,0) | NOT NULL |
| VALIDATION_REFERENCE | VARCHAR2(128 CHAR) | NOT NULL |
| VALIDATION_REVISION | VARCHAR2(128 CHAR) | Nullable; conditional constraints may require it |
| PAYMENT_AMOUNT | NUMBER(15,2) | NOT NULL |
| CURRENCY_CODE | VARCHAR2(3 CHAR) | NOT NULL |
| CONFIGURATION_VERSION_ID | NUMBER(19,0) | NOT NULL |
| CONFIGURATION_ROW_VERSION | NUMBER(19,0) | NOT NULL |
| CONFIGURATION_SNAPSHOT_ID | NUMBER(19,0) | Nullable; conditional constraints may require it |
| EVALUATOR_VERSION | VARCHAR2(64 CHAR) | NOT NULL |
| HASH_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| ACCEPTANCE_JSON | CLOB | NOT NULL |
| ACCEPTANCE_HASH | CHAR(64) | NOT NULL |
| CONFIGURATION_HASH | CHAR(64) | NOT NULL |
| INPUT_HASH | CHAR(64) | Nullable; conditional constraints may require it |
| FROZEN_INPUT_EVIDENCE_ID | NUMBER(19,0) | Nullable; conditional constraints may require it |
| INPUT_FROZEN_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| EVIDENCE_AS_OF | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |
| WORK_STATE | VARCHAR2(24 CHAR) | NOT NULL; default 'ACCEPTED' |
| TOTAL_RAW_SCORE | NUMBER(19,0) | Nullable; conditional constraints may require it |
| FINAL_SCORE | NUMBER(10,0) | Nullable; conditional constraints may require it |
| RISK_BAND | VARCHAR2(8 CHAR) | Nullable; conditional constraints may require it |
| FINAL_FACTOR_COUNT | NUMBER(1,0) | Nullable; conditional constraints may require it |
| ATTEMPT_COUNT | NUMBER(10,0) | NOT NULL; default 0 |
| LEASE_EPOCH | NUMBER(19,0) | NOT NULL; default 0 |
| LEASE_TOKEN | VARCHAR2(64 CHAR) | Nullable; conditional constraints may require it |
| LEASE_EXPIRES_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| CREATED_AT | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |
| EVALUATED_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| COMPLETED_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| LAST_ERROR_CODE | VARCHAR2(64 CHAR) | Nullable; conditional constraints may require it |
| LAST_ERROR_MESSAGE | VARCHAR2(1000 CHAR) | Nullable; conditional constraints may require it |
| CORRELATION_ID | VARCHAR2(128 CHAR) | Nullable; conditional constraints may require it |
| ROW_VERSION | NUMBER(19,0) | NOT NULL; default 0 |

Declared constraints: `PK_RSK_ASM`, `UK_RSK_SUBMISSION`, `CK_RSK_ASM_IDS`, `CK_RSK_ASM_TEXT`, `CK_RSK_ASM_MONEY`, `CK_RSK_ASM_ACCEPT_JSON`, `CK_RSK_ASM_HASHES`, `CK_RSK_ASM_STATE`, `CK_RSK_ASM_COUNTERS`, `CK_RSK_ASM_LEASE`, `CK_RSK_ASM_FREEZE`, `CK_RSK_ASM_CUTOFF`, `CK_RSK_ASM_RESULT`, `CK_RSK_ASM_ERRORS`. See the reviewed SQL for exact column sets/checks.

### RISK_CONFIGURATION_SNAPSHOTS

One immutable canonical engine configuration/provenance snapshot per assessment.

| Column | Oracle type | Declared property |
|---|---|---|
| SNAPSHOT_ID | NUMBER(19,0) | Identity; positive Java Long |
| ASSESSMENT_ID | NUMBER(19,0) | NOT NULL |
| SOURCE_VERSION_ID | NUMBER(19,0) | NOT NULL |
| SOURCE_ROW_VERSION | NUMBER(19,0) | NOT NULL |
| SOURCE_VERSION_NUMBER | NUMBER(10,0) | Nullable; conditional constraints may require it |
| SNAPSHOT_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| HASH_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| CONFIGURATION_JSON | CLOB | NOT NULL |
| CONTENT_HASH | CHAR(64) | NOT NULL |
| CAPTURED_AT | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |

Declared constraints: `PK_RSK_SNAP`, `UK_RSK_SNAP_ASM`, `UK_RSK_SNAP_PARENT_ID`, `FK_RSK_SNAP_ASM`, `CK_RSK_SNAP_IDS`, `CK_RSK_SNAP_TEXT`, `CK_RSK_SNAP_JSON`, `CK_RSK_SNAP_HASH`. See the reviewed SQL for exact column sets/checks.

### RISK_INPUT_EVIDENCE

Immutable final input groups/manifests, durable partial local prior-history capture and isolated per-attempt diagnostics.

| Column | Oracle type | Declared property |
|---|---|---|
| EVIDENCE_ID | NUMBER(19,0) | Identity; positive Java Long |
| ASSESSMENT_ID | NUMBER(19,0) | NOT NULL |
| RECORD_CLASS | VARCHAR2(24 CHAR) | NOT NULL |
| ATTEMPT_NO | NUMBER(10,0) | NOT NULL |
| EVIDENCE_KEY | VARCHAR2(128 CHAR) | NOT NULL |
| EVIDENCE_KIND | VARCHAR2(24 CHAR) | NOT NULL |
| SOURCE_SERVICE | VARCHAR2(64 CHAR) | NOT NULL |
| SOURCE_RECORD_REFERENCE | VARCHAR2(128 CHAR) | Nullable; conditional constraints may require it |
| SOURCE_SNAPSHOT_REFERENCE | VARCHAR2(128 CHAR) | Nullable; conditional constraints may require it |
| SOURCE_REVISION | VARCHAR2(128 CHAR) | Nullable; conditional constraints may require it |
| EVIDENCE_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| HASH_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| AS_OF_AT | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |
| WINDOW_START_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| WINDOW_END_AT | TIMESTAMP(9) WITH TIME ZONE | Nullable; conditional constraints may require it |
| RECORD_COUNT | NUMBER(10,0) | Nullable; conditional constraints may require it |
| AVAILABILITY | VARCHAR2(16 CHAR) | Nullable; conditional constraints may require it |
| EVIDENCE_JSON | CLOB | NOT NULL |
| CONTENT_HASH | CHAR(64) | NOT NULL |
| CAPTURED_AT | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |

Declared constraints: `PK_RSK_EVD`, `UK_RSK_EVD_KEY`, `UK_RSK_EVD_PARENT_ID`, `FK_RSK_EVD_ASM`, `CK_RSK_EVD_ID`, `CK_RSK_EVD_TEXT`, `CK_RSK_EVD_CLASS`, `CK_RSK_EVD_AVAIL`, `CK_RSK_EVD_COUNT`, `CK_RSK_EVD_WINDOW`, `CK_RSK_EVD_CAPTURE`, `CK_RSK_EVD_JSON`, `CK_RSK_EVD_HASH`. See the reviewed SQL for exact column sets/checks.

### RISK_ASSESSMENT_FACTORS

Exactly seven verified final factors per completed assessment with original explanations/evidence details.

| Column | Oracle type | Declared property |
|---|---|---|
| FACTOR_ID | NUMBER(19,0) | Identity; positive Java Long |
| ASSESSMENT_ID | NUMBER(19,0) | NOT NULL |
| RULE_CODE | VARCHAR2(64 CHAR) | NOT NULL |
| RULE_CONFIG_SOURCE_ID | NUMBER(19,0) | Nullable; conditional constraints may require it |
| FACTOR_ORDER | NUMBER(5,0) | NOT NULL |
| EVALUATION_OUTCOME | VARCHAR2(24 CHAR) | NOT NULL |
| CONFIGURED_WEIGHT | NUMBER(10,0) | NOT NULL |
| SCORE_CONTRIBUTION | NUMBER(10,0) | NOT NULL |
| PRIMARY_EVIDENCE_ID | NUMBER(19,0) | Nullable; conditional constraints may require it |
| EXPLANATION | VARCHAR2(2000 CHAR) | NOT NULL |
| RESULT_FORMAT_VERSION | VARCHAR2(32 CHAR) | NOT NULL |
| DETAILS_JSON | CLOB | NOT NULL |
| EVALUATED_AT | TIMESTAMP(9) WITH TIME ZONE | NOT NULL |

Declared constraints: `PK_RSK_FAC`, `UK_RSK_FAC_CODE`, `UK_RSK_FAC_ORDER`, `FK_RSK_FAC_ASM`, `FK_RSK_FAC_EVD`, `CK_RSK_FAC_IDS`, `CK_RSK_FAC_CODE`, `CK_RSK_FAC_ORDER`, `CK_RSK_FAC_TEXT`, `CK_RSK_FAC_SCORES`, `CK_RSK_FAC_OUTCOME`, `CK_RSK_FAC_JSON`. See the reviewed SQL for exact column sets/checks.

## Keys, indexes and cross-row responsibilities

Configuration primary keys are each generated ID, except CURRENT_CONFIGURATION's fixed POINTER_ID=1. VERSION_NUMBER is unique. Child uniqueness is (VERSION_ID,RULE_CODE), (VERSION_ID,EVALUATION_ORDER), (VERSION_ID,BAND_CODE), (VERSION_ID,APPROVAL_LEVEL) for thresholds/SLAs. Child version FKs stay local. Unique indexes support those lookups; IX_CFG_CURRENT_VERSION indexes the singleton version FK. Child replacement preserves identity. Rule weights 0..2147483647, evaluation orders 1..99999, levels 1..3, SLA duration 1..999999999999 and exact money limits are enforced before binding; Oracle checks supplement this.

RISK_ASSESSMENTS has PK_RSK_ASM and UK_RSK_SUBMISSION(SUBMISSION_SOURCE,SUBMISSION_KEY). Snapshot uniqueness is assessment ID and composite(ASSESSMENT_ID,SNAPSHOT_ID); evidence uniqueness is (ASSESSMENT_ID,RECORD_CLASS,ATTEMPT_NO,EVIDENCE_KEY) plus (ASSESSMENT_ID,EVIDENCE_ID). The composite pairs are FK targets so a parent/factor cannot associate another assessment's child. Factors are unique by assessment/code and assessment/order. Primary references are local assessment IDs; no cascade deletion was introduced.

Parents are created before children; nullable snapshot/manifest references permit temporary uncommitted parent insertion. Later local composite FKs FK_RSK_ASM_SNAP/FK_RSK_ASM_INPUT are added after both child targets exist. Acceptance associates its snapshot before commit; freeze associates its manifest before commit. SQL existence constraints alone do not require acceptance to associate the snapshot before every possible owner-DML commit: service transactions enforce that stronger invariant.

Explicit risk indexes: IX_RSK_SUPPLIER_HISTORY(SUPPLIER_ID,WORK_STATE,COMPLETED_AT), IX_RSK_RECOVERY(WORK_STATE,LEASE_EXPIRES_AT), IX_RSK_PAYMENT(PAYMENT_ID), IX_RSK_ASM_SNAP(ASSESSMENT_ID,CONFIGURATION_SNAPSHOT_ID), IX_RSK_ASM_INPUT(ASSESSMENT_ID,FROZEN_INPUT_EVIDENCE_ID), IX_RSK_FAC_EVD(ASSESSMENT_ID,PRIMARY_EVIDENCE_ID). Unique constraints supply additional indexes. Oracle may report hidden SYS_NC columns for indexed timezone timestamps; the read-only verifier checks ordered positions plus actual underlying timestamp expressions through USER_IND_EXPRESSIONS, rather than accepting arbitrary hidden names.

## Publication, immutability and policy representation

Every application child write locks the DRAFT parent, checks expected rowVersion and increments it with the child change. PUBLISHED versions reject edits. Real readiness coherently validates all seven typed rules, supported policy choices, aggregate score limits, LOW/MEDIUM/HIGH coverage/mapping, contiguous exact amount tiers from zero with unbounded L3 and all three supported SLA entries. Incomplete drafts are permitted; publication is not.

Publication derives the actor from verified JWT, normalizes publication time UTC, hashes complete content and transitions DRAFT→PUBLISHED plus revision in one transaction; it does not activate. Activation verifies publication/hash and atomically creates/updates singleton pointer with its own revision. Concurrent first activation is serialized. Repeated activation with matching revision preserves metadata. Published content/historical assessments are not rewritten by a pointer change.

No extra policy column was needed: SLA codes and band mappings select immutable approval-policy-v1 semantics, explicitly expanded and hash-covered by configuration-content-v2. Human authorization, MAX authority, task PENDING timer, deadline retention, destination-level new timers, one eligible L3 backup and manual review/audit obligations are all represented in the immutable snapshot. Historical V1 reads preserve V1 meaning; new submissions reject unsupported legacy semantics. Do not silently change the meaning of existing version codes.

Owner SQL could bypass application invariants. No trigger/PLSQL/write-role boundary or privileged-user tamper-proof guarantee is claimed. A later change to enforce immutability at the privilege layer needs its own reviewed design.

## Identity, freezing, recovery and finalization

UK_RSK_SUBMISSION reserves one assessment per stable namespaced submission identity, separate from PAYMENT_ID. First committed acceptance pins validation reference/revision, authoritative projected facts, retry exclusions, cutoff, evaluator and immutable engine configuration. Matching retry with omitted explicit configuration uses the pinned version; conflicting accepted facts/explicit choice conflicts without replacing snapshots. Genuine linked resubmission lineage remains external pending Developer 1 agreement.

Hashes are separate, versioned and locally recomputed: publication contentHash; canonical risk configurationHash; acceptanceHash; full inputHash; per-evidence/manifest hashes and provider population digest. A publisher hash is not relabelled as the local risk configuration hash. Canonical data retains exact money, explicit nulls, UTC nanoseconds, declared set sorting and meaningful array order. Source provider certification/trust is required independently of a digest.

Lease claim/takeover increments epoch/attempt/revision with opaque token and expiry. Mutations recheck state, token, epoch, attempt, revision and Oracle-clock expiry at final write. Expired/superseded workers roll back, including inserted children; renewal cannot resurrect them. ACCEPTED, RETRYABLE_FAILURE or expired IN_PROGRESS can be claimed; BLOCKED requires intervention and COMPLETED cannot be reclaimed/renewed/failed. Frozen facts/snapshots/input survive failure and takeover. Numeric exhaustion fails rather than wrapping.

Evidence record classes have distinct namespaces: INPUT/attempt0 immutable dependency groups, MANIFEST/attempt0 ENGINE_INPUT, and ATTEMPT_DIAGNOSTIC/attempt>0 RESULT. Durable local prior capture uses the reserved INPUT/attempt0 LOCAL_PRIOR_POPULATION key/codec; it can exist before a complete final input. It contains reconstructible records, coverage, identities and hashes, not merely a receipt digest. Unknown/conflicting/corrupt partial children fail closed. This compatible partial capture does not permit incomplete enabled evidence to become the authoritative final manifest. Required enabled dependencies must be COMPLETE; intentionally disabled dependencies may retain typed unavailable envelopes.

The local history read uses COMPLETED_AT in [cutoff-window,cutoff), across requesters, excluding current/retries and matching original TRIGGERED factors including weight-zero factors; PREVIOUS_RISK_FLAGS itself never qualifies. The durable capture is the first coherent collection snapshot's committed population, **not acceptance-time commit visibility**. EVALUATED_AT records evaluation time and does not drive this lookback. Later commits cannot silently alter a retry because it replays the stored population; capacity overflow stays incomplete/intervention-required.

Full freeze writes input groups plus manifest and associates parent inputHash atomically under the lease fence. Finalization reconstructs/verifies snapshots/hashes, evaluates pinned risk-scoring-v1 outside database locks, then verifies the same immutable identities/fence and inserts all seven factors plus COMPLETED summary atomically. Raw score≤15032385529, final score≤2147483647 and pinned cap, correct band and seven factors are checked by service logic. SQL additionally rejects missing completion references/times/results, technical/policy outcomes in final factors and scores on non-completed parents. Cross-row factor count, sum/cap/band/explanations/evidence membership cannot be proven by CHECK constraints alone.

COMPLETED retries verify/read persisted results without rescoring or changing completion time. Technical failures keep scores/band null; approved INSUFFICIENT_HISTORY contributes zero while other rules continue. Oracle CLOB locator binding/resource lifetime and nanosecond round trips were exercised in isolated user-run suites, not claimed solely from mocks.

## Integration and delivery boundaries

Published Configuration snapshots include complete approval/SLA semantics; Risk's stored engine projection is a separate representation/hash and is insufficient by itself for D3's full approval policy. D3 resolves the assessment's pinned published version/hash, never substitutes current active configuration. Source provider Facts/Evidence must guarantee authority/completeness and original-cutoff receipt replay before freezing; their actual D1 endpoints/retention and genuine issuer/TLS provisioning remain pending.

Polling/retry is the proposed durable handoff: D1/D3 store identity/checkpoints, GET status read-only and POST same identity to progress recoverable work. No callback/outbox/scheduler/automatic payment processing or exactly-once cross-service delivery is implemented. Push delivery, if subsequently agreed, would need a separately reviewed outbox and recipient deduplication; no outbox table is implied by these four core tables.

Current specs, examples, operational instructions and verification limits are in the [integration handoff](../developer2-handoff/README.md). No new schema decision is needed for the completed D2 milestones; genuine identity/provider/workflow guarantees need team agreement before cross-team production sign-off.
