[CmdletBinding()]
param(
    [ValidateSet('Verify','Cleanup')][string]$Mode = 'Verify',
    [string]$Namespace,
    [switch]$ConfirmStopped
)

# USER-RUN ONLY: commits synthetic D2_RISK fixtures and starts temporary loopback test receivers.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$submissionVerificationRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$submissionSavedEnvironment = @{}
function Set-SubmissionVerificationEnvironment([string]$Name, [AllowNull()][string]$Value) {
    if (-not $submissionSavedEnvironment.ContainsKey($Name)) { $submissionSavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
if ($Mode -eq 'Cleanup') {
    if (-not $ConfirmStopped) { throw 'Stop every test receiver, client, dependency and concurrent worker before passing -ConfirmStopped.' }
    if ([string]::IsNullOrWhiteSpace($Namespace) -or $Namespace -cnotmatch '^d2-risk-submit-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw 'Supply the exact printed lowercase canonical submission fixture namespace. Wildcards are forbidden.'
    }
}
Push-Location -LiteralPath $submissionVerificationRoot
try {
    Set-SubmissionVerificationEnvironment 'RISK_DB_USERNAME' 'D2_RISK'
    Set-SubmissionVerificationEnvironment 'RISK_DB_URL' 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
    Set-SubmissionVerificationEnvironment 'RISK_JWT_SECRET' $null
    $submissionSecureInput = Read-Host 'D2_RISK password (hidden, local only)' -AsSecureString
    $submissionPointer = [IntPtr]::Zero
    try {
        if ($submissionSecureInput.Length -eq 0) { throw 'A local database password is required.' }
        $submissionPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($submissionSecureInput)
        $submissionPlainInput = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($submissionPointer)
        Set-SubmissionVerificationEnvironment 'RISK_DB_PASSWORD' $submissionPlainInput
        $submissionPlainInput = $null
    } finally {
        if ($submissionPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($submissionPointer) }
        $submissionSecureInput.Dispose()
    }
    Set-SubmissionVerificationEnvironment 'RISK_VERIFY_ORACLE_SUBMISSION_HTTP_WRITES' 'true'
    if ($Mode -eq 'Verify') {
        Write-Host 'CONTRACT TEST ONLY: ephemeral issuer/adapters, temporary loopback receivers, committed synthetic fixtures. No genuine D1 or readiness-approved publication proof.'
        & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OracleSubmissionHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    } else {
        Set-SubmissionVerificationEnvironment 'RISK_SUBMISSION_HTTP_CLEANUP_ONLY' 'true'
        Set-SubmissionVerificationEnvironment 'RISK_SUBMISSION_HTTP_WORKERS_STOPPED' 'true'
        Set-SubmissionVerificationEnvironment 'RISK_SUBMISSION_HTTP_NAMESPACE' $Namespace
        & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OracleSubmissionHttpCleanupTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    if ($LASTEXITCODE -ne 0) { throw 'Verification/recovery failed. Retain the exact printed namespace and reports. No broader cleanup is attempted.' }
} finally {
    foreach ($submissionVariable in $submissionSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($submissionVariable, $submissionSavedEnvironment[$submissionVariable], 'Process') }
    $submissionSavedEnvironment.Clear()
    Pop-Location
}
