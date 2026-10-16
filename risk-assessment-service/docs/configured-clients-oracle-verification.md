# Opt-in configured-client HTTP-to-Oracle verification

`OracleConfiguredClientsFlowTest` passed user-run live verification: **7 tests, zero failures/errors/skips**. It uses actual D2 production clients, real security/read/submission/persistence paths and local Oracle. Upstream providers and issuer are explicit contract-test fixtures, not Developer 1/3 infrastructure. The agent ran only offline safety/provider tests and did not execute the live suite.

## What the seven live tests exercise

- Create two complete synthetic drafts through real draft services; the real readiness validator must pass before real publication and activation. There are no readiness overrides or direct PUBLISHED inserts. Both snapshots use hash-covered approval-policy-v1/configuration-content-v2 semantics. B changes one demonstration weight so its identity/content differ; no business data is seeded.
- Enable the production Business Configuration, authoritative Facts and upstream-evidence HTTP clients by properties. Requests travel over actual loopback HTTP to actual authenticated configuration routes or explicit authenticated providers; no integration adapter implementation is replaced. Both active and explicitly pinned configuration selection are exercised.
- Drive real authenticated submission -> acceptance -> lease -> local prior-history capture -> evidence freezing -> finalization -> factor/status reads. A second assessment proves that the original completed first assessment is captured locally as prior-risk evidence. Supplier identity is isolated per run; provider payment history spans synthetic requesters.
- Completed retries leave the whole stored graph/time unchanged, perform no provider/configuration calls and do not invoke finalization. Conflicting payment/validation/revision/configuration choices return 409 without mutation.
- Certified INCOMPLETE history produces a recoverable unscored response and no final-input hash/factors. A temporary provider 503 recovers using the original acceptance/configuration/cutoff. This is different from changing an immutable incomplete receipt into a complete one.
- Activate B after A was accepted during a failed dependency attempt: the retry retains A without resolving configuration again; a new unspecified submission selects B; an explicit new A submission uses the pinned HTTP endpoint.
- Competing submissions synchronize only immediately before the real acceptance call; real Oracle transactions decide the winner. Assert one assessment, one snapshot, one claim/finalization and seven factors. Test spies count real method calls and synchronize contention; they do not substitute scoring, facts, evidence, readiness or persistence results.

The test-only provider establishes a complete immutable in-memory page population before page 0 and authenticates dedicated RISK_FACTS_READ/RISK_EVIDENCE_READ grants using ephemeral RS256 material. Production decoders verify reference/coverage/certification, page identity and full-population digest. This does **not** prove an upstream durable receipt survives a genuine provider restart or certify cross-team historical completeness.

## Database and lifecycle guards

The suite requires `D2_VERIFY_ORACLE_CONFIGURED_CLIENTS=true` and `D2_CONFIGURED_CLIENTS_ISOLATED=true`. Use exclusive local test access. Both connections must identify FREEPDB1 and their exact owners (`D2_CONFIGURATION`, `D2_RISK`). The URL is local `localhost:1521/FREEPDB1`; no SYSTEM connection, DDL, migrations, seeding or grants are used.

**An existing CURRENT_CONFIGURATION pointer causes refusal before fixture writes.** Do not delete a real pointer to make the test run. Arrange an appropriately isolated local test database/session; otherwise retain this verification as pending. Initial absence is checked under the established exclusive pointer-table lock. The isolation opt-in means nobody else activates/edits during the test. This is not a global lock held throughout the suite.

Each run prints an exact `d2-clients-<canonical UUID>` namespace. It owns up to ten risk parents under that exact SUBMISSION_SOURCE, one unique numeric supplier, and two configuration labels `D2_CLIENTS_<same UUID>_A/B` with fixed test actor/description/demonstration markers. Real acceptance/publication/activation writes **commit**, including the concurrent winner. Identity values are consumed even on rollback and are never reset. Existing quotas/tables must already support the suite; no quota is changed automatically.

All concurrent callers, dependency workers, clients, providers and both application receivers must stop before cleanup. Unknown startup/completion or failed shutdown defers cleanup. The existing ephemeral receiver helper starts D2 test receivers at random loopback ports **only when you run the opt-in suite**; no normal ports, Kafka, Stripe or teammate service starts. Offline provider tests open only temporary contract-test listeners, not application receivers or Oracle.

Guarded cleanup verifies exact identities, expected Facts/configuration canonical hashes, frozen input/capture/result graphs and diagnostic namespaces before removing risk children/parents. It verifies the actual publication hashes, fixture markers and expected pointer metadata, and refuses foreign pointers, unexpected pointer revisions or nonfixture risk consumers of the test configurations. Cleanup never deletes unrelated source namespaces or historical configuration versions. The pointer and both configuration graphs are removed only when owned by this run. Risk and Configuration cleanup use separate schema transactions, risk first; there is no cross-schema XA transaction. A later cleanup refusal can leave configuration fixtures after risk cleanup, recoverable with the same namespace. No historical setup/review-only SQL script is modified.

## Complete PowerShell command

Run from a disposable PowerShell session with **Java 21 and installed Maven 3.9.16** already selected. You need only the two database passwords, entered hidden. Issuer keys/tokens are generated in test memory; do not paste or export any signing key or user/service token.

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

The Unix-domain directory argument is the existing test-JVM-only native-client workaround; it does not change application/JDK settings. Its directory-dependent OS/JDK root cause remains unconfirmed. This command clears temporary environment references/BSTR buffers; it does not claim to cryptographically erase immutable .NET strings.

After interruption, use the same block with the exact printed namespace in `configuredRecoveryNamespace` only after all old workers are stopped. The cleanup-only class validates namespace before connecting and opens no receivers. It still refuses unfamiliar pointers/graphs; no wildcard, identity reset, account change or broad fallback exists.

## Verification limits

The adapter/protocol review found no confirmed production defect; the unchanged **2,117-test offline baseline is retained**. **173 affected offline cases passed**, including **10 new provider/safety cases**, zero failures/errors/skips. These counts overlap and are not added together. Existing results are reused because implementation is unchanged.

The user-run configured-client Oracle suite passed **7 tests, zero failures/errors/skips**. All receivers/client/providers/dependency/concurrent workers terminated, and guarded cleanup left **zero fixture parents in both schemas**. The run exercised real readiness-approved publication/activation and all three production HTTP clients through submission, local prior capture, evidence freezing and finalization. The agent did not rerun live tests, execute SQL or start services.

This passing run establishes **configured D2 client-to-D2-Oracle mechanics with contract-test upstreams and an ephemeral issuer**. Genuine Developer 1/3 endpoints, durable receipt retention/restart recovery, certified cutoff completeness, resubmission identity, issuer provisioning/rotation and HTTPS certificate interoperability remain separate integration responsibilities.
