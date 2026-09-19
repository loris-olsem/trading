# Pure diagnostic helpers. Loading this file never reads credentials or calls eToro.
function Get-EtoroCollectionCount {
    param($Value, [string]$Name)
    if ($null -eq $Value -or -not $Value.Contains($Name) -or $null -eq $Value[$Name]) { return $null }
    if ($Value[$Name] -isnot [array]) { return $null }
    return @($Value[$Name]).Count
}

function Stop-EtoroDiagnostic {
    param([string]$Code, [string]$Stage, [string]$Field)
    $failure = [InvalidOperationException]::new('eToro diagnostic failed.')
    $failure.Data['diagnosticCode'] = $Code
    $failure.Data['diagnosticStage'] = $Stage
    if ($Field) { $failure.Data['diagnosticField'] = $Field }
    throw $failure
}

function Get-EtoroDiagnosticError {
    param([Exception]$Failure, [string]$FallbackStage)
    $result = [ordered]@{ code = 'UNEXPECTED_ERROR'; stage = $FallbackStage }
    if ($Failure.Data.Contains('diagnosticCode')) {
        $result.code = $Failure.Data['diagnosticCode']
        $result.stage = $Failure.Data['diagnosticStage']
        if ($Failure.Data.Contains('diagnosticField')) { $result.field = $Failure.Data['diagnosticField'] }
    }
    return $result
}
