# Business configuration service

## Opt-in internal reads

Dedicated service authentication and read contracts are defined in [internal-service-security](../internal-service-security/README.md). `GET /internal/v1/configuration/active` requires CONFIGURATION_ACTIVE_READ; `GET /internal/v1/configuration/versions/{versionId}` requires CONFIGURATION_VERSION_READ and verifies the requested published snapshot without consulting the current pointer. Internal access defaults off and never grants configuration mutations or manager roles. When enabled, require separate external RS256 public keys, issuer, receiver audience and explicit per-service permissions; user HMAC keys are not used. Existing manager endpoints remain unchanged. Build through `mvn -f developer2-pom.xml -pl business-configuration-service -am ...` from the repository root so the shared security library is included. No live service-issuer/Oracle verification has been performed for these routes.

## Activation and active snapshot contract

Both routes require the existing verified FINANCE_MANAGER JWT. Internal-service access is not added by this milestone.

`POST /api/v1/configuration/versions/{versionId}/activate` requires exactly one field:

```json
{"expectedPointerRowVersion":null}
```

Explicit null means **no current pointer exists**. When a pointer exists, send its exact revision as a decimal string, for example `{"expectedPointerRowVersion":"0"}`. Missing fields, fractions, negative/overflowing revisions, unknown fields and invalid version IDs return 400. The pointer revision is separate from the immutable version's revision.

Only PUBLISHED targets with a recomputed matching `configuration-content-v1` hash may activate. Pointer mutations serialize using a five-second-wait exclusive lock on the singleton table, including the empty-table first-activation race, followed by the target version lock. The READ_COMMITTED transaction has a 15-second timeout. First activation creates pointer revision `"0"`; switching versions increments it exactly once. Published rows/children and risk snapshots are never changed. A lost first-activation response followed by the original null precondition returns stale: read `/active` before deciding whether to retry.

A repeated activation of the same version with the **current pointer revision** returns 200 without changing actor, activation time or revision. A stale precondition takes precedence even for the same target. Errors: 409 `STALE_ACTIVE_CONFIGURATION`, `VERSION_NOT_PUBLISHED`, or `ACTIVATION_REVISION_EXHAUSTED`; 404 `VERSION_NOT_FOUND`; 503 `CONFIGURATION_UNAVAILABLE` for corruption/database/lock-timeout errors. Only the exact owned first-pointer PK collision is classified as a stale conflict.

`GET /api/v1/configuration/active` uses a read-only SERIALIZABLE transaction to return one coherent, verified snapshot. No pointer returns 404 `NO_ACTIVE_CONFIGURATION`. Missing/corrupt published associations fail closed with sanitized 503. Both successful endpoints return:

```json
{
  "versionId":"7",
  "versionRowVersion":"9",
  "pointerRowVersion":"0",
  "activatedAt":"2026-10-15T08:10:00Z",
  "activatedByUserId":"42",
  "publishedAt":"2026-10-15T08:09:00Z",
  "publishedByUserId":"42",
  "contentHash":"<locally recomputed SHA-256>",
  "hashFormat":"configuration-content-v1",
  "configuration":{
    "hashFormat":"configuration-content-v1",
    "versionId":"7",
    "riskRules":[],
    "riskBands":[],
    "approvalThresholds":[],
    "approvalSlas":[]
  }
}
```

Illustrative shape only: the `configuration` object is the **complete canonical publication content**, including version number/label/description, demonstration flag, score policy/cap, creation actor/time and every rule/band/threshold/SLA field. Arrays above are abbreviated, not a publishable example. IDs/revisions are strings, times UTC, money two-decimal strings; exact nested rule numbers are emitted directly from canonical JSON without a floating-point decode. Publication content hash and risk-engine snapshot hash are distinct contracts; a future adapter must map them explicitly. Current readiness/policy blockers remain unchanged; activation supplies no readiness bypass.

### Oracle activation mechanics verification

`OracleConfigurationActivationTest` requires **both** write and isolated-environment opt-ins. Stop configuration writers and first confirm `SELECT COUNT(*) FROM D2_CONFIGURATION.CURRENT_CONFIGURATION` returns 0 as D2_CONFIGURATION/FREEPDB1. If a real pointer exists, do not delete it: use a separate approved test environment. The suite refuses existing pointers; it never restores or overwrites one.

