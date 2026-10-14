# Business configuration service M2a–M2d

This service implements the configuration-version list (M2a), draft-version creation (M2b), and version details/draft risk-rule management (M2c). It uses Java 21, Spring Boot 4.1.1, installed Maven 3.9.16, Spring JDBC and Boot-managed ojdbc17 23.26.3.0.0. The user verified direct JDBC connection, basic metadata and the authenticated empty read against Oracle 26ai Free on 13 October 2026. M2b/M2c verification is mocked only; no local Oracle writes were executed. No Maven Wrapper was created or repaired.

## API contract

`GET http://localhost:8086/api/v1/configuration/versions`

Supply an `Authorization: Bearer <access-token>` header. All supported endpoints require FINANCE_MANAGER as an **approved prototype policy supplied by the user**, not a requirement attributed to the scope document. POST creates a draft only; PUT manages one draft risk rule or risk band. General version editing, publication, activation and login endpoints are absent; other methods/paths are denied.

Successful requests return HTTP 200 and a JSON array ordered by VERSION_NUMBER ascending, then VERSION_ID ascending. An empty table returns `[]`; it does not create defaults or activate configuration.

| Field | JSON type | Meaning |
|---|---|---|
| versionId | string | Exact decimal Java Long ID; never a JSON number. |
| versionNumber | integer | Human-readable version number. |
| versionLabel | string | Stored label. |
| publicationState | string | Stored DRAFT or PUBLISHED state. |
| isDemonstration | boolean | Stored demonstration flag. |
| createdAt | string | ISO-8601 timestamp normalized to UTC with explicit `Z` offset. |
| publishedAt | string or null | Same timestamp representation; null for an unpublished version. |

Errors consistently contain `status` (integer HTTP status), `code`, `message`, `timestamp` (UTC ISO-8601), and `path`. Missing/invalid authentication returns 401/UNAUTHORIZED; authenticated users without FINANCE_MANAGER return 403/FORBIDDEN; JDBC/transaction failures return 503/CONFIGURATION_UNAVAILABLE; unexpected application failures return 500/INTERNAL_ERROR. Messages do not expose SQL, database diagnostics, claims, tokens or secrets. A database failure never becomes a successful empty list.

401 responses include a `WWW-Authenticate` Bearer challenge with realm `business-configuration-service`. Missing/empty credentials and unsupported schemes receive no OAuth token-error attribute. Invalid or expired tokens receive `error="invalid_token"`; no token/exception details are reflected. FINANCE_MANAGER-only access and 403 responses for insufficient roles are preserved.

## Create a draft version (M2b)

`POST http://localhost:8086/api/v1/configuration/versions`, with `Content-Type: application/json` and the same Bearer authentication.

| Request field | Validation |
|---|---|
| versionLabel | Required JSON string, nonblank, at most 100 Unicode code points. Stored as supplied. |
| description | Optional/null JSON string, at most 1000 Unicode code points. Oracle stores an empty string as null. |
| isDemonstration | Required JSON boolean; strings, numbers and null are rejected. |
| scorePolicyCode | Required exact string `SUM` or `CAP_AT_MAX`. |
| maxFinalScore | Absent/null for SUM. For CAP_AT_MAX, a JSON integer from 1 through 2147483647, matching the reviewed table constraint. Decimal representations, strings and booleans are rejected. |

Unknown fields are rejected, including client-supplied versionId, versionNumber, createdByUserId and publication metadata. Only the verified JWT supplies the actor. Invalid/malformed bodies return sanitized HTTP 400 with code `INVALID_REQUEST`; authentication and role checks precede database access.

Example request body for a future separately authorized live check (this example has not been submitted):

```json
{
  "versionLabel": "Evaluation draft",
  "description": "Configuration under review",
  "isDemonstration": true,
  "scorePolicyCode": "CAP_AT_MAX",
  "maxFinalScore": 100
}
```

