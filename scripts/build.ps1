# Project-scoped vfox toolchains; never select global versions.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs = @('check'))
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    Invoke-Expression ((& vfox activate pwsh) -join "`n")
    & vfox use -p java@25.0.4.1+1-tem
    if ($LASTEXITCODE -ne 0) { throw 'vfox Java selection failed' }
    & vfox use -p gradle@9.7.1
    if ($LASTEXITCODE -ne 0) { throw 'vfox Gradle selection failed' }
    $env:JAVA_HOME = (Resolve-Path '.vfox/sdks/java').Path
    & '.vfox/sdks/gradle/bin/gradle.bat' @GradleArgs
    $buildExit = $LASTEXITCODE
} finally { Pop-Location }
exit $buildExit
