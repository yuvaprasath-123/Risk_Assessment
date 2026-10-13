# Business configuration service M2a

This service implements only the read-only configuration-version list. It uses Java 21, Spring Boot 4.1.1, installed Maven 3.9.16, Spring JDBC and Boot-managed ojdbc17 23.26.3.0.0. The user verified direct JDBC connection, basic metadata and the empty read against Oracle 26ai Free on 13 October 2026; populated-row conversion remains unverified. No Maven Wrapper was created or repaired.

## API contract

`GET http://localhost:8086/api/v1/configuration/versions`

Supply an `Authorization: Bearer <access-token>` header. FINANCE_MANAGER-only access is an **approved prototype policy supplied by the user**, not a requirement attributed to the scope document. There are no write, publication, activation, or login endpoints. Other methods/paths are denied.

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

The only application query selects the seven response fields from D2_CONFIGURATION.CONFIGURATION_VERSIONS. Hikari and the service transaction request read-only connections/transactions. These are hints, not a substitute for database privilege separation: the existing schema owner still has broader rights. A separate limited runtime identity remains a future decision.

SQL initialization, automatic ORM DDL, Flyway and Liquibase are disabled. No ORM/migration dependency, SQL startup script, business seeds, or mock fallback is included. Existing objects are not recreated. Missing JWT configuration prevents startup; missing database configuration cannot produce a functioning read endpoint. An unreachable database causes startup/connection failure or a sanitized 503 on an authenticated read, depending on connection-pool initialization timing.

Agent-run verification on 13 October 2026: compilation and 29 mocked web/repository tests passed with Java 21.0.12.1, Maven 3.9.16 and Boot 4.1.1; the Oracle test was initially skipped because credentials were unavailable. Mocked tests are not proof of Oracle integration.

After the two authentication fixes, `mvn '-Dtest=ConfigurationVersionApiTest' test` passed on 13 October 2026: **40 API tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. Cases include Bearer/bearer/BEARER acceptance, separator spaces, unsupported schemes with an otherwise valid manager token, missing/invalid/expired challenges, sanitized errors and preserved manager-only/403 behavior. Only the API/security test class ran; the Oracle and repository tests were not rerun in this command. This documentation update does not rerun tests.

Subsequently, the user ran `OracleReadOnlyVerificationTest` on **13 October 2026**: **1 test, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. It verified JDBC access as D2_CONFIGURATION in FREEPDB1, the six configuration tables, expected version columns, IX_CFG_CURRENT_VERSION and a successful empty version query. Temporary password/test variables were cleared. This result is user-reported; no test was rerun for this documentation update and no secret values are recorded.

**Still unverified:** live HTTP/JWT integration, timestamp conversion with populated Oracle rows, and constraint/write behavior. Full deployed type/constraint-definition comparison, publication immutability and activation concurrency are also not proven by this read-only test. Successful direct JDBC access does not prove the Spring service's complete authenticated HTTP path.

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
mvn test
```

Default tests use random throwaway signing material only in the test JVM and mocked database access. They cover manager access, requester/approver/prefixed-role denial, missing/malformed/wrong-signature/expired/no-expiry tokens, invalid identity/subject claims, large-ID and timestamp serialization, empty lists, sanitized data failures and write-method denial.

The optional Oracle test is deliberately disabled by default. With the database password already supplied securely in the current session, opt in explicitly:

```powershell
$env:CONFIG_VERIFY_ORACLE = 'true'
mvn '-Dtest=OracleReadOnlyVerificationTest' test
Remove-Item Env:CONFIG_VERIFY_ORACLE
```

That test reads as D2_CONFIGURATION at the fixed approved endpoint, checks FREEPDB1/session identity, six table names, expected version column names and IX_CFG_CURRENT_VERSION, then reads version IDs/timestamps. It never inserts, updates, deletes or executes DDL. An empty table cannot verify Oracle timestamp conversion using real rows. Full deployed type/constraint-definition comparison and constraint rejection tests are separate pending checks; this test does not claim those behaviors or immutability are verified.

## Remaining work outside M2a

Confirm live HTTP/JWT integration, populated-row Oracle timestamp conversion, full deployed type/constraint definitions, issuer/audience alignment, privately supplied key compatibility with Developer 1, and runtime privilege policy. Constraint/write behavior remains unverified. Frontend contracts/pages remain a separate integration task. Publication, activation, configuration editing, audit delivery, and validation/risk services are not implemented here.