The example score is request data, not a seed, active configuration or approved business default. Successful creation returns **HTTP 201** with the same seven-field DTO shown above: string versionId, integer versionNumber, versionLabel, `DRAFT`, isDemonstration, UTC createdAt and null publishedAt. Description and score policy are persisted but not added to this minimal response. GET subsequently reads the committed version in its existing deterministic order.

### Allocation and transaction behavior

Within one write transaction, read `COALESCE(MAX(VERSION_NUMBER), 0)` across all versions and attempt to insert the next integer. The existing reviewed `UK_CFG_VERSION_NUMBER` unique constraint is the concurrency guard, including when the table is empty. Two concurrent requests may choose the same number; Oracle permits only one committed version with that number. Only an INSERT executeUpdate failure with underlying Oracle vendor code 1 and the exact qualified constraint D2_CONFIGURATION.UK_CFG_VERSION_NUMBER returns sanitized HTTP 409 / `VERSION_ALLOCATION_CONFLICT`. The classifier follows SQLException causes/next exceptions and strictly parses the first qualified identity in the ORA-00001 diagnostic, supporting quoted names and localized surrounding text. Missing, ambiguous or unrecognized identities fail closed. Diagnostics are never returned/logged by this handling. Primary-key collisions, unknown constraints, number exhaustion and key-retrieval/readback failures use sanitized 503 without allocation retry advice. There is no upsert, overwriting, in-memory lock or automatic retry. After a recognized allocation conflict, refresh and explicitly retry if appropriate. This is not an idempotent POST; retrying after a lost response can create another draft.

The strategy depends on the deployed unique constraint; its live definition/enforcement and real concurrent writes still require verification. Other database/integrity/transaction failures return sanitized 503, and unexpected failures return sanitized 500. No Oracle messages, SQL or request credentials enter API errors.

### Controller-to-database flow for evaluation

1. The existing JWT filter verifies signature, expiry and identity claims. Security permits POST only for FINANCE_MANAGER.
2. The controller validates JSON types, field lengths and the policy/score combination, and reads the user ID from the verified principal.
3. The service opens a writable transaction, allocates the next version number and generates a UTC timestamp at microsecond precision.
4. The repository binds parameters into one INSERT in D2_CONFIGURATION.CONFIGURATION_VERSIONS. It omits VERSION_ID so Oracle identity generates it, fixes publicationState to DRAFT and omits publication metadata/content hash so they remain null. ROW_VERSION retains its table default. No child configuration rows or current pointer are created.
5. JDBC requests the generated `VERSION_ID` by column name, checks its exact positive Java Long value and reads the row back inside the transaction. The service converts it to the existing DTO. Transaction commit completes before the controller returns 201; any insert/readback/commit failure prevents a successful response.

## Version details and draft risk rules (M2c)

Use port 8086 and the same Bearer header. These endpoints use existing tables; no DDL or seed data is run.

| Endpoint | Response |
|---|---|
| GET /api/v1/configuration/versions/{versionId} | HTTP 200: existing seven version fields plus description, scorePolicyCode, nullable maxFinalScore and string rowVersion. Missing version: 404 / VERSION_NOT_FOUND. |
| GET /api/v1/configuration/versions/{versionId}/risk-rules | HTTP 200: rule array ordered by evaluationOrder, then ruleCode; `[]` for an existing version without rules. Missing version: 404. |
| PUT /api/v1/configuration/versions/{versionId}/risk-rules/{ruleCode} | HTTP 200 for create or replacement: `{ "rule": <saved rule>, "rowVersion": "<updated parent value>" }`. Existing rule identity is retained. |

Allowed rule codes are STRUCTURING, UNUSUAL_FREQUENCY, UNUSUAL_AMOUNT, RECENTLY_REACTIVATED_SUPPLIER and PREVIOUS_RISK_FLAGS. These represent the five explicit scope factors; their parameter semantics still need agreement. Other codes, including near-duplicate and new-supplier rules, are rejected. No weights, thresholds or rules are seeded.

PUT requires exactly these fields:

