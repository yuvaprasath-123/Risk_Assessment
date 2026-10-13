# Developer 2 working plan

Status: planning only. Prepared 12 October 2026. No application implementation is authorized by this document.

## Sources and authority

- Business requirements: [Scope Group 06.pdf](<C:/Users/CHANDANA K P/Downloads/Scope Group 06.pdf>), especially sections 1.5, 2, 3, 4, and 5.
- Service ownership and proposed tables/APIs: [Supplier Payment Microservice Blueprint](<C:/Users/CHANDANA K P/Downloads/Supplier_Payment_Microservice_Blueprint.docx>), Developer 2 sections 4-6.
- Environment evidence: version checks, inspection of the existing service POMs, and compilation results from this working session.
- Database environment evidence supplied and verified by the user: Oracle AI Database 26ai Free, version 23.26.2.0.0, at localhost:1521 with service/container FREEPDB1. SYSTEM access works for setup. D2_CONFIGURATION, D2_VALIDATION, and D2_RISK have been created with the quotas and privileges recorded below; all three SQL Developer connections passed. This planning update does not independently connect to the database. Application/JDBC connectivity remains unverified.
- Q&A material is excluded. Blueprint design suggestions do not override scope requirements. Demonstration defaults below are proposals, not approved requirements.

## Verified environment and limits

| Component | Evidence and status |
|---|---|
| Java | Selected JDK is `C:\jdk21`; `java` and `javac` report 21.0.12.1. Selected executables: `C:\jdk21\bin\java.exe` and `C:\jdk21\bin\javac.exe`. |
| Spring Boot | Both existing service POMs declare parent version 4.1.1 and Java target 21. Use this version as the proposed baseline for Developer 2 services. |
| Installed Maven | `mvn -v` reports 3.9.16 from `C:\apache-maven-3.9.16`, running with Oracle Java 21.0.12.1 at `C:\jdk21`. |
| Required database | Oracle AI Database 26ai Free, reported version 23.26.2.0.0. Product name and reported version are recorded separately; do not interpret the version prefix as a requirement to use a different product. User-verified host: localhost; port: 1521; service/container: FREEPDB1. |
| Database setup access | User verified that SYSTEM access works for setup. SYSTEM is a setup account, not a proposed application identity. |
| Service schema provisioning | User verified creation of D2_CONFIGURATION, D2_VALIDATION, and D2_RISK in FREEPDB1. Each has a 100 MB quota on USERS and CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, and CREATE PROCEDURE privileges. |
| Database connection verification | All three service schema connections were successfully tested in SQL Developer by the user. Application/JDBC connectivity and persistence behavior remain unverified; SQL Developer login success does not establish application integration. |
| Compilation | Installed Maven successfully ran `mvn "-Dmaven.test.skip=true" compile` separately for payment-request-service (28 source files) and supplier-invoice-service (26 source files), both targeting Java 21. These results concern existing services only. |
| Tests | Not executed in this session; pass/fail status is unverified. Successful compilation is not evidence of database connectivity or correct business behavior. |
| Maven Wrapper | Both existing wrappers configure Maven 3.9.16, but `mvnw.cmd -v` failed with `Cannot index into a null array` and `Cannot start maven from wrapper`. Actual wrapper execution remains unresolved. |
| Existing warning | supplier-invoice-service declares `org.owasp:dependency-check-maven` without a plugin version. Preserve this teammate-owned POM; refer the warning to its owner. |

There are two implemented Maven services in this checkout: payment-request-service and supplier-invoice-service. security-service is an empty folder. The three Developer 2 services are not implemented here yet.

## Service boundaries and proposed table ownership

The table names below come from the blueprint. The three schema accounts are provisioned as recorded below; physical table names, keys, columns, indexes, constraints, and migration conventions remain design decisions. Schema creation is not business-table implementation.

