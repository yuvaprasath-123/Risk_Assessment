# Environment, build, Oracle, startup, testing and recovery

These are operator instructions for later use, not actions performed while preparing this handoff. Do not put passwords, signing material, tokens or OTPs into files committed to Git. No wrapper repair, automatic DDL, migration, seed or provider fallback is part of this package.

## Verified tooling and database

| Item | Recorded environment |
|---|---|
| Java | JDK 21; previously verified 21.0.12.1 at `C:\jdk21` |
| Maven | Installed Maven 3.9.16 at `C:\apache-maven-3.9.16`; Maven Wrapper remains unresolved |
| Framework | Spring Boot 4.1.1; Java 21 reactor |
| Oracle | Oracle AI Database 26ai Free, 23.26.2.0.0; `localhost:1521/FREEPDB1` |
| Configuration | `D2_CONFIGURATION`, USERS quota 100M; six deployed tables |
| Risk | `D2_RISK`, USERS quota **later increased to 300M**; four deployed tables |
| Preserved validation account | `D2_VALIDATION`, originally 100M; validation implementation belongs to Developer 1 |

All three owner accounts were created with CREATE SESSION, CREATE TABLE, CREATE SEQUENCE and CREATE PROCEDURE; SQL Developer connections passed. D2 JDBC/read/write verification subsequently passed in isolated user-run suites. SYSTEM is a setup account, not an application datasource account. Runtime repositories use parameterized JDBC against existing owner tables, with SQL initialization, JPA DDL, Flyway and Liquibase disabled. Owner credentials still have owner DML power; application immutability is not a database privilege boundary.

Repository SQL remains **review only**. See [owner proposal](../database/dev2-schema-setup.sql), [configuration schema](../database/configuration-schema.sql), [risk schema](../database/risk-schema.sql) and [Developer 1 proposal](../database/developer1-schema-setup.sql). Preserve deliberate stops, password placeholders and original quotas. The original risk quota was 100M; a user-run finalization suite hit ORA-01536, then succeeded after the user increased only D2_RISK's USERS quota to 300M. Historical scripts were not rewritten to imply they originally granted 300M. Their historical “not executed/pending/future” comments describe preparation time; current verification is in the checklist.

For a new database, separately review owner/container/object guards and authorize an operator's execution copy; do not blindly rerun scripts against deployed objects. Oracle DDL commits implicitly. This guide supplies no new grants, passwords or migration SQL. Existing deployments need no DDL to use these backends.

## Build and offline tests

From repository root, with Java 21 and installed Maven selected:

```powershell
java -version
javac -version
mvn -v
# Build executable jars and shared libraries; compile tests but do not run any:
mvn -f developer2-pom.xml '-DskipTests' install
# Later, explicitly run offline tests only (Oracle-prefixed tests/cleanup excluded):
mvn -f developer2-pom.xml '-Dtest=*,!Oracle*' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Djdk.net.unixdomain.tmpdir=C:\risk_assessment\Risk_Assessment\risk-assessment-service\target' test
```

The reactor contains `risk-rule-contracts`, `internal-service-security`, Business Configuration and Risk Assessment. Configuration's `verification-classes` classifier and internal-security test jar support cross-backend tests; these are not runtime calls to a configuration JVM. Executable jars are `business-configuration-service/target/business-configuration-service-1.jar` and `risk-assessment-service/target/risk-assessment-service-1.jar`.

The native HTTP tests use temporary loopback listeners, not application services or Oracle. A successful prior run used the shown **test-JVM-only** Unix-domain socket directory. The underlying OS/JDK directory-dependent loopback construction cause remains unconfirmed; do not turn the test workaround into a production requirement or change global environment settings. Run from the repository root so target directories already created by Maven exist. Test result counts vary with the selected classes; do not add overlapping affected suites to historical reactor totals.

## Local process secrets and startup