| Field | Structural validation |
|---|---|
| displayName | Nonblank string, at most 120 Unicode code points. |
| enabled | JSON boolean. |
| weight | JSON integer, 0 through 2147483647. |
| parameterSchemaVersion | Nonblank string, at most 32 Unicode code points. |
| parameters | JSON object, including an empty object; nested JSON values are serialized safely into a bound CLOB parameter. |
| evaluationOrder | JSON integer, 1 through 99999, matching NUMBER(5,0). Unique within a version. |
| expectedRowVersion | Required nonnegative Java Long value, supplied as a decimal digit string (recommended) or JSON integer. Floating-point values/overflow are rejected. |

The rule response contains string ruleConfigId/versionId, ruleCode, displayName, enabled, weight, parameterSchemaVersion, object parameters and evaluationOrder. Path versionId must be a positive Java Long. Unknown request fields are rejected.

Parameter decimals use BigDecimal and integers use BigInteger during M2c HTTP decoding and CLOB readback. Serialization preserves their numeric values as JSON numbers, including nested arrays/objects and scientific notation; textual formatting may change. Standard browser JavaScript numbers can lose precision before transmission or while parsing a response. The backend cannot recover values already rounded by a client; client-side exact numeric handling remains a separate integration concern.

Read version details to obtain rowVersion before PUT. The write transaction locks the parent with SELECT FOR UPDATE, checks DRAFT and the expected rowVersion, creates or replaces the child, increments the parent's rowVersion once, then reads back the saved rule. Stale edits return 409 / STALE_VERSION; published or other nondraft states return 409 / VERSION_NOT_DRAFT. Known rule-code/order uniqueness violations return 409 / RULE_CODE_CONFLICT or RULE_ORDER_CONFLICT; primary-key/unknown database errors remain sanitized 503. Parent/child failures roll back together. No automatic conflict retry is performed. Repeating an old expectedRowVersion is a stale edit, including after a lost successful response.

Draft parameters may be incomplete: M2c validates JSON shape and column limits, **not complete business-rule semantics**. Publication remains unavailable. Actual Oracle CLOB/identity mapping, parent-lock concurrency, constraints and rollback require separately authorized live verification; do not run PUT against local Oracle as part of this implementation task. Startup environment instructions below are unchanged. Detailed progress-report updates are deferred.

## Draft risk bands (M2d)

Use port 8086 and the same FINANCE_MANAGER Bearer authentication.

| Endpoint | Response |
|---|---|
| GET /api/v1/configuration/versions/{versionId}/risk-bands | HTTP 200: band array ordered by minScoreInclusive, then bandCode; `[]` for an existing version without bands. Missing version: 404 / VERSION_NOT_FOUND. |
| PUT /api/v1/configuration/versions/{versionId}/risk-bands/{bandCode} | HTTP 200 for create or replacement: `{ "band": <saved band>, "rowVersion": "<updated parent value>" }`. Existing band identity is retained. |

Band codes are exactly `LOW`, `MEDIUM` and `HIGH`. Path versionId must be a positive Java Long. PUT accepts only the following fields:

| Field | Structural validation |
|---|---|
| minScoreInclusive | Required JSON integer, 0 through 2147483647. |
| maxScoreInclusive | Absent/null for an unbounded upper score, or a JSON integer at least minScoreInclusive and at most 2147483647. |
| approvalLevel | Required JSON integer, 1 through 3. No fixed mapping between band code and approval level is imposed. |
| expectedRowVersion | Required nonnegative Java Long, as a decimal digit string (recommended) or JSON integer. Obtain the current value from the version-details endpoint. |

Decimal/scientific representations, strings and booleans are rejected for scores and approvalLevel; exact integer decoding prevents rounding or overflow. Unknown fields are rejected. The saved band contains string bandConfigId/versionId, bandCode, integer minScoreInclusive, nullable integer maxScoreInclusive and integer approvalLevel. Parent rowVersion is returned as a string.

