-- REVIEW ONLY - NOT EXECUTED. Prepared 14 October 2026.
-- Oracle AI Database 26ai Free; FREEPDB1; D2_RISK; USERS.
-- Client: SQL*Plus/SQLcl or SQL Developer Run Script (F5).
-- The explicit EXIT below stops before ALL DDL. Keep this repository copy.
-- A future execution needs a separately reviewed/authorized copy with only the
-- marked review stop removed. Selecting individual DDL bypasses script guards.
-- No seeds, grants, accounts, triggers, packages, outbox or destructive DDL.
-- ALTER TABLE below only adds local FKs to the newly created tables.
-- Oracle DDL commits implicitly; ROLLBACK cannot undo earlier successful DDL.
-- After a partial failure inspect objects; do not bypass the rerun guard.
-- Guard checks are read-only. None of this script has been executed.
-- IDs: positive Java Long, Oracle identity bounded to 9223372036854775807.
-- Money: exact nonnegative NUMBER(15,2), matching the engine's numeric domain.
-- Future bindings must reject fractional IDs/scores and money needing rounding
-- BEFORE JDBC binding; NUMBER(p,s) coercion alone can round incoming values.
-- All risk timestamps: TIMESTAMP(9) WITH TIME ZONE, written/read as UTC instants.
-- Nanoseconds must not be rounded to the configuration schema's microseconds.

-- PROVISIONAL IDENTITY AND IMMUTABLE ACCEPTANCE
-- UK(SUBMISSION_SOURCE, SUBMISSION_KEY) reserves one logical assessment.
-- Developer 1 must confirm namespace/key scope before execution/implementation.
-- PAYMENT_ID is an external association, never a replacement for submission ID.
-- The future adapter must consistently namespace opaque engine submission IDs,
-- including prior history and exclusion identities; no delimiter ambiguity.
-- Transport correlation/event IDs are not logical identities. Genuine linked
-- resubmission identity/lineage remains external pending Developer 1 agreement.
-- First committed acceptance pins current facts, validation reference/revision,
-- identities/exclusions, cutoff, coherent full engine configuration and build.
-- ACCEPTANCE_JSON preserves that envelope; its hash detects conflicting retries.
-- Retries without supplied replacements reuse the pinned data. Explicit changed
-- facts/configuration/evidence conflict; never replace or rescore a completed row.
-- Acceptance must insert the configuration snapshot and set its parent reference
-- in the same transaction; only temporary uncommitted parent rows may lack it.

-- THREE SEPARATE, LOCALLY COMPUTED HASHES
-- ACCEPTANCE_HASH: canonical immutable acceptance envelope.
-- CONFIGURATION_HASH: canonical received configuration body and provenance.
-- INPUT_HASH: canonical assembled, fully frozen RiskInput (nullable until frozen).
-- Use locally computed lowercase SHA-256, with a versioned canonical format:
-- sorted keys/declared sets; stable record ordering; money decimal strings at
-- scale two; exact ratios; explicit nulls; UTC timestamps at nanosecond precision.
-- Preserve genuinely ordered arrays. Exclude transport/attempt/lease/capture
-- metadata and the locally computed hash itself from its hashed body.
-- The engine's supplied contentHash is opaque: retain it as source provenance,
-- not as verified local SHA-256. Verify any publisher hash separately using its
-- declared canonical contract; do not silently relabel it as our local hash.

-- EVIDENCE AND ATTEMPT DIAGNOSTICS SHARE A TABLE, NOT A KEY SPACE
-- INPUT: ATTEMPT_NO=0, stable EVIDENCE_KEY, append once after coherent successful
-- capture. Preserve captured groups across retries; never overwrite them.
-- MANIFEST: ATTEMPT_NO=0, EVIDENCE_KEY='ENGINE_INPUT', one immutable manifest of
-- input row IDs/hashes, exclusion set and assembly version. Its own CONTENT_HASH
-- hashes the manifest; parent INPUT_HASH hashes the assembled complete engine
-- input. Do not assume those two hashes are equal.
-- ATTEMPT_DIAGNOSTIC: ATTEMPT_NO>0, EVIDENCE_KEY='RESULT', at most one immutable
-- terminal diagnostic per claimed attempt. Store sanitized failures/policy blocks,
-- partial outcomes, original observations and evaluated evidence references.
-- Diagnostics never enter INPUT_HASH or supplier-wide historical factor queries.
-- UK(assessment,class,attempt,key) separates these namespaces without NULL keys.
-- Capture failed/unavailable fetch envelopes inside attempt diagnostics, not an
-- INPUT key that would prevent later successful capture at the SAME pinned cutoff.
-- For disabled dependencies the manifest may deliberately preserve UNAVAILABLE
-- typed envelopes; required ENABLED dependencies must be COMPLETE before freezing.
-- Non-COMPLETE INPUT rows are allowed only as intentionally ignored frozen
-- envelopes for disabled dependencies. Required failed captures stay diagnostic.
-- COMPLETE empty history is not INCOMPLETE/UNAVAILABLE. INSUFFICIENT_HISTORY is a
-- FACTOR outcome (including the approved zero baseline), not evidence availability.