| Developer 2 service | Responsibilities | Proposed owned tables | Boundary |
|---|---|---|---|
| payment-validation-service | Evaluate mandatory data, supplier activity, invoice/payment consistency once defined, and exact duplicate payment submissions; persist pass/fail and individual reasons. Invalid requests do not proceed to risk or approval. | `validation_results`, `validation_checks` | Does not own supplier/invoice masters, payment lifecycle, risk scoring, or approval decisions. |
| risk-assessment-service | Assess eligible submitted payments once; add independent rule contributions; produce score, band, explanations, and supporting evidence; retain the configuration actually used. | `risk_assessments`, `risk_assessment_factors`, `risk_configuration_snapshots` | Does not reject on risk band alone, execute approval routing, process payments, or continuously rescore. |
| business-configuration-service | Maintain versioned rule settings, bands, approval amount thresholds, SLA settings, and activation state. | `configuration_versions`, `risk_rule_configs`, `risk_band_configs`, `approval_threshold_configs`, `sla_configs` | Owns configuration, not approval tasks, escalation execution, or backup approver identities/mappings. |

Developer 1 owns security, supplier/invoice data, and payment request/status history. Developer 3 owns approval workflow, simulated processing, and append-only business audit. Keep references to their IDs; do not write their tables directly. Avoid cross-service foreign keys unless the team explicitly approves a shared-schema model.

Assessment evidence should include evaluated payment data, relevant historical observations, evaluation time, rule contributions, configuration version, and rule/evaluator version. Configuration alone cannot reproduce a decision after supplier or payment history changes. The storage model for that evidence needs agreement at milestone 1.

### Provisioned Oracle schemas and proposed runtime separation

User-verified setup: three separate local schema owners now exist in FREEPDB1 on the same database. All three SQL Developer connections were successfully tested. Each account has a 100 MB quota on USERS and the four privileges shown below. No business-table creation or application/JDBC verification is established by this report. No cross-service table access is proposed; use the agreed service APIs/events.

Connection endpoint for future application verification: `localhost:1521/FREEPDB1`. Proposed JDBC service URL: `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`. No connection has been attempted as part of this update.

| Service | Provisioned schema owner | Planned owned objects | User-verified privileges and quota |
|---|---|---|---|
| payment-validation-service | `D2_VALIDATION` | `validation_results`, `validation_checks`, their constraints/indexes, and optional explicit ID sequences | `CREATE SESSION`, `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE PROCEDURE`; 100 MB on USERS. |
| risk-assessment-service | `D2_RISK` | `risk_assessments`, `risk_assessment_factors`, `risk_configuration_snapshots`, their constraints/indexes, and optional explicit ID sequences | `CREATE SESSION`, `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE PROCEDURE`; 100 MB on USERS. |
| business-configuration-service | `D2_CONFIGURATION` | `configuration_versions`, `risk_rule_configs`, `risk_band_configs`, `approval_threshold_configs`, `sla_configs`, their constraints/indexes, and optional explicit ID sequences | `CREATE SESSION`, `CREATE TABLE`, `CREATE SEQUENCE`, `CREATE PROCEDURE`; 100 MB on USERS. |

The owner can manipulate its own tables without object grants to itself. Ordinary indexes on its own tables do not require a blanket `CREATE ANY INDEX` grant. CREATE SEQUENCE and CREATE PROCEDURE are already granted for future own-schema sequences and PL/SQL procedures/packages; their presence does not require using these objects. Add CREATE VIEW or CREATE TRIGGER only if an approved design requires them. USERS and the 100 MB quotas are resolved setup facts, not remaining M1 decisions.

For runtime separation, propose optional login users `D2_VALIDATION_APP`, `D2_RISK_APP`, and `D2_CONFIGURATION_APP`. They add no separately owned application tables. Each needs `CREATE SESSION` and specific object privileges on its corresponding owner's objects, not table-creation or migration privileges:

| Runtime identity | Proposed object privileges | Qualification |
|---|---|---|
| `D2_VALIDATION_APP` | `SELECT`, `INSERT` on validation results/checks; narrowly scoped `UPDATE` if mutable attempt/outcome fields are approved | No `DELETE` by default. Finalized check evidence should remain immutable; settle insert-only versus pending-to-final updates at M1. |
| `D2_RISK_APP` | `SELECT`, `INSERT` on assessments, factors, snapshots; narrowly scoped `UPDATE` only if an approved pending-to-final model requires it | No `DELETE` by default. Finalized assessment evidence must not be overwritten. |
| `D2_CONFIGURATION_APP` | `SELECT`, `INSERT` on configuration tables; scoped `UPDATE` for editable draft fields and activation/effective state | No `DELETE` by default. Retain historical versions. Table-level UPDATE alone cannot distinguish draft from active rows; enforce lifecycle rules through the service and, if required, a reviewed database access design. |

If explicit sequences are used, the corresponding runtime user needs `SELECT` on those named sequences to obtain generated values. Identity-based IDs should be reviewed separately. Grant no runtime user access to another service's schema or teammate-owned data. The schema-qualified mapping/default-schema choice remains an implementation decision.

Using the owner as the runtime login is a simpler prototype option but retains the owner's inherent DDL and broad object control; revoking CREATE TABLE does not make existing owned objects read-only. Separate runtime identities are therefore recommended when enforcing privilege separation. Neither option is yet approved.

Application identities do not need SYSTEM credentials, `DBA`, `RESOURCE`, `CREATE USER`, `ANY` privileges, `UNLIMITED TABLESPACE`, or privileges on system/dictionary objects. Provisioning of the three owner accounts and their recorded grants/quotas is complete according to the user. Any future runtime-account provisioning is a separate action. Administrative account privileges are not inherited by applications.

Privilege references: Oracle's [CREATE TABLE prerequisites](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-TABLE.html) specify CREATE TABLE for own-schema creation and storage quota; its [privilege and role guide](https://docs.oracle.com/en/database/oracle/oracle-database/26/dbseg/configuring-privilege-and-role-authorization.html) distinguishes system/object privileges and local PDB grants. Runtime permissions above are proposed from the planned service operations, not an Oracle-mandated application role.

## Integration contracts

### Existing APIs verified in code

| Provider | Existing contract | Usage and limitation |
|---|---|---|
| Developer 1 supplier-invoice-service | `GET /api/v1/suppliers/{supplierId}` | Returns supplier ID, name, status, creation/update timestamps. Supports activity and possible new-supplier checks. |
| Developer 1 supplier-invoice-service | `GET /api/v1/suppliers/{supplierId}/status-history` | Returns status changes and times for detecting INACTIVE-to-ACTIVE reactivation. |
| Developer 1 supplier-invoice-service | `GET /api/v1/invoices/{invoiceId}` | Returns supplier ID, invoice number, amount, invoice/due dates, creation/update timestamps. No quantity or account fields. |
| Developer 1 supplier-invoice-service | `GET /api/v1/invoices?supplierId={id}` | Lists supplier invoices. The blueprint's additional invoice-number filter is not implemented by the current controller. |
| Developer 1 supplier-invoice-service | `GET /api/v1/invoices/duplicates/exact?supplierId={id}&invoiceNumber={number}` | Checks whether a master invoice exists. Cannot alone detect a duplicate payment submission: a valid request already references an existing invoice. |
| Developer 1 supplier-invoice-service | `GET /api/v1/invoices/duplicates/near?supplierId={id}&amount={amount}` | Finds invoices with exactly equal supplier and amount; includes the referenced invoice. No tolerance or date-window semantics. |
| Developer 1 payment-request-service | `POST /api/v1/payment-requests/{paymentId}/submit` | Saves PENDING_VALIDATION after an activity check. No validation invocation/event or downstream handoff currently exists. |
| Developer 1 payment-request-service | `GET /api/v1/payment-requests/{paymentId}`; list with optional supplierId/status; `GET /{paymentId}/timeline` under the same base path | Reads are requester-role protected and requester-owned. Not suitable for service-wide historical analysis without a new internal authorization contract. |

Existing creation already rejects inactive suppliers and a second payment request for an invoice. A database uniqueness constraint also enforces one request per invoice. Current draft updates change amount only; creation and submission do not compare payment amount with invoice amount. Risk score, band, and required approval level fields exist but no downstream result-update API exists.