Six live cases cover first activation/readback, switching/repeat/stale behavior, draft and corrupt-hash rejection, actual post-write rollback and concurrent first activation. Test-only synthetic PUBLISHED rows are constructed with canonical hashes to test mechanics; their empty sections deliberately do **not** claim successful publication through the real blocked readiness validator. No production bypass is installed. Each case commits two UUID-labelled fixture versions and may commit its own singleton pointer. Cleanup requires worker termination, exact fixture identities/actors, unchanged parent content/hash and no unexpected children before removing only its pointer/versions transactionally. It never resets identity sequences. If interrupted or refused, retain the printed namespace and inspect before any recovery; there is no broad-delete fallback.

User-run only, from repository root (no server or JWT needed):

```powershell
try {
    $env:CONFIG_VERIFY_ORACLE_ACTIVATION_WRITES = 'true'
    $env:CONFIG_ORACLE_ACTIVATION_ISOLATED = 'true'
    $env:CONFIG_DB_PASSWORD = [System.Net.NetworkCredential]::new(
        '', (Read-Host 'D2_CONFIGURATION database password' -AsSecureString)
    ).Password
    mvn -f developer2-pom.xml -pl business-configuration-service -am '-Dtest=OracleConfigurationActivationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'Activation verification failed; retain namespace and reports.' }
}
finally {
    Remove-Item Env:CONFIG_VERIFY_ORACLE_ACTIVATION_WRITES, Env:CONFIG_ORACLE_ACTIVATION_ISOLATED, Env:CONFIG_DB_PASSWORD -ErrorAction SilentlyContinue
}
```

Activation-checkpoint verification: **1,398 offline tests passed**. The user-run live Oracle activation-mechanics suite passed **6 tests, zero failures/errors/skips**. Concurrent workers terminated; all fixture cleanups left **zero parents**. Controlled PUBLISHED fixtures verify activation mechanics, including contention and rollback, **not successful publication through the real readiness validator**. Real publication policy blockers remain intact. Successful real publication and live snapshots populated with complete business configuration remain unverified. Production implementation is unchanged, so existing reactor results are reused; the adjusted publication API regression class was additionally rerun (**32 tests, zero failures/errors/skips**) because its source was newer than its report. The agent did not run live SQL or services; detailed documentation remains deferred.

This service implements the configuration-version list (M2a), draft-version creation (M2b), and version details/draft risk-rule management (M2c). It uses Java 21, Spring Boot 4.1.1, installed Maven 3.9.16, Spring JDBC and Boot-managed ojdbc17 23.26.3.0.0. The user verified direct JDBC connection, basic metadata and the authenticated empty read against Oracle 26ai Free on 13 October 2026. M2b/M2c verification is mocked only; no local Oracle writes were executed. No Maven Wrapper was created or repaired.

## API contract

`GET http://localhost:8086/api/v1/configuration/versions`

Supply an `Authorization: Bearer <access-token>` header. All supported endpoints require FINANCE_MANAGER as an **approved prototype policy supplied by the user**, not a requirement attributed to the scope document. POST on the collection creates a draft; PUT manages one draft risk rule, risk band, approval threshold or approval SLA. Publication and activation are separate operations. General version editing and login endpoints remain absent; other methods/paths are denied.

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

## Publish a configuration version (mechanics only; policy-blocked)

`POST /api/v1/configuration/versions/{versionId}/publish` on port 8086 accepts only `{"expectedRowVersion":"8"}` (illustrative revision; fetch the actual version first). The revision may also be an exact nonnegative JSON integer within Java Long range; decimal/exponent/boolean values, unknown fields and caller-supplied publication metadata are rejected. Publisher identity comes only from the verified manager JWT.

The service locks the parent using the same lock as every draft child writer, reads all four child sections and calls the unchanged readiness validator within one writable Spring transaction. Any configuration errors or unresolved policy blockers produce HTTP 409 `CONFIGURATION_NOT_READY`, with the standard status/code/message/timestamp/path fields plus `readiness` containing the existing complete readiness response. **Real readiness still always includes history/identity certification, approval mapping and SLA-policy blockers. No real publishable fixture exists yet; this endpoint does not approve demonstration policies or bypass those blockers.**

