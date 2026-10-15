[CmdletBinding()]
param(
    [ValidateSet('Start', 'Verify', 'Cleanup')][string]$Mode = 'Verify',
    [ValidateRange(1, 65535)][int]$Port = 8087,
    [string]$Namespace,
    [switch]$ConfirmRequestsStopped
)

# User-run only. No credentials/tokens are accepted as command-line arguments or written to files.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$httpSavedEnvironment = @{}
$httpRepositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Set-HttpVerificationEnvironment([string]$Name, [AllowNull()][string]$Value) {
    if (-not $httpSavedEnvironment.ContainsKey($Name)) {
        $httpSavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Read-HttpVerificationSecret([string]$Name, [string]$Prompt, [switch]$Optional) {
    $httpSecureValue = Read-Host $Prompt -AsSecureString
    $httpSecretPointer = [IntPtr]::Zero
    try {
        if ($httpSecureValue.Length -eq 0) {
            if (-not $Optional) { throw 'A required local secret/token was not supplied.' }
            Set-HttpVerificationEnvironment $Name $null
            return
        }
        $httpSecretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($httpSecureValue)
        $httpPlainValue = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($httpSecretPointer)
        Set-HttpVerificationEnvironment $Name $httpPlainValue
        $httpPlainValue = $null
    } finally {
        if ($httpSecretPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($httpSecretPointer) }
        $httpSecureValue.Dispose()
    }
}

if ($Mode -eq 'Cleanup') {
    if (-not $ConfirmRequestsStopped) { throw 'Stop outstanding requests/application and explicitly pass -ConfirmRequestsStopped before recovery cleanup.' }
    if ($Namespace -cnotmatch '^D2_RISK_FINAL_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') {
        throw 'Supply the exact printed HTTP fixture namespace; no wildcard cleanup is supported.'
    }
}

Push-Location -LiteralPath $httpRepositoryRoot
try {
    if ($Mode -eq 'Start') {
        # Build before prompting for the signing key; the build does not need it.
        & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw 'Executable packaging failed.' }
    }
    Set-HttpVerificationEnvironment 'RISK_DB_USERNAME' 'D2_RISK'
    Set-HttpVerificationEnvironment 'RISK_DB_URL' 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
    Read-HttpVerificationSecret 'RISK_DB_PASSWORD' 'D2_RISK password (hidden, local only)'
    if ($Mode -eq 'Start') {
        Read-HttpVerificationSecret 'RISK_JWT_SECRET' 'Existing issuer-aligned signing key (hidden, local only; never paste into chat)'
        Set-HttpVerificationEnvironment 'RISK_HTTP_PORT' ([string]$Port)
        Write-Host 'Leave this terminal running during verification. Stop with Ctrl+C afterward.'
        & java -jar '.\risk-assessment-service\target\risk-assessment-service-1.jar' "--server.port=$Port"
    } else {
        # The external HTTP application alone receives the signing key. These tests never read it.
        Set-HttpVerificationEnvironment 'RISK_JWT_SECRET' $null
        Set-HttpVerificationEnvironment 'RISK_VERIFY_ORACLE_HTTP_WRITES' 'true'
        if ($Mode -eq 'Verify') {
            Set-HttpVerificationEnvironment 'RISK_HTTP_TEST_BASE_URL' "http://127.0.0.1:$Port"
            Read-HttpVerificationSecret 'RISK_HTTP_TEST_MANAGER_TOKEN' 'Fresh genuine security-service FINANCE_MANAGER token (raw JWT, hidden)'
            Read-HttpVerificationSecret 'RISK_HTTP_TEST_OTHER_ROLE_TOKEN' 'Optional genuine non-manager issuer token (raw JWT, hidden; Enter to leave unverified)' -Optional
            Write-Host 'This creates two committed isolated fixtures. Optional wrong-role coverage is skipped if no genuine token is provided.'
            & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OracleRiskReadHttpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
        } else {
            Set-HttpVerificationEnvironment 'RISK_HTTP_TEST_MANAGER_TOKEN' $null
            Set-HttpVerificationEnvironment 'RISK_HTTP_TEST_OTHER_ROLE_TOKEN' $null
            Set-HttpVerificationEnvironment 'RISK_ORACLE_HTTP_NAMESPACE' $Namespace
            Set-HttpVerificationEnvironment 'RISK_ORACLE_HTTP_CLEANUP_ONLY' 'true'
            Set-HttpVerificationEnvironment 'RISK_HTTP_TEST_REQUESTS_STOPPED' 'true'
            & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OracleRiskReadHttpCleanupTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
        }
        if ($LASTEXITCODE -ne 0) { throw 'Verification/cleanup failed. Retain the printed namespace and Surefire reports; no broader cleanup is attempted.' }
    }
} finally {
    foreach ($httpEnvironmentName in $httpSavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($httpEnvironmentName, $httpSavedEnvironment[$httpEnvironmentName], 'Process')
    }
    $httpSavedEnvironment.Clear()
    Pop-Location
}