-- PRIOR ASSESSMENT TIME CONTRACT (NO ENGINE CHANGE)
-- The engine filters the supplied PriorAssessment.assessedAt. It has no clock or
-- persisted assessedAt definition. This adapter contract maps it to COMPLETED_AT:
-- the DB timestamp recorded during successful fenced finalization, NOT a claim
-- of the exact physical commit instant. Only committed COMPLETED rows qualify.
-- EVALUATED_AT records successful engine evaluation completion for diagnostics;
-- it may precede finalization and MUST NOT drive the previous-flags lookback.
-- Query supplier-wide, across requesters, using COMPLETED_AT in [cutoff-window,
-- cutoff), exclude current/retry namespaced identities, and match original
-- TRIGGERED factors from the configured six codes (including weight-zero triggers).
-- Count distinct logical assessments once. Never filter on later overrides.
-- Preserve the prior-assessment population from a consistent committed snapshot
-- during first acceptance (or an equivalent durable as-of provider contract).
-- A timestamp predicate alone cannot recreate first visibility after later commits.

-- LEASE FENCING / REQUIRED FUTURE SERVICE TRANSACTIONS
-- Every claim/takeover increments LEASE_EPOCH and ATTEMPT_COUNT and sets a fresh
-- LEASE_TOKEN plus expiry; reject counter exhaustion, never wrap. After acceptance,
-- every write (input capture/freeze, diagnostic, failure, heartbeat, finalization)
-- locks the parent and checks IN_PROGRESS, token, epoch, attempt and ROW_VERSION.
-- An expired lease cannot be resurrected by heartbeat; a new fenced claim is needed.
-- Recheck expiry against the DATABASE clock and execute a fenced parent update
-- before committing child changes. Zero updated rows => rollback the ENTIRE tx.
-- Superseded workers cannot capture/freeze evidence, append diagnostics or finalize.
-- No network fetch or scoring while holding the parent lock. Recover stale claims
-- at the pinned cutoff/build/configuration, using already captured evidence.

-- FINALIZATION IS ONE TRANSACTION, NOT A FINAL_FACTOR_COUNT DECLARATION
-- Under the fence, verify referenced snapshot/hash/version/build and manifest,
-- every input reference/hash/cutoff, completeness of enabled dependencies, and
-- the exact seven unique rule codes/orders against the frozen configuration.
-- Insert the full FINAL factor set, verify each outcome/contribution/matched ID,
-- raw=sum(contributions), final=min(raw,pinned cap), and exactly one matching band.
-- Set FINAL_FACTOR_COUNT=7, EVALUATED_AT, COMPLETED_AT, scores/band and COMPLETED,
-- clear lease and increment ROW_VERSION only after verification; otherwise rollback.
-- SQL checks/FKs ensure local references and basic row shapes, NOT child counts,
-- hash correctness, manifest role/completeness, cross-row totals or immutability.
-- Parent COMPLETE checks are necessary but cannot alone enforce this protocol.
-- Owner DML can bypass service invariants; no privilege-enforced immutability claim.
-- Technical failures retain null scores/band and a retryable/blocking diagnostic.
-- Completed factors permit INSUFFICIENT_HISTORY zero; no technical/policy failure
-- is encoded as NOT_TRIGGERED. Failed engine factor sets live in diagnostics only.
-- Evaluator artifact retention, Developer 1 validation/as-of/identity contracts,
-- coherent configuration adapters and retention within the 100 MB quota are pending.
-- Callback/audit delivery is separate; durable delivery later requires an outbox
-- inserted with completion and recipient deduplication. No outbox is created here.

SET ECHO OFF
SET VERIFY OFF
SET DEFINE OFF
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK

