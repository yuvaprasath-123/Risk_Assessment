[CmdletBinding()]
param([Parameter(Mandatory)][switch]$ConfirmIsolated)

# USER-RUN ONLY. Committed synthetic configuration writes; no application listener, DDL or seeding.
# Refuses any existing current pointer. Stop other configuration writers for this isolated test.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmIsolated) { throw 'Explicit isolated use is required.' }
$publicationSaved = @{}
function Set-PublicationEnvironment([string]$Name,[AllowNull()][string]$Value) {
    if (-not $publicationSaved.ContainsKey($Name)) { $publicationSaved[$Name] = [Environment]::GetEnvironmentVariable($Name,'Process') }
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
$publicationRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $publicationRoot
try {
    $publicationPassword = Read-Host 'D2_CONFIGURATION password (hidden, local only)' -AsSecureString
    $publicationPointer = [IntPtr]::Zero
    try {
        if ($publicationPassword.Length -eq 0) { throw 'Local database password is required.' }
        $publicationPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($publicationPassword)
        Set-PublicationEnvironment 'CONFIG_DB_PASSWORD' ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($publicationPointer))
    } finally {
        if ($publicationPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($publicationPointer) }
        $publicationPassword.Dispose()
    }
    Set-PublicationEnvironment 'CONFIG_VERIFY_ORACLE_CONCRETE_PUBLICATION' 'true'
    Set-PublicationEnvironment 'CONFIG_ORACLE_PUBLICATION_ISOLATED' 'true'
    & mvn -f developer2-pom.xml -pl risk-assessment-service -am '-Dtest=OracleConcretePublicationFlowTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw 'Verification failed. Retain printed namespace/reports; do not perform broad cleanup.' }
} finally {
    foreach ($publicationName in $publicationSaved.Keys) { [Environment]::SetEnvironmentVariable($publicationName,$publicationSaved[$publicationName],'Process') }
    $publicationSaved.Clear()
    Pop-Location
}