### Proposed Developer 2 APIs from the blueprint

These are planned interfaces, not existing endpoints. Final payloads and authorization require agreement.

| Owner | Proposed API families | Required contract content |
|---|---|---|
| Validation | `POST /api/v1/payment-validations`; `GET /api/v1/payment-validations/{paymentId}` and `/checks` | Payment/submission identity, authoritative input reference or snapshot, overall outcome, check codes, reasons, timestamps, correlation ID. Internal mandatory-fields, supplier-status, and duplicate checks are also blueprint proposals; decide whether they need independently exposed endpoints. |
| Risk | `POST /api/v1/risk-assessments`; assessment lookup by assessmentId and `/payment/{paymentId}`; score-breakdown, factors, configuration-snapshot | Validation eligibility, payment identity, assessment ID, total score/band, rule contributions and evidence, effective configuration version, evaluation timestamp. Internal risk-check endpoints are optional blueprint interface proposals. |
| Configuration | `/api/v1/configuration/risk-rules`, `/risk-bands`, `/approval-thresholds`, `/approval-slas`, `/versions`, version activation | Read/write role policy, full version identity, coherent version content, validation errors, activation time and concurrency behavior. Separate rule enable/disable is proposed. |

### Proposed cross-team additions and handoffs

None of these additions is implemented in the existing services.

| Parties | Contract to agree | Ownership and expected behavior |
|---|---|---|
| Developer 1 -> Developer 2 | Submission handoff by HTTP or event | Developer 1 emits/invokes once after submission. Identify payment and submission attempt, requester, submitted time, correlation ID, and data revision. Agree on transaction/retry semantics. |
| Developer 1 -> Developer 2 | Internal payment detail, duplicate eligibility, and supplier payment-history reads | Developer 1 exposes authorized reads across requesters, excluding the current request where required. Support time windows, eligible statuses, pagination/aggregation, and deterministic as-of time. Do not reuse unrestricted requester endpoints. |
| Developer 2 -> Developer 1 | Validation result and risk summary callback/event | Payment owner records validation rejection or progression, risk score/band, assessment reference, and status history. No direct writes by Developer 2. Define how technical outages differ from business rejection. |
| Developer 2 -> Developer 3 | Validated assessment handoff | Deliver payment amount, requester, risk band/score, assessment and configuration references. Developer 3 calculates `MAX(amount tier level, risk band level)`, assigns authorized approvers, and prevents self-approval. |
| Configuration -> Developer 3 | Versioned approval thresholds and SLA reads | Supply exact version used. Developer 3 owns approval deadlines, escalation, backup selection, decisions, and manager override. Agree whether risk/routing/SLA share one pinned version. |
| Developer 2 -> Developer 3 | Audit submission | Blueprint proposes `POST /api/v1/audit-events`; not present locally. Include event ID/type, payment/configuration/assessment references, actor or service identity, time, old/new outcome, reasons, and correlation ID. Agree retry and deduplication. |
| Developer 2 with Developers 1/3 | Previous supplier risk evidence | Developer 2 owns past assessment results; agree how prior flags are queried and whether resolved/overridden flags count. Developer 3 audit provides lifecycle/decision context when required. |
| Developer 1 security -> all services | User and service authentication contract | Current payment JWT uses userId and roles. Define trusted issuer/signing policy, internal service identity, history-read permissions, and configuration administration roles; do not impersonate a requester. |

Specify shared IDs and money precision, date/time zone, error schema, HTTP/event versioning, timeouts, idempotency keys, retry eligibility, and configuration pinning before wiring these contracts. Example string IDs in scope diagrams are illustrative; current application IDs are Long values.

## Decision register

### Confirmed requirements