Set secrets only in the process/STS run configuration used to launch that service. An STS Environment tab can hold the same variables; do not modify `.classpath`, `.project`, `.settings` or application YAML. The following helper reads hidden input and does not print it:

[environment-template.json](environment-template.json) lists the external variable names with blank credentials and disabled opt-ins. It is a reference document, not an automatically loaded runtime configuration or a complete enabled deployment.

```powershell
function Set-LocalSecret([string] $Name, [string] $Prompt) {
    $localSecure = Read-Host $Prompt -AsSecureString
    $localPointer = [IntPtr]::Zero
    try {
        $localPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($localSecure)
        [Environment]::SetEnvironmentVariable($Name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($localPointer), 'Process')
    } finally {
        if ($localPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($localPointer) }
        $localSecure.Dispose()
        Remove-Variable localSecure, localPointer -ErrorAction SilentlyContinue
    }
}
# Configuration terminal (only after operator chooses to start it):
$env:CONFIG_DB_USERNAME = 'D2_CONFIGURATION'
Set-LocalSecret 'CONFIG_DB_PASSWORD' 'D2_CONFIGURATION password'
Set-LocalSecret 'CONFIG_JWT_SECRET' 'Externally aligned user-JWT verification material'
try { java -jar .\business-configuration-service\target\business-configuration-service-1.jar }
finally { Remove-Item Env:CONFIG_DB_PASSWORD, Env:CONFIG_JWT_SECRET, Env:CONFIG_DB_USERNAME -ErrorAction SilentlyContinue }
```

Use a separate terminal for Risk Assessment:

In that new terminal, first copy the `Set-LocalSecret` function definition above; PowerShell functions are session-local and are not inherited by a separate terminal. Do not copy filled secret values between terminals or into files.

```powershell
$env:RISK_DB_USERNAME = 'D2_RISK'
$env:RISK_DB_URL = 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
$env:RISK_HTTP_PORT = '8087'
Set-LocalSecret 'RISK_DB_PASSWORD' 'D2_RISK password'
Set-LocalSecret 'RISK_JWT_SECRET' 'Externally aligned user-JWT verification material'
try { java -jar .\risk-assessment-service\target\risk-assessment-service-1.jar }
finally { Remove-Item Env:RISK_DB_PASSWORD, Env:RISK_JWT_SECRET, Env:RISK_DB_USERNAME, Env:RISK_DB_URL, Env:RISK_HTTP_PORT -ErrorAction SilentlyContinue }
```

Configuration defaults to 8086 with its existing local JDBC URL; Risk defaults to 8087 and permits `RISK_DB_URL`. Ordinary Spring external overrides remain available; this handoff changes no port/configuration behavior. Internal routes and submission are disabled by default, so manager reads can be used without provisioning service-principal trust. User JWT keys must align with the genuine security issuer; do not copy a token into a signing-key setting. No manager API issues tokens.

## Manager MFA with the current Developer 1 issuer

Security-service currently uses port 8081. Its owner must first prepare the existing database/account and intentionally start that service; its Hibernate/setup behavior differs from D2's disabled DDL. Do not assume starting teammate services is read-only or covered by this guide.

1. Use an existing active local account assigned the **unprefixed `FINANCE_MANAGER`** role. POST `/api/v1/auth/login` with `username` and `password`, privately supplied. It returns `{mfaRequired, challengeId, expiresInSeconds}`, not the final manager token.
2. Current `MfaService` is **console demo delivery**: it prints the challenge's OTP to the issuer's console. An authorized local issuer operator obtains that code privately. No SMTP/email delivery is implemented in this flow. OTP expiry is five minutes and the maximum is five attempts; an actual production delivery channel/security policy is Developer 1's responsibility.
3. POST `/api/v1/auth/verify-otp` with `challengeId` and a six-digit `otp` string. The response contains `accessToken`, `tokenType`, `userId`, `username`, `fullName` and `roles`. Keep the access token only in local process/private client memory; never record or export the OTP/token. Use the token's expiry and acquire another genuine token when needed.
4. Send `Authorization: Bearer <locally held token>` on manager routes. Missing/invalid/expired auth => 401; authenticated non-manager => 403. Scheme matching is case-insensitive; token verification remains strict. 401 responses have Bearer challenges; invalid tokens add `error="invalid_token"` without leaking parsing/signature details.