-- Fail closed: direct owner connection and unchanged current schema/container.
BEGIN
    IF NVL(SYS_CONTEXT('USERENV', 'CON_NAME'), 'UNKNOWN') <> 'FREEPDB1'
       OR NVL(SYS_CONTEXT('USERENV', 'SESSION_USER'), 'UNKNOWN') <> 'D2_RISK'
       OR NVL(SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'UNKNOWN') <> 'D2_RISK' THEN
        RAISE_APPLICATION_ERROR(-20001,
            'Connect directly as D2_RISK in FREEPDB1 with current schema D2_RISK.');
    END IF;
END;
/

-- Stop BEFORE the first DDL if any target object or reserved constraint/index
-- prefix exists. This includes partial earlier creation and name collisions.
DECLARE
    existing_objects PLS_INTEGER;
    existing_constraints PLS_INTEGER;
BEGIN
    SELECT COUNT(*) INTO existing_objects
    FROM user_objects
    WHERE object_name IN ('RISK_ASSESSMENTS', 'RISK_ASSESSMENT_FACTORS',
        'RISK_CONFIGURATION_SNAPSHOTS', 'RISK_INPUT_EVIDENCE')
       OR SUBSTR(object_name, 1, 7) IN
           ('PK_RSK_', 'UK_RSK_', 'FK_RSK_', 'CK_RSK_', 'IX_RSK_');
    SELECT COUNT(*) INTO existing_constraints
    FROM user_constraints
    WHERE SUBSTR(constraint_name, 1, 7) IN
        ('PK_RSK_', 'UK_RSK_', 'FK_RSK_', 'CK_RSK_');
    IF existing_objects > 0 OR existing_constraints > 0 THEN
        RAISE_APPLICATION_ERROR(-20002,
            'Risk target objects/names already exist. Stop and inspect; do not blindly rerun.');
    END IF;
END;
/

-- BEGIN REVIEW-ONLY STOP -- retain in the repository copy.
PROMPT REVIEW ONLY: stopping before all table/index/constraint creation.
EXIT SUCCESS ROLLBACK
-- END REVIEW-ONLY STOP