| ID | Requirement | Authority |
|---|---|---|
| C01 | Developer 2 owns validation, risk assessment, and business configuration. | Blueprint ownership |
| C02 | Block incomplete submissions, inactive suppliers, and exact duplicate supplier/invoice submissions before approval; valid submissions undergo assessment. | Scope sections 2, 3, 5.1 |
| C03 | Use explainable, independent, additive rule scoring; weights, bands, thresholds, and SLA durations are configurable. Assess once, not continuously. | Scope sections 1.5, 5.3 |
| C04 | Cover structuring, unusual frequency, unusual amount, recently reactivated suppliers, and previous risk flags. | Scope sections 3.1, 5.1 |
| C05 | Risk band feeds routing, not automatic risk-only rejection. Final approval level uses MAX(amount tier, risk band level). | Scope sections 1.5, 2.2 and approval matrix |
| C06 | Required authority, no self-approval, mandatory rejection remarks, pending-approval SLA escalation, immutable important payment details after final approval, and auditable decisions. | Scope sections 2.4, 3; implementation primarily Developers 1/3 |
| C07 | Synthetic data, one organization, simulated processing; no live banking/ERP, ML, correlated rules, continuous rescoring, tax/GST/full accounting, or full supplier onboarding. | Scope sections 1.5, 4, 5 |
| C08 | Oracle AI Database 26ai Free, version 23.26.2.0.0, localhost:1521, service/container FREEPDB1 is the required and user-verified database environment. SYSTEM setup access works. | User-verified environment; application/JDBC connectivity remains unverified |
| C09 | D2_CONFIGURATION, D2_VALIDATION, and D2_RISK are created in FREEPDB1, each with 100 MB on USERS and CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE PROCEDURE. All three SQL Developer connections passed. | User-verified setup and connection results; business tables and application integration are not established |

### Unresolved questions

Priority M1 means the answer affects the first database-design milestone. Later means it must be settled before the relevant implementation/integration is accepted.

| ID | Decision required | Priority / participants |
|---|---|---|
| U01 | Schema names, FREEPDB1 placement, USERS tablespace, 100 MB quotas, and the four owner privileges are resolved by provisioning. Remaining: owner/runtime separation, any runtime object grants, physical naming, keys, sequence/identity convention, and migration mechanism. Never assume permission to execute further DDL. | M1 / team |
| U02 | Payment/submission/assessment identity and cardinality: one validation/assessment per submitted request or separate attempts; uniqueness and retention; corrected-request identity. Scope allows a rejected invoice to return through a new request, while existing one-request-per-invoice rules prevent this. | M1 / Developers 1/2 |
| U03 | Duplicate semantics: invoice existence versus prior eligible payment submission; status eligibility, current-record exclusion, concurrent submission protection, invoice-number normalization. Exact duplicates must be blocked, not merely scored. | M1 / Developers 1/2 |
| U04 | Rule inventory: whether new-supplier and near-duplicate are accepted rules. Scope figure 2.2 shows duplicate +40, structuring +35, new vendor +25, but exact duplicate scoring conflicts with validation blocking. Confirm whether the diagram is illustrative and what 'duplicate' means there. | M1 inventory; numeric values later / business owner, Developer 2 |
| U05 | Rule definitions and historical input shape: structuring threshold/window; frequency threshold/window; unusual-amount baseline/minimum observations; prior-flag meaning and retention; reactivation/new-supplier windows; near-duplicate tolerance/window; eligible statuses, aggregation clock, and current-payment inclusion. | M1 data shape; final settings before risk implementation / Developers 1/2 and business owner |
| U06 | Score range, cap/normalization and band boundaries. Diagram proposes 0-100, Low <30, Medium 30-60, High >60; confirm approved initial values and totals above 100. | Before configuration/risk acceptance / business owner |
| U07 | Payment amount versus invoice amount: full/partial payment policy and quantity/vendor/account mismatch checks. Quantity/account data are absent from existing DTOs. Agree which fields belong in the prototype. | M1 / Developer 1 and business owner |
| U08 | Evidence and configuration persistence: immutable activated versions, snapshot granularity, input/history evidence, evaluator version, outcome status, audit linkage, and retention. Who can edit/activate versions? | M1 storage model; permissions before configuration acceptance / team |
| U09 | One assessment trigger and workflow coordinator; HTTP versus events, callback/status ownership, timeout/retry/idempotency behavior, partial failures, and missing historical data policy. Outages must not silently pass validation. | M1 IDs/outcome shape; transport before integration / team |
| U10 | Approval amount thresholds; L1 automatic versus human approval; SLA start time (workflow prose says after approval, escalation applies to pending approval), backup priority, terminal L3 behavior, and version pinning. | SLA/configuration shape M1; behavior before integration / Developer 3, business owner |
| U11 | Service authentication, configuration administration, internal history access, error contracts, audit guarantees, and UI access to explanations. | Before integration / Developers 1/2/3 |
| U12 | Host, port, FREEPDB1, SYSTEM setup access, all three owner accounts, quotas/grants, and SQL Developer logins are resolved. Remaining: secure application credential delivery, JDBC choice, application/JDBC connectivity and persistence verification, optional runtime identity provisioning, and isolated verification schema. No passwords belong in this plan. | Before database integration verification / team |