The earlier first-manager bootstrap was user-run local development: POST `/api/v1/users` hashed the supplied password; SYSTEM in FREEPDB1 inserted the association to the existing FINANCE_MANAGER role in `SUPPLIER_PAYMENT_APP.SEC_USER_ROLES` and committed; login obtained a genuine token. This is a historical bootstrap, not an automatic runtime account/role seed or a production authorization workflow.

## Service trust, submission and clients

Public manager routes use the existing user JWT verifier: signature/expiry, nonblank subject, positive integral Java-Long userId and FINANCE_MANAGER role. Current user issuer/audience alignment is not enforced as an internal-service contract and still needs cross-team review. Internal credentials are a separate RS256 trust domain; using the user signing key or user token there is invalid.

| Variables | Required meaning when enabled |
|---|---|
| `CONFIG_INTERNAL_ENABLED`, `RISK_INTERNAL_ENABLED` | Explicit `true`; otherwise internal routes return 404 INTERNAL_ACCESS_DISABLED while manager APIs remain usable |
| `CONFIG_INTERNAL_ISSUER`, `RISK_INTERNAL_ISSUER` | Exact trusted service issuer, externally agreed |
| `CONFIG_INTERNAL_AUDIENCE`, `RISK_INTERNAL_AUDIENCE` | Exact receiver audience; token must have exactly that single audience |
| `CONFIG_INTERNAL_PUBLIC_KEYS`, `RISK_INTERNAL_PUBLIC_KEYS` | JSON object: allowed kid → base64 DER X.509 RSA public verification key, at least 2048 bits; external provisioning only |
| `CONFIG_INTERNAL_SERVICE_PERMISSIONS`, `RISK_INTERNAL_SERVICE_PERMISSIONS` | JSON object: allowlisted service subject → nonempty list of supported receiver-owned permissions |
| `RISK_SUBMISSION_ENABLED` | Explicit true for POST and status service; does not itself grant access |
| `RISK_SUBMISSION_SOURCES` | JSON object service identity → namespaces, each namespace assigned exactly once; e.g. proposed `{"payment-request-service":["payment-request"]}` |
| `RISK_SUBMISSION_DEPENDENCY_BUDGET` | Positive ISO duration ≤ PT30S for bounded external dependency work; not an overall hard HTTP SLA |
| `RISK_LEASE_DURATION` | Explicit positive ISO duration; select operationally, no built-in approved default. Lease/final-write authority uses Oracle time |
| `RISK_HISTORY_MAX_ASSESSMENTS` | Default/maximum 1000; capacity overflow is incomplete/blocked rather than silently truncated |

Missing enabled trust or client settings fail startup safely. Service keys are public verification keys at the receivers; private keys remain with the external issuer. Tokens require RS256, known `kid`, `typ=service+jwt`, `principalType=SERVICE`, exact issuer/receiver audience, nonblank allowlisted subject, future exp and nonempty unique permissions. UserId/roles claims are forbidden. Requested permissions must all fit the receiver's grants; user/service cross-use is rejected. Rotation: provision overlapping trusted public kids, start issuing with the new kid, retain old verification keys until issued tokens expire, then remove old trust. Unknown kids fail closed. No issuance/refresh endpoint or embedded signing key is provided; the external operator must renew process credentials before expiry and restart/reprovision as appropriate.

Each HTTP client is separately opt-in. Disabled clients throw ADAPTER_UNAVAILABLE; they do not use demo data in normal runtime. Set no `risk-submission-demo` profile for production integration.

