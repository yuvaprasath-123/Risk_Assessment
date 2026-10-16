$ErrorActionPreference = 'Stop'
$handoffRoot = Split-Path -Parent $PSScriptRoot
$handoffBlockCount = 0
$handoffErrors = @()
foreach ($handoffFile in Get-ChildItem -LiteralPath $handoffRoot -Filter '*.md' -Recurse -File) {
    $handoffText = [IO.File]::ReadAllText($handoffFile.FullName)
    foreach ($handoffMatch in [regex]::Matches($handoffText, '(?ms)```powershell\r?\n(.*?)```')) {
        $handoffBlockCount++
        $handoffTokens = $null
        $handoffParseErrors = $null
        $null = [System.Management.Automation.Language.Parser]::ParseInput($handoffMatch.Groups[1].Value, [ref]$handoffTokens, [ref]$handoffParseErrors)
        foreach ($handoffError in $handoffParseErrors) {
            # Record identifiers/locations only, never source lines or local secret values.
            $handoffErrors += @{ file = $handoffFile.Name; lineWithinFence = $handoffError.Extent.StartLineNumber; errorId = $handoffError.ErrorId }
        }
    }
}
$handoffReport = @{ fencedPowerShellBlocksParsed = $handoffBlockCount; syntaxErrors = @($handoffErrors); commandsExecuted = $false; oracleAccess = $false }
$handoffJson = ConvertTo-Json -InputObject $handoffReport -Depth 5
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'powershell-validation-report.json'), $handoffJson + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
$handoffJson
if ($handoffErrors.Count -ne 0) { throw 'Documentation PowerShell syntax validation failed' }
