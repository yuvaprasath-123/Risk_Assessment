[CmdletBinding()]
param(
    [ValidateSet('Verify','Cleanup')][string]$Mode = 'Verify',
    [string]$Namespace,
    [switch]$ConfirmStopped
)

# USER-RUN ONLY. Committed synthetic D2_RISK writes, no server or DDL. Restores Process environment in finally.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$priorRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$priorSaved = @{}
function Set-PriorEnvironment([string]$Name,[AllowNull()][string]$Value) {
    if (-not $priorSaved.ContainsKey($Name)) { $priorSaved[$Name] = [Environment]::GetEnvironmentVariable($Name,'Process') }
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
if ($Mode -eq 'Cleanup') {
    if (-not $ConfirmStopped) { throw 'Confirm all test workers have stopped before cleanup.' }
    if ($Namespace -cnotmatch '^D2_RISK_PRIOR_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'Exact printed prior UUID namespace required; no wildcards.' }
}
Push-Location -LiteralPath $priorRoot
try {
    Set-PriorEnvironment 'RISK_DB_USERNAME' 'D2_RISK'
    Set-PriorEnvironment 'RISK_DB_URL' 'jdbc:oracle:thin:@//localhost:1521/FREEPDB1'
    $priorSecure = Read-Host 'D2_RISK password (hidden, local only)' -AsSecureString
    $priorPointer = [IntPtr]::Zero
    try {
        if ($priorSecure.Length -eq 0) { throw 'Local database password is required.' }
        $priorPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($priorSecure)
        Set-PriorEnvironment 'RISK_DB_PASSWORD' ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($priorPointer))
    } finally {
        if ($priorPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($priorPointer) }
        $priorSecure.Dispose()
    }
    Set-PriorEnvironment 'RISK_VERIFY_ORACLE_PRIOR_CAPTURE_WRITES' 'true'
    if ($Mode -eq 'Verify') {
        Write-Host 'Synthetic committed capture/history fixtures only. No services, DDL or upstream API calls.'
        & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OraclePriorCaptureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    } else {
        Set-PriorEnvironment 'RISK_PRIOR_CAPTURE_CLEANUP_ONLY' 'true'
        Set-PriorEnvironment 'RISK_PRIOR_CAPTURE_WORKERS_STOPPED' 'true'
        Set-PriorEnvironment 'RISK_PRIOR_CAPTURE_NAMESPACE' $Namespace
        & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OraclePriorCaptureCleanupTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    }
    if ($LASTEXITCODE -ne 0) { throw 'Prior verification/recovery failed. Retain namespace and reports; no broad cleanup attempted.' }
} finally {
    foreach ($priorName in $priorSaved.Keys) { [Environment]::SetEnvironmentVariable($priorName,$priorSaved[$priorName],'Process') }
    $priorSaved.Clear()
    Pop-Location
}