CREATE TABLE D2_RISK.RISK_ASSESSMENTS (
    ASSESSMENT_ID NUMBER(19,0) GENERATED ALWAYS AS IDENTITY
        (START WITH 1 INCREMENT BY 1 MAXVALUE 9223372036854775807 NOCYCLE),
    SUBMISSION_SOURCE VARCHAR2(64 CHAR) NOT NULL,
    SUBMISSION_KEY VARCHAR2(128 CHAR) NOT NULL,
    PAYMENT_ID NUMBER(19,0) NOT NULL,
    SUPPLIER_ID NUMBER(19,0) NOT NULL,
    INVOICE_ID NUMBER(19,0) NOT NULL,
    REQUESTER_ID NUMBER(19,0) NOT NULL,
    VALIDATION_REFERENCE VARCHAR2(128 CHAR) NOT NULL,
    VALIDATION_REVISION VARCHAR2(128 CHAR),
    PAYMENT_AMOUNT NUMBER(15,2) NOT NULL,
    CURRENCY_CODE VARCHAR2(3 CHAR) NOT NULL,
    CONFIGURATION_VERSION_ID NUMBER(19,0) NOT NULL,
    CONFIGURATION_ROW_VERSION NUMBER(19,0) NOT NULL,
    CONFIGURATION_SNAPSHOT_ID NUMBER(19,0),
    EVALUATOR_VERSION VARCHAR2(64 CHAR) NOT NULL,
    HASH_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    ACCEPTANCE_JSON CLOB NOT NULL,
    ACCEPTANCE_HASH CHAR(64) NOT NULL,
    CONFIGURATION_HASH CHAR(64) NOT NULL,
    INPUT_HASH CHAR(64),
    FROZEN_INPUT_EVIDENCE_ID NUMBER(19,0),
    INPUT_FROZEN_AT TIMESTAMP(9) WITH TIME ZONE,
    EVIDENCE_AS_OF TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    WORK_STATE VARCHAR2(24 CHAR) DEFAULT 'ACCEPTED' NOT NULL,
    TOTAL_RAW_SCORE NUMBER(19,0),
    FINAL_SCORE NUMBER(10,0),
    RISK_BAND VARCHAR2(8 CHAR),
    FINAL_FACTOR_COUNT NUMBER(1,0),
    ATTEMPT_COUNT NUMBER(10,0) DEFAULT 0 NOT NULL,
    LEASE_EPOCH NUMBER(19,0) DEFAULT 0 NOT NULL,
    LEASE_TOKEN VARCHAR2(64 CHAR),
    LEASE_EXPIRES_AT TIMESTAMP(9) WITH TIME ZONE,
    CREATED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    EVALUATED_AT TIMESTAMP(9) WITH TIME ZONE,
    COMPLETED_AT TIMESTAMP(9) WITH TIME ZONE,
    LAST_ERROR_CODE VARCHAR2(64 CHAR),
    LAST_ERROR_MESSAGE VARCHAR2(1000 CHAR),
    CORRELATION_ID VARCHAR2(128 CHAR),
    ROW_VERSION NUMBER(19,0) DEFAULT 0 NOT NULL,
    CONSTRAINT PK_RSK_ASM PRIMARY KEY (ASSESSMENT_ID) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_SUBMISSION UNIQUE (SUBMISSION_SOURCE, SUBMISSION_KEY)
        USING INDEX TABLESPACE USERS,
    CONSTRAINT CK_RSK_ASM_IDS CHECK (
        ASSESSMENT_ID BETWEEN 1 AND 9223372036854775807
        AND PAYMENT_ID BETWEEN 1 AND 9223372036854775807
        AND SUPPLIER_ID BETWEEN 1 AND 9223372036854775807
        AND INVOICE_ID BETWEEN 1 AND 9223372036854775807
        AND REQUESTER_ID BETWEEN 1 AND 9223372036854775807
        AND CONFIGURATION_VERSION_ID BETWEEN 1 AND 9223372036854775807
        AND CONFIGURATION_ROW_VERSION BETWEEN 0 AND 9223372036854775807),
    CONSTRAINT CK_RSK_ASM_TEXT CHECK (
        TRIM(SUBMISSION_SOURCE) IS NOT NULL AND TRIM(SUBMISSION_KEY) IS NOT NULL
        AND TRIM(VALIDATION_REFERENCE) IS NOT NULL
        AND (VALIDATION_REVISION IS NULL OR TRIM(VALIDATION_REVISION) IS NOT NULL)
        AND TRIM(EVALUATOR_VERSION) IS NOT NULL AND TRIM(HASH_FORMAT_VERSION) IS NOT NULL
        AND (CORRELATION_ID IS NULL OR TRIM(CORRELATION_ID) IS NOT NULL)),
    CONSTRAINT CK_RSK_ASM_MONEY CHECK (PAYMENT_AMOUNT >= 0 AND CURRENCY_CODE = 'INR'),
    CONSTRAINT CK_RSK_ASM_ACCEPT_JSON CHECK (ACCEPTANCE_JSON IS JSON STRICT),
    CONSTRAINT CK_RSK_ASM_HASHES CHECK (
        REGEXP_LIKE(ACCEPTANCE_HASH, '^[0-9a-f]{64}$', 'c')
        AND REGEXP_LIKE(CONFIGURATION_HASH, '^[0-9a-f]{64}$', 'c')
        AND (INPUT_HASH IS NULL OR REGEXP_LIKE(INPUT_HASH, '^[0-9a-f]{64}$', 'c'))),
    CONSTRAINT CK_RSK_ASM_STATE CHECK
        (WORK_STATE IN ('ACCEPTED', 'IN_PROGRESS', 'RETRYABLE_FAILURE', 'BLOCKED', 'COMPLETED')),
    CONSTRAINT CK_RSK_ASM_COUNTERS CHECK (
        ATTEMPT_COUNT BETWEEN 0 AND 2147483647
        AND LEASE_EPOCH BETWEEN 0 AND 9223372036854775807
        AND ROW_VERSION BETWEEN 0 AND 9223372036854775807),
    CONSTRAINT CK_RSK_ASM_LEASE CHECK (
        (WORK_STATE <> 'IN_PROGRESS' AND LEASE_TOKEN IS NULL AND LEASE_EXPIRES_AT IS NULL) OR
        (WORK_STATE = 'IN_PROGRESS' AND LEASE_TOKEN IS NOT NULL AND TRIM(LEASE_TOKEN) IS NOT NULL
         AND LEASE_EXPIRES_AT IS NOT NULL AND LEASE_EXPIRES_AT >= CREATED_AT
         AND LEASE_EPOCH > 0 AND ATTEMPT_COUNT > 0 AND WORK_STATE = 'IN_PROGRESS')),
    CONSTRAINT CK_RSK_ASM_FREEZE CHECK (
        (INPUT_HASH IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_FROZEN_AT IS NULL) OR
        (INPUT_HASH IS NOT NULL AND FROZEN_INPUT_EVIDENCE_ID IS NOT NULL
         AND INPUT_FROZEN_AT IS NOT NULL AND INPUT_FROZEN_AT >= CREATED_AT)),
    CONSTRAINT CK_RSK_ASM_CUTOFF CHECK (EVIDENCE_AS_OF <= CREATED_AT),
    CONSTRAINT CK_RSK_ASM_RESULT CHECK (
        (WORK_STATE = 'COMPLETED' AND CONFIGURATION_SNAPSHOT_ID IS NOT NULL
         AND INPUT_HASH IS NOT NULL AND FROZEN_INPUT_EVIDENCE_ID IS NOT NULL
         AND INPUT_FROZEN_AT IS NOT NULL AND FINAL_FACTOR_COUNT IS NOT NULL
         AND FINAL_FACTOR_COUNT = 7 AND TOTAL_RAW_SCORE IS NOT NULL
         AND TOTAL_RAW_SCORE BETWEEN 0 AND 15032385529
         AND FINAL_SCORE IS NOT NULL AND FINAL_SCORE BETWEEN 0 AND 2147483647
         AND FINAL_SCORE <= TOTAL_RAW_SCORE AND RISK_BAND IS NOT NULL
         AND RISK_BAND IN ('LOW', 'MEDIUM', 'HIGH')
         AND EVALUATED_AT IS NOT NULL AND EVALUATED_AT >= INPUT_FROZEN_AT
         AND COMPLETED_AT IS NOT NULL AND COMPLETED_AT >= EVALUATED_AT
         AND LEASE_TOKEN IS NULL AND LEASE_EXPIRES_AT IS NULL
         AND LAST_ERROR_CODE IS NULL AND LAST_ERROR_MESSAGE IS NULL) OR
        (WORK_STATE <> 'COMPLETED' AND TOTAL_RAW_SCORE IS NULL AND FINAL_SCORE IS NULL
         AND RISK_BAND IS NULL AND FINAL_FACTOR_COUNT IS NULL
         AND EVALUATED_AT IS NULL AND COMPLETED_AT IS NULL)),
    CONSTRAINT CK_RSK_ASM_ERRORS CHECK (
        (WORK_STATE IN ('RETRYABLE_FAILURE', 'BLOCKED') AND LAST_ERROR_CODE IS NOT NULL
         AND TRIM(LAST_ERROR_CODE) IS NOT NULL) OR
        (WORK_STATE IN ('ACCEPTED', 'IN_PROGRESS', 'COMPLETED') AND LAST_ERROR_CODE IS NULL
         AND LAST_ERROR_MESSAGE IS NULL))
) TABLESPACE USERS;