| Client | External variables |
|---|---|
| Configuration | `RISK_CONFIGURATION_CLIENT_ENABLED`, `RISK_CONFIGURATION_BASE_URL`, `RISK_CONFIGURATION_CONNECT_TIMEOUT`, `RISK_CONFIGURATION_REQUEST_TIMEOUT`, `RISK_CONFIGURATION_MAX_RESPONSE_BYTES`, `RISK_CONFIGURATION_SERVICE_TOKEN` |
| Facts | `RISK_FACTS_CLIENT_ENABLED`, `RISK_FACTS_BASE_URL`, `RISK_FACTS_PROVIDER_SERVICE_ID`, `RISK_FACTS_CONNECT_TIMEOUT`, `RISK_FACTS_HEADER_TIMEOUT`, `RISK_FACTS_BODY_TIMEOUT`, `RISK_FACTS_MAX_RESPONSE_BYTES`, `RISK_FACTS_SERVICE_TOKEN` |
| Evidence | `RISK_EVIDENCE_CLIENT_ENABLED`, `RISK_EVIDENCE_BASE_URL`, `RISK_EVIDENCE_PROVIDER_SERVICE_ID`, `RISK_EVIDENCE_CONNECT_TIMEOUT`, `RISK_EVIDENCE_HEADER_TIMEOUT`, `RISK_EVIDENCE_BODY_TIMEOUT`, `RISK_EVIDENCE_MAX_RESPONSE_BYTES`, `RISK_EVIDENCE_SERVICE_TOKEN`, `RISK_EVIDENCE_PAGE_SIZE`, `RISK_EVIDENCE_MAX_PAGES`, `RISK_EVIDENCE_MAX_RECORDS` |

Use fixed trusted origins (no paths, arbitrary callback URLs, query, fragment or userinfo). HTTPS with normal JVM certificate/hostname verification is required away from explicit loopback HTTP. No redirects. Each timeout is 1ms–30s; max response bytes 1–4194304. Facts/Evidence split connection/header/body deadlines; Configuration bounds its request/body consumption. Evidence page size 1–1000, maximum pages 1–100 and maximum records 1–9000. These are operational caps, not evidence completeness guarantees. Configure budgets large enough for the required complete population; exceeding a bound never authorizes partial scoring. External calls/evaluation occur outside write locks; database steps and evaluation add time beyond the dependency budget.

Tokens for the three upstream receivers are separately provisioned for their audiences/permissions. Do not reuse the inbound Risk submit token for Configuration or providers. Origin/TLS trust plus configured provider identity and certification establish authority; a matching response `serviceId` or digest alone does not.

## Testing and verification recovery

The current evidence is summarized in [completion-checklist.md](completion-checklist.md). This package was validated offline; no historical live suite was rerun for documentation.

Opt-in tests must remain deliberately enabled with credentials external. The most comprehensive existing instructions are [configured-client Oracle verification](../../risk-assessment-service/docs/configured-clients-oracle-verification.md): real readiness-approved publication/activation, production configuration/Facts/evidence clients, submission and Oracle across both owners. Its complete hidden-password PowerShell command enables only `OracleConfiguredClientsFlowTest` using `D2_VERIFY_ORACLE_CONFIGURED_CLIENTS=true` and the isolated-fixture confirmation. It uses contract-test providers and ephemeral service keys; this is not a genuine Developer 1 environment.

### Optional metadata-only Oracle verification (operator-run later)

These two selected tests execute read-only JDBC/metadata queries only; they do not require a JWT signing key or application startup. Use the hidden-input helper above in a disposable PowerShell session. The explicit class selector prevents any write suite from being selected.