PUT locks the same parent used by risk-rule edits, checks DRAFT and expectedRowVersion, inserts or updates one band, increments the parent once and reads the saved band back in one transaction. Commit completes before HTTP 200; failures roll back child and parent together. Stale edits return 409 / STALE_VERSION; nondraft edits return 409 / VERSION_NOT_DRAFT; an exhausted parent counter returns 409 / ROW_VERSION_EXHAUSTED. Only an INSERT/UPDATE error identifying Oracle vendor code 1 and D2_CONFIGURATION.UK_CFG_BAND_CODE returns 409 / BAND_CODE_CONFLICT. Unknown constraints, primary-key collisions and other database failures receive sanitized 503. Invalid input returns 400; missing versions return 404. There is no automatic retry.

Incomplete drafts, cross-band gaps and overlaps are allowed. Coverage, overlap and publication-completeness validation are deferred to publication, which remains unavailable. No boundaries or approval defaults are seeded.

M2d verification: the unchanged implementation passed 277 automated tests (0 failures, 0 errors, 0 skipped), explicitly excluding live Oracle tests. The user reports successful Oracle checks for band creation, null maximum readback, replacement preserving the band ID, parent rowVersion increment, stale-write HTTP 409 and unchanged state afterward. Concurrent parent locking, actual database rollback and full deployed constraint enforcement remain unverified. Automated JDBC transaction tests use mocked database objects; detailed progress documentation remains deferred.

## JWT contract

The service verifies signed claims with JJWT 0.12.6. The external HMAC key uses the same **raw UTF-8 bytes** as the existing payment service, not a newly invented or automatically generated application secret. Obtain the matching verification configuration privately from Developer 1. The key must meet JJWT's algorithm/key-strength requirements (at least 32 UTF-8 bytes for HMAC-SHA-256); do not pad/change an existing key just to pass a size check.

- `exp` is required and must be in the future. Signed expired tokens are rejected.
- `sub` is required and must be nonblank.
- `userId` is required: a positive JSON integer or digit string, no larger than 9223372036854775807. Fractions, floating-point representations, overflow, booleans, zero and negative IDs are rejected rather than rounded/truncated. Use digit strings for large IDs.
- `roles`, when supplied, must be a list of nonblank strings. The required entry is exactly `FINANCE_MANAGER`, without `ROLE_`. Missing/empty roles authenticate but cannot access this API. The backend adds the Spring authority prefix internally.
- No local/generated development users, Basic authentication, form login or server sessions are used.
- Bearer scheme parsing is case-insensitive (`Bearer`, `bearer`, `BEARER`); JWT token bytes are not case-folded, trimmed, or otherwise rewritten before verification.
- **Issuer/audience alignment is pending with Developer 1.** No issuer/audience values were invented and these claims are not enforced yet. This limit must remain visible during prototype evaluation.

## Database safety and current evidence

Application SQL is confined to D2_CONFIGURATION.CONFIGURATION_VERSIONS, RISK_RULE_CONFIGS and RISK_BAND_CONFIGS. Draft rule/band writes use parent locking, parameterized child INSERT/UPDATE, parent rowVersion increment and readback. Hikari permits writes; GET retains read-only service transactions and POST/PUT use write transactions. Read-only hints are not a substitute for database privilege separation: the existing schema owner still has broader rights. A separate limited runtime identity remains a future decision.

SQL initialization, automatic ORM DDL, Flyway and Liquibase are disabled. No ORM/migration dependency, SQL startup script, business seeds, or mock fallback is included. Existing objects are not recreated. Missing JWT configuration prevents startup; missing database configuration cannot produce a functioning read endpoint. An unreachable database causes startup/connection failure or a sanitized 503 on an authenticated read, depending on connection-pool initialization timing.

Agent-run verification on 13 October 2026: compilation and 29 mocked web/repository tests passed with Java 21.0.12.1, Maven 3.9.16 and Boot 4.1.1; the Oracle test was initially skipped because credentials were unavailable. Mocked tests are not proof of Oracle integration.