CREATE TABLE D2_RISK.RISK_CONFIGURATION_SNAPSHOTS (
    SNAPSHOT_ID NUMBER(19,0) GENERATED ALWAYS AS IDENTITY
        (START WITH 1 INCREMENT BY 1 MAXVALUE 9223372036854775807 NOCYCLE),
    ASSESSMENT_ID NUMBER(19,0) NOT NULL,
    SOURCE_VERSION_ID NUMBER(19,0) NOT NULL,
    SOURCE_ROW_VERSION NUMBER(19,0) NOT NULL,
    SOURCE_VERSION_NUMBER NUMBER(10,0),
    SNAPSHOT_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    HASH_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    CONFIGURATION_JSON CLOB NOT NULL,
    CONTENT_HASH CHAR(64) NOT NULL,
    CAPTURED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT PK_RSK_SNAP PRIMARY KEY (SNAPSHOT_ID) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_SNAP_ASM UNIQUE (ASSESSMENT_ID) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_SNAP_PARENT_ID UNIQUE (ASSESSMENT_ID, SNAPSHOT_ID)
        USING INDEX TABLESPACE USERS,
    CONSTRAINT FK_RSK_SNAP_ASM FOREIGN KEY (ASSESSMENT_ID)
        REFERENCES D2_RISK.RISK_ASSESSMENTS (ASSESSMENT_ID),
    CONSTRAINT CK_RSK_SNAP_IDS CHECK (
        SNAPSHOT_ID BETWEEN 1 AND 9223372036854775807
        AND SOURCE_VERSION_ID BETWEEN 1 AND 9223372036854775807
        AND SOURCE_ROW_VERSION BETWEEN 0 AND 9223372036854775807
        AND (SOURCE_VERSION_NUMBER IS NULL OR SOURCE_VERSION_NUMBER BETWEEN 1 AND 2147483647)),
    CONSTRAINT CK_RSK_SNAP_TEXT CHECK (
        TRIM(SNAPSHOT_FORMAT_VERSION) IS NOT NULL AND TRIM(HASH_FORMAT_VERSION) IS NOT NULL),
    CONSTRAINT CK_RSK_SNAP_JSON CHECK (CONFIGURATION_JSON IS JSON STRICT),
    CONSTRAINT CK_RSK_SNAP_HASH CHECK (REGEXP_LIKE(CONTENT_HASH, '^[0-9a-f]{64}$', 'c'))
) TABLESPACE USERS;