If readiness eventually passes, one conditional UPDATE records PUBLISHED, the verified publisher, application-clock UTC publication time at Oracle TIMESTAMP(6) precision, the locally computed SHA-256 content hash and rowVersion+1. Verified readback precedes commit. HTTP 200 then returns `versionId`, `rowVersion`, `publicationState`, `contentHash`, `hashFormat` (`configuration-content-v1`), `publishedAt` (UTC ISO-8601) and `publishedByUserId`; all IDs/revisions are strings. Failures roll back the update; there is no current-pointer access, activation, seeding or external call.

The hash is domain/version separated by an explicit `hashFormat` in canonical UTF-8 JSON. It covers version identity/number/label/description, demonstration flag, score policy/cap, creation time/actor, and every rule/band/threshold/SLA field and child identity. Keys are sorted; child collections have deterministic semantic order; nested parameter arrays retain order, Unicode is preserved, nulls are explicit, decimal/integer parameter values are exact, and money is normalized to scale-two strings. Publication state/time/actor, rowVersion and the hash itself are excluded to avoid mutation-dependent/self-referential content. This is distinct from risk acceptance/configuration/evidence hashes. Retain the v1 canonicalizer for future compatibility.

Repeated publication of any PUBLISHED version returns 409 `VERSION_ALREADY_PUBLISHED` without changing content, publisher, timestamp or revision, even when the request contains its original revision. A stale DRAFT revision returns 409 `STALE_VERSION`; revision exhaustion returns 409 `ROW_VERSION_EXHAUSTED`. Missing versions return 404 `VERSION_NOT_FOUND`, invalid bodies/IDs return 400 `INVALID_REQUEST`, and database/readback/transaction failures use sanitized 503 `CONFIGURATION_UNAVAILABLE`. All existing child write paths lock/check DRAFT and refuse published edits. These are application invariants, not protection against arbitrary privileged SQL.

Offline success/rollback and competing-publication tests explicitly mock readiness=true; actual-validator tests retain all policy blockers. Verification: **1,319 offline reactor tests passed, zero failures/errors/skips, including 73 new publication tests**; all live Oracle classes were explicitly excluded. `OracleConfigurationPublicationTest` is disabled unless `CONFIG_VERIFY_ORACLE_PUBLICATION_WRITES=true`. It uses the real Spring/JDBC path with the real readiness validator, creates committed empty demonstration drafts under unique `D2_CFG_PUBLISH_<UUID>` labels, and checks blocked/repeated/stale/concurrent rejection with unchanged metadata. It cannot prove successful live publication while policies remain unresolved. It starts no server and requires no JWT key. Authentication is covered separately by automated HTTP token-fixture tests.

User-run Oracle verification (do not run until those synthetic writes are intended):

```powershell
Set-Location 'C:\risk_assessment\Risk_Assessment'
$publicationPassword = Read-Host 'D2_CONFIGURATION password' -AsSecureString
$publicationPointer = [IntPtr]::Zero
$publicationPriorPassword = [Environment]::GetEnvironmentVariable('CONFIG_DB_PASSWORD', 'Process')
$publicationPriorOptIn = [Environment]::GetEnvironmentVariable('CONFIG_VERIFY_ORACLE_PUBLICATION_WRITES', 'Process')
try {
    $publicationPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($publicationPassword)
    $env:CONFIG_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($publicationPointer)
    $env:CONFIG_VERIFY_ORACLE_PUBLICATION_WRITES = 'true'
    mvn -f developer2-pom.xml -pl business-configuration-service -am '-Dtest=OracleConfigurationPublicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
} finally {
    if ($publicationPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($publicationPointer) }
    $publicationPassword.Dispose()
    [Environment]::SetEnvironmentVariable('CONFIG_DB_PASSWORD', $publicationPriorPassword, 'Process')
    [Environment]::SetEnvironmentVariable('CONFIG_VERIFY_ORACLE_PUBLICATION_WRITES', $publicationPriorOptIn, 'Process')
    Remove-Variable publicationPassword, publicationPointer, publicationPriorPassword, publicationPriorOptIn -ErrorAction SilentlyContinue
}
```