### Proposed configurable demonstration defaults

These values are discussion starters only. Do not encode them as approved policy or activate them without agreement. Keep a clearly labeled demonstration version separate from accepted business configuration. Currency/unit is deliberately unset pending agreement.

| ID | Proposed demonstration setting | Qualification |
|---|---|---|
| D01 | Bands: Low <30; Medium 30-60 inclusive; High >60; cap additive score at 100. | Mirrors diagram boundaries, but cap is a proposal. Validate non-overlapping, complete bands. |
| D02 | Structuring: 7-day window, at least two related payments, each below the relevant approval amount threshold and combined total above it; +35. | Window/count are proposals; +35 is shown in the diagram, not confirmed initial policy. Threshold comes from configuration. |
| D03 | Unusual frequency: more than 3 eligible payments in 7 days; +15. | Entirely proposed; define eligible statuses and current-payment inclusion first. |
| D04 | Unusual amount: greater than twice the mean of at least 5 completed supplier payments within 90 days; +20. | Proposed baseline. Insufficient history must be explicitly reported, not silently interpreted as low risk. |
| D05 | Recently reactivated: INACTIVE-to-ACTIVE within 30 days; +15. Previous triggered risk flags within 90 days: +10. | Proposed; define whether overrides/resolutions change prior-flag eligibility. |
| D06 | New supplier: created within 30 days; +25. Near duplicate: another invoice within 30 days and amount within 1%; +20. | Optional rules pending U04. Exclude the referenced invoice. +25 appears in the diagram; remaining values are proposals. |
| D07 | Approval pending SLA: 24 hours, with configurable durations per level. | Proposed duration only. Developer 3 must settle timing, backup/next-level priority, and terminal behavior. |
| D08 | Exact duplicate is a validation failure with a reason code and zero risk-scoring role. | Blocking is confirmed; the absence of a scoring role follows that boundary. Do not adopt the diagram's +40 for an exact duplicate. |

Do not seed arbitrary approval monetary thresholds, currency assumptions, timeout-success behavior, or resubmission identity. Numeric configuration can be finalized after a generic storage design, but data and lifecycle semantics must be agreed first.

## Implementation milestones and meaningful verification

All verification below is future work. No tests, services, or database scripts are run to produce this plan. Teammate-owned changes are coordinated with their owners rather than applied unilaterally.

