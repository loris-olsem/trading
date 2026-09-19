#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../scripts/etoro-diagnostic-common.ps1')
function Assert-Equal($Actual, $Expected, $Message) {
    if ($Actual -cne $Expected) { throw $Message }
}
$fixture = @{ orders = @(@{ orderId = 1 }, @{ orderId = 2 }); ordersForClose = @(@{ orderId = 3 }); ordersForCloseMultiple = @() }
Assert-Equal (Get-EtoroCollectionCount $fixture 'ordersForClose') 1 'General orders were counted as close orders.'
Assert-Equal (Get-EtoroCollectionCount $fixture 'ordersForCloseMultiple') 0 'An empty known list should be zero.'
Assert-Equal (Get-EtoroCollectionCount $fixture 'ordersForOpen') $null 'An absent list must be unknown.'
Assert-Equal (Get-EtoroCollectionCount @{ ordersForOpen = $null } 'ordersForOpen') $null 'A null list must be unknown.'
Assert-Equal (Get-EtoroCollectionCount @{ ordersForOpen = 'bad' } 'ordersForOpen') $null 'A malformed list must be unknown.'
try { Stop-EtoroDiagnostic 'MISSING_FIELD' 'mirror_lookup' 'availableAmount' }
catch { $failure = Get-EtoroDiagnosticError $_.Exception 'fallback' }
Assert-Equal $failure.code 'MISSING_FIELD' 'Structured error code lost.'
Assert-Equal $failure.stage 'mirror_lookup' 'Error stage lost.'
Assert-Equal $failure.field 'availableAmount' 'Missing-field identity lost.'
$unknown = Get-EtoroDiagnosticError ([Exception]::new('secret-like response must not appear')) 'identity'
Assert-Equal $unknown.code 'UNEXPECTED_ERROR' 'Unknown error code incorrect.'
if (($unknown | ConvertTo-Json) -match 'secret-like') { throw 'Unexpected error message leaked.' }
'Diagnostic fixture checks passed; no network calls.'