Cleanup requires worker termination and verifies the exact UUID label, synthetic creator/description, unchanged DRAFT/revision/publication fields and absence of all children/current-pointer references before deleting that one draft. It never resets identities or deletes unrelated records. Unexpected data refuses cleanup transactionally. For interrupted-run recovery only, first confirm workers stopped, then supply the same external password plus `CONFIG_VERIFY_ORACLE_PUBLICATION_WRITES=true`, `CONFIG_PUBLICATION_CLEANUP_ONLY=true`, `CONFIG_PUBLICATION_WORKERS_STOPPED=true` and the exact printed `CONFIG_PUBLICATION_FIXTURE_NAMESPACE`; explicitly select only `OraclePublicationFixtureCleanupTest`. No wildcard recovery is supported. Live successful publication bindings, actual publication-update rollback and publish-vs-edit contention remain pending.

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

Allowed rule codes are STRUCTURING, UNUSUAL_FREQUENCY, UNUSUAL_AMOUNT, RECENTLY_REACTIVATED_SUPPLIER, PREVIOUS_RISK_FLAGS, NEW_SUPPLIER and NEAR_DUPLICATE_INVOICE. The last two are user-approved demonstration additions, not requirements attributed to the scope PDF. No weights, thresholds or rules are seeded. Draft PUT continues accepting incomplete object-shaped parameters; readiness applies the typed contracts below.

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

## Draft approval thresholds (M2e)

Use port 8086 and the same FINANCE_MANAGER Bearer authentication.

| Endpoint | Response |
|---|---|
| GET /api/v1/configuration/versions/{versionId}/approval-thresholds | HTTP 200: array ordered by minAmountInclusive, then approvalLevel; `[]` for an existing version with no thresholds. Missing version: 404 / VERSION_NOT_FOUND. |
| PUT /api/v1/configuration/versions/{versionId}/approval-thresholds/{approvalLevel} | HTTP 200 for creation or replacement: `{ "threshold": <saved threshold>, "rowVersion": "<updated parent value>" }`. Existing threshold identity is retained. |

The path approvalLevel is exactly `1`, `2` or `3`; versionId is a positive Java Long. PUT accepts only these fields:

| Field | Validation |
|---|---|
| minAmountInclusive | Required JSON **string** containing a nonnegative plain decimal amount from 0 through 9999999999999.99. |
| maxAmountExclusive | Absent/null for an unbounded upper amount, or a decimal JSON string strictly greater than the minimum and no larger than 9999999999999.99. |
| expectedRowVersion | Required nonnegative Java Long, as a decimal digit string (recommended) or JSON integer. Fetch from the version-details endpoint. |

Money strings contain digits and an optional decimal point followed by digits: no whitespace, signs, exponent notation, currency symbols or separators. JSON numbers and booleans are rejected. Parsing uses BigDecimal directly, with scale 2 and RoundingMode.UNNECESSARY; extra trailing fractional zeros are accepted, but any value requiring rounding is rejected. For example, `"1.230"` becomes `"1.23"`, while `"1.231"` is invalid. Responses return minAmountInclusive/maxAmountExclusive as canonical two-decimal strings (or null maximum), alongside string thresholdConfigId/versionId and integer approvalLevel. No money is converted through Double, and clients should retain these strings.

PUT shares the parent's transaction and concurrency value with rule/band edits: SELECT FOR UPDATE, DRAFT check, expectedRowVersion check, child INSERT/UPDATE, parent increment and readback. Parent and child failures roll back together; commit precedes HTTP 200. Stale/nondraft/exhausted edits use the existing 409 codes. Only an INSERT/UPDATE Oracle error with vendor code 1 and D2_CONFIGURATION.UK_CFG_THRESHOLD_LEVEL returns 409 / THRESHOLD_LEVEL_CONFLICT; primary-key, unknown-constraint and other database failures remain sanitized 503. Invalid input is 400; missing versions are 404. Authentication behavior is unchanged.

Incomplete drafts and cross-tier gaps/overlaps remain allowed; coverage/completeness checks are deferred to publication, which is unavailable. No approved monetary thresholds or defaults are invented or seeded.

M2e verification: the unchanged implementation passed 377 automated tests (0 failures, 0 errors, 0 skipped), explicitly excluding live Oracle tests. The user reports successful Oracle checks for exact decimal-string/null readback, replacement preserving the threshold ID, parent rowVersion increment, stale-write HTTP 409 and unchanged state afterward. Concurrent parent locking, actual database rollback and full deployed constraint enforcement remain unverified. Automated JDBC transaction tests use mocked database objects; detailed progress documentation remains deferred.

## Draft approval SLAs (M2f)

