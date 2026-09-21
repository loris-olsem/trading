# Dot-source once in each PowerShell session: . ./env.ps1
# This file only selects toolchains and installs the session-local gr alias.
if ($MyInvocation.InvocationName -ne '.') { throw 'Dot-source this file: . ./env.ps1' }
$bravosProjectRoot = $PSScriptRoot
$bravosPreviousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Stop'
$bravosPins = Get-Content -LiteralPath (Join-Path $bravosProjectRoot '.vfox.toml') -Raw
Push-Location $bravosProjectRoot
try {
    Invoke-Expression ((& vfox activate pwsh) -join "`n")
    foreach ($bravosTool in @('java', 'gradle')) {
        $bravosMatch = [regex]::Match($bravosPins, '(?m)^' + $bravosTool + '\s*=\s*"([^"]+)"')
        if (-not $bravosMatch.Success) { throw "Missing $bravosTool pin in .vfox.toml" }
        & vfox use -p ($bravosTool + '@' + $bravosMatch.Groups[1].Value)
        if ($LASTEXITCODE -ne 0) { throw "vfox failed to select $bravosTool" }
    }
    $env:JAVA_HOME = (Resolve-Path '.vfox/sdks/java').Path
    $bravosGradle = (Resolve-Path '.vfox/sdks/gradle/bin/gradle.bat').Path
    $global:BravosToolchain = @{ Gradle = $bravosGradle; Root = $bravosProjectRoot; Java = $env:JAVA_HOME }
    function global:Invoke-BravosGradle {
        $bravosPreviousJava = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $global:BravosToolchain.Java
            & $global:BravosToolchain.Gradle --project-dir $global:BravosToolchain.Root @args
            $global:LASTEXITCODE = $LASTEXITCODE
        } finally { $env:JAVA_HOME = $bravosPreviousJava }
    }
    Set-Alias -Name gr -Value Invoke-BravosGradle -Scope Global
} finally {
    Pop-Location
    $ErrorActionPreference = $bravosPreviousErrorAction
}