```powershell
$env:CONFIG_DB_USERNAME = 'D2_CONFIGURATION'
$env:RISK_DB_USERNAME = 'D2_RISK'
$env:RISK_DB_URL = 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
Set-LocalSecret 'CONFIG_DB_PASSWORD' 'D2_CONFIGURATION password'
Set-LocalSecret 'RISK_DB_PASSWORD' 'D2_RISK password'
$env:CONFIG_VERIFY_ORACLE = 'true'
$env:RISK_VERIFY_ORACLE = 'true'
try {
    mvn -f developer2-pom.xml '-Dtest=com.ofss.configuration.OracleReadOnlyVerificationTest,com.ofss.risk.persistence.OracleReadOnlyVerificationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'Read-only metadata verification failed; inspect Surefire reports' }
} finally {
    Remove-Item Env:CONFIG_DB_PASSWORD, Env:RISK_DB_PASSWORD, Env:CONFIG_DB_USERNAME, Env:RISK_DB_USERNAME, Env:RISK_DB_URL, Env:CONFIG_VERIFY_ORACLE, Env:RISK_VERIFY_ORACLE -ErrorAction SilentlyContinue
}
```

### Optional configured-client Oracle flow and exact-namespace recovery

This command **does write synthetic fixtures** when explicitly run by the operator, and temporarily opens test receivers/providers. It starts no normal application service or production issuer. Use exclusive test access to both schemas; the fixture suite protects any existing active pointer. Keep `configuredRecoveryNamespace` null for verification. For cleanup-only recovery, first verify all previous workers are stopped, then set it only to the exact printed fixture namespace. This is not a general-purpose cleanup command.

```powershell
Set-Location 'C:\risk_assessment\Risk_Assessment'
$configuredRiskSecret = Read-Host 'D2_RISK password' -AsSecureString
$configuredConfigSecret = Read-Host 'D2_CONFIGURATION password' -AsSecureString
$configuredRiskPointer = [IntPtr]::Zero
$configuredConfigPointer = [IntPtr]::Zero
# Normal verification: keep null. Recovery: set ONLY the exact printed namespace
# after confirming all previous test receivers/clients/providers/workers stopped.
$configuredRecoveryNamespace = $null
try {
    $configuredRiskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($configuredRiskSecret)
    $configuredConfigPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($configuredConfigSecret)
    $env:RISK_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($configuredRiskPointer)
    $env:CONFIG_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($configuredConfigPointer)
    $env:RISK_DB_USERNAME = 'D2_RISK'
    $env:CONFIG_DB_USERNAME = 'D2_CONFIGURATION'
    $env:RISK_DB_URL = 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
    $env:D2_VERIFY_ORACLE_CONFIGURED_CLIENTS = 'true'
    $env:D2_CONFIGURED_CLIENTS_ISOLATED = 'true'
    if ($configuredRecoveryNamespace) {
        $env:D2_CONFIGURED_CLIENTS_NAMESPACE = $configuredRecoveryNamespace
        $env:D2_CONFIGURED_CLIENTS_CLEANUP_ONLY = 'true'
        $env:D2_CONFIGURED_CLIENTS_WORKERS_STOPPED = 'true'
        $configuredTestClass = 'OracleConfiguredClientsCleanupTest'
    } else {
        Remove-Item Env:D2_CONFIGURED_CLIENTS_NAMESPACE, Env:D2_CONFIGURED_CLIENTS_CLEANUP_ONLY, Env:D2_CONFIGURED_CLIENTS_WORKERS_STOPPED -ErrorAction SilentlyContinue
        $configuredTestClass = 'OracleConfiguredClientsFlowTest'
    }
    mvn -f developer2-pom.xml -pl risk-assessment-service -am "-Dtest=$configuredTestClass" '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Djdk.net.unixdomain.tmpdir=C:\risk_assessment\Risk_Assessment\risk-assessment-service\target' test
    if ($LASTEXITCODE -ne 0) { throw 'Verification/cleanup failed; retain the printed namespace and Surefire reports. Do not broaden cleanup.' }
} finally {
    if ($configuredRiskPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($configuredRiskPointer) }
    if ($configuredConfigPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($configuredConfigPointer) }
    $configuredRiskSecret.Dispose()
    $configuredConfigSecret.Dispose()
    Remove-Item Env:RISK_DB_PASSWORD, Env:CONFIG_DB_PASSWORD, Env:RISK_DB_USERNAME, Env:CONFIG_DB_USERNAME, Env:RISK_DB_URL, Env:D2_VERIFY_ORACLE_CONFIGURED_CLIENTS, Env:D2_CONFIGURED_CLIENTS_ISOLATED, Env:D2_CONFIGURED_CLIENTS_NAMESPACE, Env:D2_CONFIGURED_CLIENTS_CLEANUP_ONLY, Env:D2_CONFIGURED_CLIENTS_WORKERS_STOPPED -ErrorAction SilentlyContinue
    Remove-Variable configuredRiskSecret, configuredConfigSecret, configuredRiskPointer, configuredConfigPointer, configuredRecoveryNamespace, configuredTestClass -ErrorAction SilentlyContinue
}
```