Use port 8086 and the same FINANCE_MANAGER Bearer authentication.

| Endpoint | Response |
|---|---|
| GET /api/v1/configuration/versions/{versionId}/approval-slas | HTTP 200: array ordered by approvalLevel; `[]` for an existing version without SLAs. Missing version: 404 / VERSION_NOT_FOUND. |
| PUT /api/v1/configuration/versions/{versionId}/approval-slas/{approvalLevel} | HTTP 200 for creation or replacement: `{ "sla": <saved SLA>, "rowVersion": "<updated parent value>" }`. Existing SLA identity is retained. |

The path approvalLevel is exactly `1`, `2` or `3`; versionId is a positive Java Long. PUT accepts only these fields:

| Field | Structural validation |
|---|---|
| durationSeconds | Required JSON integer, 1 through 999999999999, matching NUMBER(12,0). Strings, fractional/scientific representations, booleans and overflow are rejected. |
| startEventCode | Required nonblank JSON string, at most 32 Unicode code points. Stored as supplied. |
| escalationPolicyCode | Required nonblank JSON string, at most 32 Unicode code points. Stored as supplied. |
| terminalLevelPolicyCode | Absent/null, or a nonblank JSON string of at most 32 Unicode code points. Stored as supplied. |
| expectedRowVersion | Required nonnegative Java Long, as a decimal digit string (recommended) or JSON integer. Fetch from the version-details endpoint. |

The saved SLA contains string slaConfigId/versionId, integer approvalLevel/durationSeconds, startEventCode, escalationPolicyCode and nullable terminalLevelPolicyCode. Parent rowVersion is a string. Policy text must be well-formed Unicode: unpaired UTF-16 surrogates are rejected; valid supplementary characters count as one code point. Policy codes are draft data: unknown meanings and incomplete configurations are allowed. No approved SLA durations, start events or escalation/terminal semantics are invented or seeded. Complete policy validation is deferred to publication after agreement; publication remains unavailable.

PUT uses the shared parent transaction: SELECT FOR UPDATE, DRAFT and expectedRowVersion checks, child INSERT/UPDATE, parent increment and saved-row readback. Failures roll back parent and child together; commit precedes HTTP 200. Existing stale/nondraft/exhausted 409 codes apply. Only an INSERT/UPDATE Oracle error with vendor code 1 and D2_CONFIGURATION.UK_CFG_SLA_LEVEL returns 409 / SLA_LEVEL_CONFLICT; primary-key, unknown-constraint and other database failures remain sanitized 503. Invalid input is 400; missing versions are 404. Authentication behavior is unchanged.

M2f verification: the unchanged implementation passed 499 automated tests (0 failures, 0 errors, 0 skipped), explicitly excluding live Oracle tests. The user reports successful SLA checks for creation and null readback, replacement preserving the SLA ID, duration/terminal-policy readback, parent rowVersion increment, stale-write HTTP 409 and unchanged state afterward. Unicode Oracle round trips, concurrent parent locking, actual database rollback and full deployed constraint enforcement remain unverified. Automated JDBC transaction tests use mocked database objects; detailed progress documentation remains deferred.

## Publication readiness (M2g, read-only)

`GET http://localhost:8086/api/v1/configuration/versions/{versionId}/publication-readiness` uses the same FINANCE_MANAGER prototype access policy. It reads existing DRAFT or PUBLISHED versions; it does not publish, activate, hash, seed or update anything. A missing version returns 404 / VERSION_NOT_FOUND; malformed versionId returns 400. An existing but incomplete version returns HTTP 200 with readiness issues. Authentication and sanitized database-error conventions are unchanged; failed/corrupt reads return an error, never a partial readiness report.

| Response field | Meaning |
|---|---|
| versionId / rowVersion | Exact decimal strings from the evaluated parent snapshot. |
| publicationState / isDemonstration | Stored metadata; neither grants publication permission nor approves policy values. |
| structurallyValid | True only when no CONFIGURATION_ERROR issues exist. |
| ready | True only when the issue list is empty, including semantic blockers. Currently always false because approved semantic validators/contracts do not exist. |
| issues | Array of `{ "category": "CONFIGURATION_ERROR" or "POLICY_BLOCKER", "code": "stable code", "path": "/configuration-section/entry", "message": "safe explanation" }`. Sorted by category (configuration errors first), then path and code. |