| Milestone | Deliverables and completion criteria | Meaningful verification |
|---|---|---|
| M1 Database design | Resolve structural decisions listed below. Produce Developer 2 ERD/data dictionary and reviewed Oracle Database 26 DDL/migration drafts only. Define table ownership, keys, cardinality, immutable evidence/version storage, decimal/time types, indexes, unique attempt constraints, and check/result statuses. No direct writes to teammate tables. | Review against valid/rejected submissions, repeated delivery, rejected-request resubmission, simultaneous activation, historical reproduction, and supplier history queries. Demonstrate in design that duplicates cannot create two accepted assessments and historical versions cannot be overwritten. Oracle execution remains a later explicit step. |
| M2 Configuration service | Implement version-coherent reads, validated edits, enable/disable behavior, activation/authorization, and retained historical versions. Require complete, valid rule/band/approval/SLA configuration before activation. | Boundary checks for bands and thresholds; reject gaps/overlaps, negative windows/weights, invalid SLAs, incomplete versions and unauthorized activation. Verify two concurrent activation attempts cannot produce competing current versions and an old assessment retains its original version. Oracle-backed persistence checks require an approved isolated database. |
| M3 Validation service | Implement agreed mandatory/invoice/supplier/duplicate checks, per-check evidence and reasons, idempotent submission handling, and distinction between business failure and dependency failure. | Valid request passes; inactive supplier, missing field and exact duplicate are blocked; current invoice/request is not mistaken for a duplicate. Verify concurrent repeated delivery, resubmission policy, changed supplier status, mismatched amounts, and provider timeout behavior. |
| M4 Risk assessment service | Implement accepted rules against a pinned input/configuration snapshot; additive explanations, band assignment, single-assessment semantics, supplier-history inputs, and immutable evidence. | Deterministic fixtures covering every rule and boundary; verify each contribution equals the stored explanation, totals/bands match configuration, current-payment inclusion is correct, insufficient history is explicit, and re-delivery does not rescore. Reproduce an old decision after configuration and source history change. |
| M5 Integration | Agree and implement Developer 2 contracts plus teammate-owned counterpart changes by their owners. Complete submission -> validation -> assessment -> approval handoff -> audit, with authorized internal access and recoverable failure behavior. | Contract checks against real DTO/error shapes; demonstrate valid and rejected paths, same invoice detection, cross-requester history, MAX routing with Developer 3, missed/duplicate messages or retries, configuration changes in flight, and audit reconstruction. Confirm no real payment execution and no unauthorized status mutation. |
| M6 Evaluation preparation | Prepare requirement traceability, synthetic scenarios, seeded demonstration configuration clearly labeled with its approval status, deployment/run instructions, known limitations, and evidence of verified behavior. | Demonstrate baseline valid payment, inactive supplier, duplicate, structuring, each accepted risk factor, low/medium/high boundaries, configuration version changes, and downstream rejection/escalation/retry with Developer 3. Record observed results and evidence; list any unverified database, security, transport, or wrapper behavior without claiming completion. |

Future verification should test business properties and integrations rather than merely mirror getters or method implementations. Compile success, isolated rule tests, contract checks, and Oracle persistence verification provide different evidence and must be reported separately.

## Decisions necessary for the first milestone

M1 can begin as a draft, but its design is not complete until the team agrees on:

1. **Storage ownership and Oracle conventions (U01):** build on the provisioned three local schemas in FREEPDB1 with 100 MB each on USERS and the recorded owner privileges. Decide owner/runtime separation, runtime object grants if applicable, physical names, ID generation, decimal precision, time-zone convention, and migration ownership.
2. **Request and attempt identity (U02/U03):** payment/submission IDs, validation/assessment cardinality, resubmission lineage, duplicate eligibility, and concurrency uniqueness.
3. **Rule inputs and invoice policy (U04/U05/U07):** accepted rule inventory, history/evidence fields, partial payments, and whether quantity/account mismatch data enter scope.
4. **Immutable configuration and assessment evidence (U08):** version/snapshot relationships, input evidence, evaluator version, audit references, and retention.
5. **Outcome and handoff data (U09/U10):** business rejection versus technical failure, status-update ownership, stable correlation/idempotency fields, SLA setting shape, and whether one configuration version spans risk/routing/SLA.

Exact demonstration weights, windows, and SLA durations need not block drafting generic configuration tables. They must remain visibly provisional until approved before activation and evaluation. User-verified SYSTEM and three SQL Developer schema connections establish setup/client connectivity; application/JDBC and persistence behavior still need verification before claiming application Oracle integration works. No database execution is required for a paper design.

## Change boundary

This planning task creates and subsequently updates only `docs/developer2-plan.md`. Preserve existing services, source, configuration, and teammate files. No passwords, account creation, SQL/database script execution, application code, commits, pushes, or service execution are included. Implementation, testing, provisioning, and database verification described above are planned future actions, not completed work.
