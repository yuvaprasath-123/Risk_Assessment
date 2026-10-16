# Developer 3 services

Developer 3 implements approval routing/tasks, simulated payment execution, and append-only business audit. The 27 API entries in sections 7–9 of the supplied microservice blueprint are represented below. API availability does not mean that the whole team's automatic payment flow is connected.

| Service | Port | Main records |
|---|---|---|
| audit-service | 8088 | AUD_AUDIT_EVENTS (existing project's physical name for blueprint audit_events) |
| approval-workflow-service | 8089 | APPROVAL_ROUTES, APPROVAL_TASKS, APPROVAL_DECISIONS, APPROVAL_ESCALATIONS, BACKUP_APPROVER_MAPPINGS |
| payment-processing-service | 8090 | PAYMENT_PROCESSING, PAYMENT_PROCESSING_ATTEMPTS, PAYMENT_IDEMPOTENCY, PAYMENT_SIMULATION_CONFIG |

Developer 1 and 2 ports and source files were not changed.

## Run in STS

1. Refresh the imported projects using F5.
2. Right-click each Developer 3 project → Maven → Update Project. Select all three.
3. Use Java 21 or newer with Maven compiling for Java 21.
4. Set the database values under Run Configurations → Spring Boot App → Environment.
5. Start the three services. Start Developer 1's security-service on 8081 before creating or deciding approval tasks.

| Service | Environment names |
|---|---|
| Audit | AUDIT_DB_URL, AUDIT_DB_USERNAME, AUDIT_DB_PASSWORD |
| Approval | APPROVAL_DB_URL, APPROVAL_DB_USERNAME, APPROVAL_DB_PASSWORD |
| Processing | PROCESSING_DB_URL, PROCESSING_DB_USERNAME, PROCESSING_DB_PASSWORD |

For all three, a local URL example is `jdbc:oracle:thin:@localhost:1521/FREEPDB1`. Use your application schema credentials. Credentials are intentionally absent from source.

Approval and processing default to Hibernate `update` for local development, which creates their new tables. Audit defaults to `validate`:
- New audit database: run `audit-service/db/oracle-audit-schema.sql` in SQL Developer.
- Already have AUD_AUDIT_EVENTS: run `audit-service/db/oracle-upgrade-existing-audit.sql` once. It adds a nullable PAYMENT_ID column/index and an append-only trigger.
- These scripts are alternatives. Do not rerun CREATE/ALTER statements already applied.
- Hibernate update is a development convenience. The team should review schema changes before shared deployment.
- Existing APR_WORKFLOWS and PPC_SIMULATED_PAYMENTS demo tables are preserved, but new APIs use the blueprint tables above. Old demo rows are not migrated. New IDs can overlap old IDs, so use the new API responses.

Other settings:
- `SECURITY_SERVICE_URL`: approval's Developer 1 identity/authority service, default http://localhost:8081.
- `APPROVAL_DEFAULT_SLA_MINUTES`: fallback prototype SLA, default 60. Pass the SLA from Developer 2 when integrating; 60 is not a confirmed business value.
- `SIMULATION_ALLOW_MODE_CHANGE`: default true for the prototype; false blocks changing the default mode. All processing remains simulated.

## Approval rules and security

Routing stores the inputs and chooses MAX(amountApprovalLevel, risk level). LOW maps to L1, MEDIUM to L2, and HIGH to L3. The request must include the amount level already evaluated from Developer 2's configuration. An optional configurationVersion records the supplied version.

Create a route first, then a task for that payment. One route and one task per payment ID are allowed. The assigned approver must be active, have an approval role and sufficient authority in security-service. Self-assignment is blocked.

Approve/reject must use the assigned user's Bearer token and matching approverId. Self-approval and closed tasks are blocked. Rejection requires a nonblank remark. Manager override checks FINANCE_MANAGER through security-service and requires a justification; it can resolve MANUAL_REVIEW, but cannot reverse an already approved/rejected task.

Reassignment/escalation requires an expired SLA and a Finance Manager token. A backup remains at the same level; the selected user must have at least that authority. If backupApproverId is omitted, the optional BACKUP_APPROVER_MAPPINGS table is used. L1 escalates to L2, L2 to L3, and expired L3 enters MANUAL_REVIEW. Reassignment/escalation starts another period of the task's stored SLA.

SLA endpoints execute when called. There is no background scheduler or automatic backup selection from Developer 2 yet. The team's coordinator/scheduler must invoke them. Exact next/backup selection and production SLA policy still need team agreement.

## Insomnia

Import `docs/insomnia/developer3.insomnia.json` using Insomnia → Import → From File. Select Local.

1. Log in through Developer 1's existing login API and paste the returned token into Local.token. Do not include the word Bearer in the value.
2. Replace the sample user IDs with real requester, approver and manager IDs from security-service.
3. Calculate a route, then create its task using the same paymentId. Set Local.paymentId and Local.approvalTaskId from the response. The create request bodies contain editable example IDs: update those bodies too.
4. Approve, reject and override are alternative outcomes. Create a new payment/route/task for each path.
5. For backup/escalation testing, create a task with slaMinutes=1 and wait until dueAt. Use a manager token and a valid backup/next-level approver.
6. Execute a payment with simulateFailure=true; check attempts, then retry with false. Repeating after success must not add an attempt.
7. Record audit events, including the complete risk fixture, then use the payment history and snapshot requests.

The collection contains all 27 blueprint entries plus a complete frozen-risk event example. It has no saved passwords or tokens.

## API reference

### Approval Workflow Service

| Method | Path | What it does |
|---|---|---|
| POST | `/api/v1/approval-routes` | Stores amount/risk levels and MAX routing explanation. |
| GET | `/api/v1/approval-routes/payment/{paymentId}` | Returns stored route and frozen routing inputs. |
| GET | `/api/v1/approval-routes/payment/{paymentId}/decision-explanation` | Returns amount level, risk level, final level and configuration version. |
| POST | `/api/v1/approval-tasks` | Requires Bearer token; validates assigned approver with security-service. |
| GET | `/api/v1/approval-tasks?status=PENDING_APPROVAL` | Optional filters: paymentId, assignedApproverId, status. |
| GET | `/api/v1/approval-tasks/{approvalTaskId}` | Returns current assignment, status, SLA and decision. |
| POST | `/api/v1/approval-tasks/{approvalTaskId}/approve` | Use the assigned approver's token. Requester cannot approve own payment. |
| POST | `/api/v1/approval-tasks/{approvalTaskId}/reject` | Use a separate pending task; a rejection reason is required. |
| POST | `/api/v1/approval-tasks/{approvalTaskId}/override-and-approve` | Use Finance Manager token and matching approverId; justification required. |
| GET | `/api/v1/approval-tasks/payment/{paymentId}/history` | Returns task, all persisted decisions, and escalation/reassignment history. |
| GET | `/api/v1/approval-tasks/{approvalTaskId}/sla` | Returns SLA minutes, dueAt, expired and task status. |
| POST | `/api/v1/approval-tasks/{approvalTaskId}/reassign-to-backup` | Requires expired SLA and Manager token; backup must have sufficient authority. |
| POST | `/api/v1/approval-tasks/{approvalTaskId}/escalate-to-next-level` | Requires Manager token. L1/L2 needs next approver; expired L3 goes to manual review. |

### Payment Processing Service

| Method | Path | What it does |
|---|---|---|
| POST | `/api/v1/payment-processing/execute` | 201 first request; 200 replay. Use same canonical payment ID across services. |
| GET | `/api/v1/payment-processing/{paymentId}` | Returns saved SUCCESS/FAILED result. |
| POST | `/api/v1/payment-processing/{paymentId}/retry` | First create a FAILED payment. Success is protected against another execution. |
| GET | `/api/v1/payment-processing/{paymentId}/attempts` | Returns all original/retry attempts in order. |
| GET | `/api/v1/payment-processing/{paymentId}/idempotency-status` | Shows successful execution protection and attempt count. |
| PUT | `/api/v1/payment-processing/simulation-mode` | Prototype only. Used when execute/retry omit simulateFailure. |

### Audit Service

| Method | Path | What it does |
|---|---|---|
| POST | `/api/v1/audit-events` | Use paymentId to link task/risk/processing events into one payment timeline. |
| GET | `/api/v1/audit-events/payment/{paymentId}` | Chronological events linked by paymentId; includes legacy PAYMENT_REQUEST events. |
| GET | `/api/v1/audit-events/payment/{paymentId}/decision-history` | Filters approval/routing/rejection/escalation event types. |
| GET | `/api/v1/audit-events/payment/{paymentId}/risk-history` | Returns RISK_ events linked to the payment. |
| GET | `/api/v1/audit-events/payment/{paymentId}/risk-decision-snapshot` | Requires a complete RISK_ASSESSMENT_COMPLETED event; 404 missing, 409 incomplete. |
| GET | `/api/v1/audit-events?eventType=PAYMENT_APPROVED` | Can combine with performedBy, from/to and resource/correlation filters. |
| GET | `/api/v1/audit-events?performedBy=2` | Actor identifiers are strings. |
| GET | `/api/v1/audit-events?from=2026-10-01T00%3A00%3A00Z&to=2026-10-31T23%3A59%3A59Z` | Inclusive ISO-8601 bounds; percent-encode plus signs in timezone offsets. |

## Processing behavior

`POST /execute` creates one stored result and first attempt. Repeat requests with the same payment ID, supplier and amount return that result with idempotentReplay=true. A different supplier/amount for an existing ID returns 409.

A repeated failed execute also returns the existing failed result; use the explicit retry endpoint to create a new attempt. A retry after SUCCESS returns the protected success without another attempt. Retry operations lock the processing record to serialize concurrent attempts. A race between two first executions can return 409 for one caller; retrieve the saved state and repeat with the same inputs.

The success marker, attempt record and current state are saved in one transaction. The mode-change endpoint saves its default in PAYMENT_SIMULATION_CONFIG. Per-request simulateFailure overrides that default; omit it to use the saved mode. Missing IDs return 404.

Payment ID is a string in processing for compatibility. Use the numeric payment request ID as a string, e.g. "1", for integration with approval/audit. Processing does not independently verify approval or change Developer 1's payment state. Its internal caller must authorize execution and submit only approved payments.

## Audit and historical risk

Audit has no update/delete APIs. The Hibernate entity is immutable, and the Oracle scripts install a trigger rejecting UPDATE/DELETE. Every event records actor, old/new state, metadata and server timestamp.

For task/risk/processing events, supply the numeric paymentId explicitly. Legacy PAYMENT or PAYMENT_REQUEST events with a numeric resourceId also appear in the payment timeline. Timelines are chronological. Generic filters combine with AND, support string actor IDs, and accept ISO-8601 date/time bounds.

To reconstruct a risk decision, record eventType=RISK_ASSESSMENT_COMPLETED (or RISK_ASSESSED):
- afterState: JSON string containing riskScore, riskBand and any assessment details.
- metadata: JSON string containing configurationVersion, configurationSnapshot and scoreBreakdown.
- paymentId: the same payment request ID used by approval.

The snapshot endpoint returns the latest completed event's stored state and frozen configuration. It never fetches today's active rules or invents historical values. It returns 404 when no completed risk event exists, or 409 if the latest one is incomplete. The extra Insomnia fixture demonstrates this contract; its values are illustrative.

## Team integration still required

- Developer 1's coordinator must supply validated payment/requester IDs, invoke risk assessment, create approval work, call processing only after approval, and update payment lifecycle states.
- Developer 2's completed assessment and exact pinned configuration must provide riskBand, amountApprovalLevel, configurationVersion and SLA. This adapter does not yet fetch or verify Developer 2's snapshots/hashes.
- A scheduler must invoke SLA operations with a valid manager/service identity and configured selection rules.
- Services must publish their actual lifecycle events to audit. Approval/processing history is persisted locally, but it is not automatically copied into audit.
- Read/query, route creation, audit ingestion and simulated execution endpoints are intended for a trusted local/internal boundary. Gateway/service authentication and authorization for those operations must be agreed before shared deployment. Decision and reassignment endpoints already verify Developer 1 identity/authority.
- Cross-service failure recovery, event delivery, resubmission identity and final business policies remain team work.
- Run Oracle smoke tests using your real schema and end-to-end tests with Developer 1/2. Automated tests here use H2 in Oracle compatibility mode; they do not prove Oracle runtime compatibility.

## Automated checks

Run `mvn test` separately from each Developer 3 service folder. Tests cover persisted route/decision/escalation behavior, rejection/override guards, request validation, retry/idempotency including concurrent retries, audit query filters and frozen risk reconstruction. Test configuration uses an in-memory database and never uses your Oracle connection.

Verified on 16 October 2026: all three `mvn package` builds succeeded; 27 tests passed with zero failures or errors (approval 14, audit 6, processing 7). Built with JDK 25 targeting Java 21. The security client was checked against an HTTP fixture matching Developer 1's existing `/auth/me`, `/users/{id}` and `/approvers?approvalLevel=...` response shapes. This does not replace a live security/Oracle/team integration test.