Structural checks require all seven supported rule entries (presence, not enablement), valid rule fields and unique evaluation order. Each entry, including disabled entries, must have a valid versioned parameter contract. Missing/invalid contracts are INVALID_RULE_PARAMETER_CONTRACT errors. Stored parameter arrays/scalars or invalid JSON are INVALID_RULE_PARAMETER_OBJECT errors; JDBC read failures remain sanitized 503. Draft editing remains permissive. SUM must have no cap and its enabled weight total must fit Java Integer; CAP_AT_MAX requires a positive Integer cap. Exact arithmetic and configuration storage contracts remain unchanged.

LOW, MEDIUM and HIGH must be ordered, adjacent inclusive integer ranges starting at zero: each successor minimum equals the preceding maximum plus one. Only HIGH may have a null maximum. A finite HIGH maximum must cover the configured score upper bound. Coverage does not require every band to be reachable with the current weights/cap; no such business requirement is established. The structural bound is the enabled weight sum for SUM, or the declared maxFinalScore for CAP_AT_MAX. These are storage/configuration checks, not proof of complete rule semantics or approval of weights/boundaries. Band approval levels must be in 1–3; no fixed LOW/MEDIUM/HIGH-to-authority mapping is invented.

Amount levels 1–3 must form contiguous `[minimum inclusive, maximum exclusive)` tiers starting at zero, using exact NUMBER(15,2) BigDecimal arithmetic. Level 3 must be unbounded and preceding levels bounded. SLA levels 1–3 must exist and meet M2f duration/string/null constraints. Missing sections/entries, gaps, overlaps and invalid bounds are configuration errors; no monetary or SLA defaults are created.

Every report retains these POLICY_BLOCKER groups; rule-specific unresolved choices also produce RULE_POLICY_UNRESOLVED when enabled:

| Code | Decision needed |
|---|---|
| HISTORY_POLICY_UNRESOLVED | Developer 1 history eligibility certification, resubmission identity and coherent as-of evidence contracts. |
| APPROVAL_POLICY_UNRESOLVED | Risk-band authority mapping and level-one automatic/human handling with Developer 3. Demonstration currency INR and the scope's MAX routing rule are established. |
| SLA_POLICY_SEMANTICS_UNRESOLVED | Start-event meanings, escalation/backup priority and terminal-level behavior with Developer 3. Arbitrary stored strings cannot clear this blocker. |

The user-approved demonstration risk policy does not approve production thresholds or SLA meanings. Arbitrary JSON flags cannot clear blockers. Readiness is advisory: future publication must revalidate the same version/content atomically with its state transition, hash/immutability and agreed audit delivery; none is implemented here.

### Typed risk parameters

Use parameterSchemaVersion `risk-policy-v1`. Each parameters object must contain exactly the fields listed below at readiness; no defaults are supplied. `weight`, `enabled`, `evaluationOrder` and `expectedRowVersion` remain outside parameters in the existing PUT envelope.

| Rule | Parameter fields |
|---|---|
| STRUCTURING | windowSeconds, minimumPayments (at least 2), approvalBoundaryLevels (distinct 2/3), historyEligibility |
| UNUSUAL_FREQUENCY | windowSeconds, maximumSubmissions, historyEligibility |
| UNUSUAL_AMOUNT | windowSeconds, minimumObservations, meanMultiplier (positive decimal string), zeroBaselinePolicy |
| RECENTLY_REACTIVATED_SUPPLIER | windowSeconds |
| PREVIOUS_RISK_FLAGS | windowSeconds, minimumFlaggedPayments, qualifyingRuleCodes (nonempty, distinct supported codes; excludes itself) |
| NEW_SUPPLIER | windowSeconds |
| NEAR_DUPLICATE_INVOICE | windowSeconds, historyEligibility, invoiceNumberNormalization |

Windows are exact positive JSON integers bounded to 3155760000 seconds (100 years, an implementation limit); counts are positive Java Integer values. meanMultiplier permits up to 13 integer and 16 fractional digits. History eligibility choices are UNRESOLVED/VALIDATED_SUBMISSIONS/COMPLETED_PAYMENTS; zero baseline choices UNRESOLVED/INSUFFICIENT_HISTORY/COMPARE_TO_ZERO; invoice normalization choices UNRESOLVED/EXACT/TRIM_UPPERCASE. Explicit alternative choices are supported contract options, not an assertion of business agreement. Incomplete drafts can store missing fields; enabled unresolved choices block readiness. See [engine contracts](../risk-assessment-service/README.md) for evidence and unresolved semantics.