CREATE TABLE D2_RISK.RISK_INPUT_EVIDENCE (
    EVIDENCE_ID NUMBER(19,0) GENERATED ALWAYS AS IDENTITY
        (START WITH 1 INCREMENT BY 1 MAXVALUE 9223372036854775807 NOCYCLE),
    ASSESSMENT_ID NUMBER(19,0) NOT NULL,
    RECORD_CLASS VARCHAR2(24 CHAR) NOT NULL,
    ATTEMPT_NO NUMBER(10,0) NOT NULL,
    EVIDENCE_KEY VARCHAR2(128 CHAR) NOT NULL,
    EVIDENCE_KIND VARCHAR2(24 CHAR) NOT NULL,
    SOURCE_SERVICE VARCHAR2(64 CHAR) NOT NULL,
    SOURCE_RECORD_REFERENCE VARCHAR2(128 CHAR),
    SOURCE_SNAPSHOT_REFERENCE VARCHAR2(128 CHAR),
    SOURCE_REVISION VARCHAR2(128 CHAR),
    EVIDENCE_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    HASH_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    AS_OF_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    WINDOW_START_AT TIMESTAMP(9) WITH TIME ZONE,
    WINDOW_END_AT TIMESTAMP(9) WITH TIME ZONE,
    RECORD_COUNT NUMBER(10,0),
    AVAILABILITY VARCHAR2(16 CHAR),
    EVIDENCE_JSON CLOB NOT NULL,
    CONTENT_HASH CHAR(64) NOT NULL,
    CAPTURED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT PK_RSK_EVD PRIMARY KEY (EVIDENCE_ID) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_EVD_KEY UNIQUE (ASSESSMENT_ID, RECORD_CLASS, ATTEMPT_NO, EVIDENCE_KEY)
        USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_EVD_PARENT_ID UNIQUE (ASSESSMENT_ID, EVIDENCE_ID)
        USING INDEX TABLESPACE USERS,
    CONSTRAINT FK_RSK_EVD_ASM FOREIGN KEY (ASSESSMENT_ID)
        REFERENCES D2_RISK.RISK_ASSESSMENTS (ASSESSMENT_ID),
    CONSTRAINT CK_RSK_EVD_ID CHECK (EVIDENCE_ID BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT CK_RSK_EVD_TEXT CHECK (
        TRIM(EVIDENCE_KEY) IS NOT NULL AND TRIM(SOURCE_SERVICE) IS NOT NULL
        AND TRIM(EVIDENCE_FORMAT_VERSION) IS NOT NULL AND TRIM(HASH_FORMAT_VERSION) IS NOT NULL
        AND (SOURCE_RECORD_REFERENCE IS NULL OR TRIM(SOURCE_RECORD_REFERENCE) IS NOT NULL)
        AND (SOURCE_SNAPSHOT_REFERENCE IS NULL OR TRIM(SOURCE_SNAPSHOT_REFERENCE) IS NOT NULL)
        AND (SOURCE_REVISION IS NULL OR TRIM(SOURCE_REVISION) IS NOT NULL)),
    CONSTRAINT CK_RSK_EVD_CLASS CHECK (
        (RECORD_CLASS = 'INPUT' AND ATTEMPT_NO = 0 AND EVIDENCE_KIND IN
            ('CURRENT_INPUT', 'PAYMENT_HISTORY', 'SUPPLIER', 'SUPPLIER_TRANSITIONS', 'PRIOR_ASSESSMENTS')) OR
        (RECORD_CLASS = 'MANIFEST' AND ATTEMPT_NO = 0
         AND EVIDENCE_KEY = 'ENGINE_INPUT' AND EVIDENCE_KIND = 'INPUT_MANIFEST') OR
        (RECORD_CLASS = 'ATTEMPT_DIAGNOSTIC' AND ATTEMPT_NO BETWEEN 1 AND 2147483647
         AND EVIDENCE_KEY = 'RESULT' AND EVIDENCE_KIND = 'ATTEMPT_RESULT')),
    CONSTRAINT CK_RSK_EVD_AVAIL CHECK (
        (RECORD_CLASS = 'ATTEMPT_DIAGNOSTIC' AND AVAILABILITY IS NULL AND RECORD_COUNT IS NULL) OR
        (RECORD_CLASS IN ('INPUT', 'MANIFEST') AND AVAILABILITY IS NOT NULL AND
            ((AVAILABILITY = 'COMPLETE' AND RECORD_COUNT IS NOT NULL
              AND SOURCE_SNAPSHOT_REFERENCE IS NOT NULL) OR
             (RECORD_CLASS = 'INPUT' AND AVAILABILITY IN ('UNAVAILABLE', 'INCOMPLETE'))))),
    CONSTRAINT CK_RSK_EVD_COUNT CHECK
        (RECORD_COUNT IS NULL OR RECORD_COUNT BETWEEN 0 AND 2147483647),
    CONSTRAINT CK_RSK_EVD_WINDOW CHECK (
        (WINDOW_START_AT IS NULL AND WINDOW_END_AT IS NULL) OR
        (WINDOW_START_AT IS NOT NULL AND WINDOW_END_AT IS NOT NULL
         AND WINDOW_END_AT >= WINDOW_START_AT AND WINDOW_END_AT <= AS_OF_AT)),
    CONSTRAINT CK_RSK_EVD_CAPTURE CHECK (CAPTURED_AT >= AS_OF_AT),
    CONSTRAINT CK_RSK_EVD_JSON CHECK (EVIDENCE_JSON IS JSON STRICT),
    CONSTRAINT CK_RSK_EVD_HASH CHECK (REGEXP_LIKE(CONTENT_HASH, '^[0-9a-f]{64}$', 'c'))
) TABLESPACE USERS;

CREATE TABLE D2_RISK.RISK_ASSESSMENT_FACTORS (
    FACTOR_ID NUMBER(19,0) GENERATED ALWAYS AS IDENTITY
        (START WITH 1 INCREMENT BY 1 MAXVALUE 9223372036854775807 NOCYCLE),
    ASSESSMENT_ID NUMBER(19,0) NOT NULL,
    RULE_CODE VARCHAR2(64 CHAR) NOT NULL,
    RULE_CONFIG_SOURCE_ID NUMBER(19,0),
    FACTOR_ORDER NUMBER(5,0) NOT NULL,
    EVALUATION_OUTCOME VARCHAR2(24 CHAR) NOT NULL,
    CONFIGURED_WEIGHT NUMBER(10,0) NOT NULL,
    SCORE_CONTRIBUTION NUMBER(10,0) NOT NULL,
    PRIMARY_EVIDENCE_ID NUMBER(19,0),
    EXPLANATION VARCHAR2(2000 CHAR) NOT NULL,
    RESULT_FORMAT_VERSION VARCHAR2(32 CHAR) NOT NULL,
    DETAILS_JSON CLOB NOT NULL,
    EVALUATED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    CONSTRAINT PK_RSK_FAC PRIMARY KEY (FACTOR_ID) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_FAC_CODE UNIQUE (ASSESSMENT_ID, RULE_CODE) USING INDEX TABLESPACE USERS,
    CONSTRAINT UK_RSK_FAC_ORDER UNIQUE (ASSESSMENT_ID, FACTOR_ORDER) USING INDEX TABLESPACE USERS,
    CONSTRAINT FK_RSK_FAC_ASM FOREIGN KEY (ASSESSMENT_ID)
        REFERENCES D2_RISK.RISK_ASSESSMENTS (ASSESSMENT_ID),
    CONSTRAINT FK_RSK_FAC_EVD FOREIGN KEY (ASSESSMENT_ID, PRIMARY_EVIDENCE_ID)
        REFERENCES D2_RISK.RISK_INPUT_EVIDENCE (ASSESSMENT_ID, EVIDENCE_ID),
    CONSTRAINT CK_RSK_FAC_IDS CHECK (
        FACTOR_ID BETWEEN 1 AND 9223372036854775807
        AND (RULE_CONFIG_SOURCE_ID IS NULL OR RULE_CONFIG_SOURCE_ID BETWEEN 1 AND 9223372036854775807)),
    CONSTRAINT CK_RSK_FAC_CODE CHECK (RULE_CODE IN
        ('STRUCTURING', 'UNUSUAL_FREQUENCY', 'UNUSUAL_AMOUNT', 'RECENTLY_REACTIVATED_SUPPLIER',
         'PREVIOUS_RISK_FLAGS', 'NEW_SUPPLIER', 'NEAR_DUPLICATE_INVOICE')),
    CONSTRAINT CK_RSK_FAC_ORDER CHECK (FACTOR_ORDER BETWEEN 1 AND 99999),
    CONSTRAINT CK_RSK_FAC_TEXT CHECK
        (TRIM(EXPLANATION) IS NOT NULL AND TRIM(RESULT_FORMAT_VERSION) IS NOT NULL),
    CONSTRAINT CK_RSK_FAC_SCORES CHECK
        (CONFIGURED_WEIGHT BETWEEN 0 AND 2147483647 AND SCORE_CONTRIBUTION BETWEEN 0 AND 2147483647),
    CONSTRAINT CK_RSK_FAC_OUTCOME CHECK (
        (EVALUATION_OUTCOME = 'TRIGGERED' AND SCORE_CONTRIBUTION = CONFIGURED_WEIGHT
         AND PRIMARY_EVIDENCE_ID IS NOT NULL) OR
        (EVALUATION_OUTCOME IN ('NOT_TRIGGERED', 'INSUFFICIENT_HISTORY')
         AND SCORE_CONTRIBUTION = 0 AND PRIMARY_EVIDENCE_ID IS NOT NULL) OR
        (EVALUATION_OUTCOME = 'DISABLED' AND SCORE_CONTRIBUTION = 0 AND PRIMARY_EVIDENCE_ID IS NULL)),
    CONSTRAINT CK_RSK_FAC_JSON CHECK (DETAILS_JSON IS JSON STRICT)
) TABLESPACE USERS;

-- Back-references enforce physical snapshot/manifest existence when non-null.
-- New parent insertion first uses NULL references; the acceptance/freeze tx sets
-- them after inserting children. Completed state requires both references.
-- No deferred commit, cascade or deletion behavior is introduced.
ALTER TABLE D2_RISK.RISK_ASSESSMENTS ADD CONSTRAINT FK_RSK_ASM_SNAP
    FOREIGN KEY (ASSESSMENT_ID, CONFIGURATION_SNAPSHOT_ID)
    REFERENCES D2_RISK.RISK_CONFIGURATION_SNAPSHOTS (ASSESSMENT_ID, SNAPSHOT_ID);
ALTER TABLE D2_RISK.RISK_ASSESSMENTS ADD CONSTRAINT FK_RSK_ASM_INPUT
    FOREIGN KEY (ASSESSMENT_ID, FROZEN_INPUT_EVIDENCE_ID)
    REFERENCES D2_RISK.RISK_INPUT_EVIDENCE (ASSESSMENT_ID, EVIDENCE_ID);

CREATE INDEX D2_RISK.IX_RSK_SUPPLIER_HISTORY
    ON D2_RISK.RISK_ASSESSMENTS (SUPPLIER_ID, WORK_STATE, COMPLETED_AT) TABLESPACE USERS;
CREATE INDEX D2_RISK.IX_RSK_RECOVERY
    ON D2_RISK.RISK_ASSESSMENTS (WORK_STATE, LEASE_EXPIRES_AT) TABLESPACE USERS;
CREATE INDEX D2_RISK.IX_RSK_PAYMENT
    ON D2_RISK.RISK_ASSESSMENTS (PAYMENT_ID) TABLESPACE USERS;
CREATE INDEX D2_RISK.IX_RSK_ASM_SNAP
    ON D2_RISK.RISK_ASSESSMENTS (ASSESSMENT_ID, CONFIGURATION_SNAPSHOT_ID) TABLESPACE USERS;
CREATE INDEX D2_RISK.IX_RSK_ASM_INPUT
    ON D2_RISK.RISK_ASSESSMENTS (ASSESSMENT_ID, FROZEN_INPUT_EVIDENCE_ID) TABLESPACE USERS;
CREATE INDEX D2_RISK.IX_RSK_FAC_EVD
    ON D2_RISK.RISK_ASSESSMENT_FACTORS (ASSESSMENT_ID, PRIMARY_EVIDENCE_ID) TABLESPACE USERS;

-- END REVIEW-ONLY PROPOSAL. Nothing above has been executed by its preparation.