Other prepared scripts are `risk-assessment-service/scripts/verify-concrete-publication.ps1`, `verify-risk-prior-capture.ps1`, `verify-risk-read-http.ps1` and `verify-risk-submission-http.ps1`; inspect their parameters/guards before use. Real readiness publication protects the existing active pointer and restores only recognized fixture transitions. Manager read verification uses a genuine externally obtained token; absence of a genuine non-manager token causes that authorization interoperability case to be skipped, not proven.

Live suites write unique synthetic namespaces and may commit rows to test concurrency/visibility. Use exclusive local test access, preserve the exact printed namespace, stop/confirm all workers, then use that suite's guarded cleanup path. Never delete broad table populations, reset identities, bypass pointer ownership guards or import teammate exports as cleanup. A quota/socket/guard failure is not proof later assertions were reached. Inspect Surefire reports and guard diagnostics; cleanup must report zero fixture parents in both schemas where applicable. Keep current application data intact.

## Production recovery contract

The caller durably records the stable source/key, separate payment/validation references, optional explicit configuration choice, correlation information and retry state **before** sending a submission. An uncertain HTTP result is resolved by status GET, which is read-only. A 404 means no accepted row was found at that instant; retry the same logical POST after backoff, never invent a new key because of a timeout.

ACCEPTED/RETRYABLE_FAILURE and expired IN_PROGRESS work can progress through the same-identity POST. A current unexpired worker is not duplicated. Lease renewal cannot revive an expired fence. Completed POST retries read the stored result without external calls/rescoring. BLOCKED requires intervention; there is no scheduler, unblock endpoint or automatic repair. Do not delete/recreate an assessment to evade immutable acceptance conflicts.

First acceptance pins facts, validation reference/revision, cutoff, configuration version/revision/publication provenance and evaluator. Before full freeze, upstream providers must retain/replay the exact original cutoff receipt; if they cannot, fail/block rather than recapture today's history. Developer 2 separately captures complete local prior-risk population immutably before relying on it. Once fully frozen, retries/takeovers reconstruct from persisted evidence and hashes. Technical failures never carry a completed score/band. Insufficient completed-payment baseline is an approved factor outcome with zero contribution while independent rules continue.

A permanently incomplete/unavailable certified provider receipt cannot be upgraded under the same replay identity. Although a dependency gap may surface as RETRYABLE_FAILURE, an immutable permanent gap requires operator/provider intervention rather than endless recapture attempts. Raising client page/record limits helps only when the provider retained the complete original population; it cannot rewrite an already persisted incomplete local prior-history capture.

After completion the caller/Developer 3 durably polls, validates the pinned configuration, deduplicates its own workflow transition and records a checkpoint. No risk callback/outbox or exactly-once cross-service transaction exists. See [handoff](developer1-developer3.md) for ownership and acceptance criteria.