The shared `risk-rule-contracts` library prevents drift between readiness and scoring. From the repository root, run all Developer 2 tests without Oracle:

```powershell
mvn -f developer2-pom.xml '-Dtest=!OracleReadOnlyVerificationTest,!OracleConfigurationPublicationTest,!OraclePublicationFixtureCleanupTest,!OracleConfigurationActivationTest,!OracleAcceptancePersistenceTest,!OracleAcceptanceFixtureCleanupTest,!OracleWorkPersistenceTest,!OracleWorkFixtureCleanupTest,!OracleEvidencePersistenceTest,!OracleEvidenceFixtureCleanupTest,!OracleFinalizationPersistenceTest,!OracleFinalizationFixtureCleanupTest,!OracleRiskReadHttpTest,!OracleRiskReadHttpCleanupTest,!OracleInternalConfigurationHttpTest,!OracleInternalConfigurationCleanupTest,!OracleInternalRiskHttpTest,!OracleSubmissionHttpTest,!OracleSubmissionHttpCleanupTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Before using the existing service-local Maven compile/test/run commands, install the shared library once from the repository root (repeat after changing it): `mvn -f risk-rule-contracts/pom.xml install`. This command builds a library; it does not start a service or access Oracle.

All five SELECTs run on one Spring read-only SERIALIZABLE transaction. Oracle [transaction-level read consistency](https://docs.oracle.com/en/database/oracle/oracle-database/26/cncpt/data-concurrency-and-consistency.html) supplies a single committed snapshot across parent/rules/bands/thresholds/SLAs. A JDBC read-only hint alone would not provide this guarantee. No SELECT FOR UPDATE is used; repositories are called only for reads, and connection isolation/read-only settings are restored afterward. A readiness-specific rule reader distinguishes invalid stored configuration from SQL failures. Live Oracle concurrent-read/snapshot behavior remains unverified; transaction tests use mocked JDBC, including a MOCK-web Boot test of automatic transaction proxy wiring with no server socket or Oracle connection. Detailed progress documentation remains deferred.

M2g verification: the unchanged implementation passed 590 automated tests (0 failures, 0 errors, 0 skipped), explicitly excluding live Oracle tests. The user verified an HTTP 200 readiness response for versionId `"1"`, rowVersion `"9"`, structurallyValid `false` and ready `false`, with expected missing-section configuration errors and unresolved-policy blockers. This checks the live readiness response, not concurrent snapshot consistency, which remains unverified. No policy agreement or publication is implied; detailed documentation remains deferred.

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

Application SQL is confined to D2_CONFIGURATION.CONFIGURATION_VERSIONS, RISK_RULE_CONFIGS, RISK_BAND_CONFIGS, APPROVAL_THRESHOLD_CONFIGS and SLA_CONFIGS. Draft rule/band/threshold/SLA writes use parent locking, parameterized child INSERT/UPDATE, parent rowVersion increment and readback. Hikari permits writes; GET retains read-only service transactions and POST/PUT use write transactions. Read-only hints are not a substitute for database privilege separation: the existing schema owner still has broader rights. A separate limited runtime identity remains a future decision.

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
mvn '-Dtest=PublicationReadinessValidatorTest,PublicationReadinessApiTest,PublicationReadinessJdbcTransactionTest,PublicationReadinessRuleRepositoryTest,PublicationReadinessBootTransactionTest,DraftApprovalSlaApiTest,DraftApprovalSlaRepositoryTest,DraftApprovalSlaJdbcTransactionTest,DraftApprovalThresholdApiTest,DraftApprovalThresholdRepositoryTest,DraftApprovalThresholdJdbcTransactionTest,DraftRiskBandApiTest,DraftRiskBandRepositoryTest,DraftRiskBandJdbcTransactionTest,DraftRiskRuleApiTest,DraftRiskRuleRepositoryTest,DraftRiskRuleJdbcTransactionTest,ConfigurationVersionApiTest,ConfigurationVersionCreationApiTest,ConfigurationVersionRepositoryTest,ConfigurationVersionTransactionTest,ConfigurationVersionJdbcTransactionTest,OracleVersionNumberConflictTest' test
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
