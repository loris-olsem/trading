# Owner-operated CLI. No arguments display help; run is an explicit trading command.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CommandArgs = @('help'))
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $env:JAVA_HOME = (Resolve-Path '.vfox/sdks/java').Path
    $applicationPath = 'build/install/bravos-trader/bin/bravos-trader.bat'
    if (-not (Test-Path -LiteralPath $applicationPath)) {
        throw 'Build the distribution first: .\scripts\build.ps1 installDist'
    }
    & $applicationPath @CommandArgs
    $applicationExit = $LASTEXITCODE
} finally { Pop-Location }
exit $applicationExit