After the two authentication fixes, `mvn '-Dtest=ConfigurationVersionApiTest' test` passed on 13 October 2026: **40 API tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. Cases include Bearer/bearer/BEARER acceptance, separator spaces, unsupported schemes with an otherwise valid manager token, missing/invalid/expired challenges, sanitized errors and preserved manager-only/403 behavior. Only the API/security test class ran; the Oracle and repository tests were not rerun in this command. This documentation update does not rerun tests.

Subsequently, the user ran `OracleReadOnlyVerificationTest` on **13 October 2026**: **1 test, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. It verified JDBC access as D2_CONFIGURATION in FREEPDB1, the six configuration tables, expected version columns, IX_CFG_CURRENT_VERSION and a successful empty version query. Temporary password/test variables were cleared. This result is user-reported; no test was rerun for this documentation update and no secret values are recorded.

User-run live verification: security-service issued a token for a local FINANCE_MANAGER test account. The running configuration service accepted that token and returned **HTTP 200 with `[]`** from `GET /api/v1/configuration/versions`. This verifies the live issuer-to-configuration-service authenticated empty-list path for that local account and supplied signing configuration; it does not establish every authentication/error case or issuer/audience policy. Both services were stopped afterward. Temporary PowerShell database credentials, signing key, token and request variables were cleared. No secret values are recorded, and this documentation update does not rerun the check.

**Still unverified:** mapping and timestamp conversion with populated Oracle rows, and constraint/write behavior. Full deployed type/constraint-definition comparison, publication immutability and activation concurrency are also not proven by the empty-list checks. Issuer/audience alignment remains pending.

M2b automated verification on **13 October 2026**: installed Maven compiled the service and ran the explicitly selected mocked API, repository and transaction tests: **90 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. Breakdown: 40 existing GET/authentication regressions, 43 creation API cases, 5 repository cases and 2 transaction cases. Coverage includes draft creation, strict field combinations/boundaries, 401 challenges/403 denial, JWT actor attribution and rejected spoofed fields, string IDs/UTC timestamps, named identity-key retrieval and parameter binding, GET visibility fixtures, allocation conflict/exhaustion, sanitized database failures and transaction commit/rollback/read-only attributes. Repository/transaction collaborators are mocked; these tests do not prove Oracle identity retrieval, persisted-row mapping, concurrent constraint enforcement or actual database rollback. Mockito/JVM agent warnings and a deprecated transaction-test helper constructor were reported, without failures. The Oracle test was excluded explicitly, rather than counted as skipped.

After the two M2b review fixes on **13 October 2026**, compilation and the expanded explicitly selected suite passed: **116 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (40 existing API, 45 creation API, 5 repository, 2 service transaction, 20 JDBC transaction and 4 Oracle-error classifier tests). The JDBC transaction tests use real JdbcTemplate, repository/service/controller flow and Spring JdbcTransactionManager over mocked DataSource/Connection/Statement/ResultSet objects. They verify one transaction-bound connection, INSERT invocation, rollback/no commit/no 201 after missing, nonnumeric, nonpositive, fractional or overflowing identity values and readback SQL/mapping/no-row failures. Regression cases distinguish version-number/primary-key/unknown constraints, wrong owners/codes, quoted/localized identities, ambiguous/cyclic exception chains and errors outside executeUpdate. These are explicitly mocked transaction tests, not live Oracle integration; their actor resolver bypasses JWT solely in that class, with authentication covered separately by API tests. The optional Oracle test was excluded. A callback-overload compilation error introduced while narrowing the catch scope was fixed before the passing run; only non-failing Mockito/JVM agent and unchecked test-generic warnings remain.

## Beginner startup instructions

The following steps are for you to run later; the service has not been started by this implementation task.

1. Confirm the Oracle listener/PDB is available at localhost:1521/FREEPDB1. The user has reported the six existing configuration tables; do not rerun setup or schema scripts.
2. Obtain the D2_CONFIGURATION password and the matching JWT verification key through a private channel. Keep them out of source files, command-line arguments, screenshots, terminal transcripts, and commits.
3. Open PowerShell and set environment values for this terminal session. These prompts hide input; the script does not print it:

```powershell
Set-Location 'C:\risk_assessment\Risk_Assessment\business-configuration-service'
$env:CONFIG_DB_USERNAME = 'D2_CONFIGURATION'
$env:CONFIG_DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'D2_CONFIGURATION password' -AsSecureString)).Password
$env:CONFIG_JWT_SECRET = [System.Net.NetworkCredential]::new('', (Read-Host 'JWT key supplied privately by Developer 1' -AsSecureString)).Password
java -version
mvn -v
mvn spring-boot:run
```

This starts only this service on port 8086. Secrets are temporarily present in process environment memory; do not dump environment values. Stop with Ctrl+C, then clear them:

```powershell
Remove-Item Env:CONFIG_DB_PASSWORD, Env:CONFIG_JWT_SECRET, Env:CONFIG_DB_USERNAME -ErrorAction SilentlyContinue
```

4. Obtain a real FINANCE_MANAGER token from Developer 1's security API/private authentication procedure. The current UI supports requesters only and has no configuration page. Do not generate a replacement issuer/key for the application or initialize security-service as part of this milestone.
5. In a second terminal, paste the token into a hidden prompt and request the list. This prints version data, not the token:

```powershell
$managerToken = [System.Net.NetworkCredential]::new('', (Read-Host 'Finance manager access token' -AsSecureString)).Password
Invoke-RestMethod 'http://localhost:8086/api/v1/configuration/versions' -Headers @{ Authorization = "Bearer $managerToken" }
Remove-Variable managerToken
```

## Verification commands

From this service folder, use installed Maven:

```powershell
mvn '-Dmaven.test.skip=true' compile
mvn '-Dtest=DraftRiskBandApiTest,DraftRiskBandRepositoryTest,DraftRiskBandJdbcTransactionTest,DraftRiskRuleApiTest,DraftRiskRuleRepositoryTest,DraftRiskRuleJdbcTransactionTest,ConfigurationVersionApiTest,ConfigurationVersionCreationApiTest,ConfigurationVersionRepositoryTest,ConfigurationVersionTransactionTest,ConfigurationVersionJdbcTransactionTest,OracleVersionNumberConflictTest' test
```

The selected tests use random throwaway signing material only in the test JVM and mocked database access. They cover supported GET/POST/PUT behavior and denied methods. This explicit selection excludes the optional live Oracle test even if an opt-in variable exists in the terminal.

The optional Oracle test is deliberately disabled by default. With the database password already supplied securely in the current session, opt in explicitly:

```powershell
$env:CONFIG_VERIFY_ORACLE = 'true'
mvn '-Dtest=OracleReadOnlyVerificationTest' test
Remove-Item Env:CONFIG_VERIFY_ORACLE
```

That test reads as D2_CONFIGURATION at the fixed approved endpoint, checks FREEPDB1/session identity, six table names, expected version column names and IX_CFG_CURRENT_VERSION, then reads version IDs/timestamps. It never inserts, updates, deletes or executes DDL. An empty table cannot verify Oracle timestamp conversion using real rows. Full deployed type/constraint-definition comparison and constraint rejection tests are separate pending checks; this test does not claim those behaviors or immutability are verified.

## Remaining verification and work outside M2a/M2b

The user verified the live FINANCE_MANAGER empty-list HTTP/JWT path and compatibility of the locally supplied signing configuration before M2b. Live draft creation and M2c rule writes are pending and must not be run against local Oracle without separate authorization. Verify identity-key retrieval, all persisted draft fields and trusted actor, null publication fields, UTC timestamp round trips, subsequent GET, deployed unique constraints, simultaneous writes and CLOB round trips under an agreed data policy. Full type/constraint definitions, write/rollback behavior, issuer/audience alignment and runtime privilege policy remain pending. Frontend contracts/pages remain a separate integration task. Publication, activation, general version editing, audit delivery, and validation/risk services are not implemented here.
