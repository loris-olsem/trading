#requires -Version 7.0
<#
Read-only owner funding diagnostic. Resolves the agent by its authenticated GCID
and the owner's copy by mirror ID. Never saves raw responses or other holdings.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'etoro-diagnostic-common.ps1')
$projectRoot = Split-Path -Parent $PSScriptRoot
$checks = [Collections.Generic.List[object]]::new()

function Read-Endpoint {
    param(
        [ValidateSet('/me', '/agent-portfolios', '/trading/info/real/pnl')][string]$Path,
        [ValidateSet('owner', 'agent')][string]$Account
    )
    $requestId = [guid]::NewGuid().ToString()
    $check = [ordered]@{ account = $Account; path = $Path; requestId = $requestId; status = $null }
    $checks.Add($check)
    $userKey = if ($Account -eq 'owner') { $ownerKey } else { $agentKey }
    try {
        $response = Invoke-WebRequest -Method Get -Uri ('https://public-api.etoro.com/api/v1' + $Path) `
            -Headers @{ 'x-api-key' = $publicKey; 'x-user-key' = $userKey; 'x-request-id' = $requestId } `
            -TimeoutSec 30 -MaximumRedirection 0 -SkipHttpErrorCheck
    } catch { Stop-EtoroDiagnostic 'TRANSPORT_ERROR' "$Account $Path" }
    $check.status = [int]$response.StatusCode
    if ($response.StatusCode -ne 200) { Stop-EtoroDiagnostic 'HTTP_ERROR' "$Account $Path" }
    try { return ($response.Content | ConvertFrom-Json -AsHashtable) }
    catch { Stop-EtoroDiagnostic 'INVALID_JSON' "$Account $Path" }
}

function Require-Fields {
    param($Value, [string[]]$Names)
    foreach ($name in $Names) {
        if ($null -eq $Value -or -not $Value.Contains($name) -or $null -eq $Value[$name]) {
            Stop-EtoroDiagnostic 'MISSING_FIELD' $diagnosticStage $name
        }
    }
}

function Select-Fields {
    param($Value, [string[]]$Names)
    $selected = [ordered]@{}
    foreach ($name in $Names) { $selected[$name] = $Value[$name] }
    return $selected
}

$report = [ordered]@{
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
    mode = 'read-only'
    ownerAllocationVerified = $false
}
try {
    $diagnosticStage = 'credentials'
    try {
        $publicKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-bravos-agent/bravos-public-key.txt')).Trim()
        $agentKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-bravos-agent/bravos-private-key.txt')).Trim()
        $ownerKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-main-readonly/private-key.txt')).Trim()
    } catch { Stop-EtoroDiagnostic 'CREDENTIAL_READ_FAILED' $diagnosticStage }
    foreach ($key in @($publicKey, $agentKey, $ownerKey)) {
        if ([string]::IsNullOrWhiteSpace($key)) { Stop-EtoroDiagnostic 'EMPTY_CREDENTIAL' $diagnosticStage }
    }
    $diagnosticStage = 'identity'
    $owner = Read-Endpoint -Path '/me' -Account owner
    $agent = Read-Endpoint -Path '/me' -Account agent
    Require-Fields $owner @('gcid', 'realCid', 'scopes')
    Require-Fields $agent @('gcid', 'realCid')
    $report.ownerIdentity = Select-Fields $owner @('username', 'gcid', 'realCid', 'scopes')
    $report.agentIdentity = Select-Fields $agent @('username', 'gcid', 'realCid')
    if ($owner.gcid -eq $agent.gcid) { Stop-EtoroDiagnostic 'OWNER_IS_AGENT' $diagnosticStage }
    if (@($owner.scopes).Count -eq 0 -or @($owner.scopes | Where-Object { $_ -notmatch ':read$' }).Count -gt 0) {
        Stop-EtoroDiagnostic 'OWNER_NOT_READONLY' $diagnosticStage
    }
    $diagnosticStage = 'agent_lookup'
    $listing = Read-Endpoint -Path '/agent-portfolios' -Account owner
    $matches = @($listing.agentPortfolios | Where-Object { $_.agentPortfolioGcid -eq $agent.gcid })
    if ($matches.Count -ne 1) { Stop-EtoroDiagnostic 'AGENT_MATCH_COUNT' $diagnosticStage }
    $link = $matches[0]
    Require-Fields $link @('mirrorId', 'agentPortfolioId')
    $report.agentPortfolio = Select-Fields $link @('agentPortfolioId', 'agentPortfolioName', 'agentPortfolioGcid', 'agentPortfolioVirtualBalance', 'mirrorId')
    $diagnosticStage = 'mirror_lookup'
    $portfolioResponse = Read-Endpoint -Path '/trading/info/real/pnl' -Account owner
    $mirrors = @($portfolioResponse.clientPortfolio.mirrors | Where-Object { $_.mirrorID -eq $link.mirrorId })
    if ($mirrors.Count -ne 1) { Stop-EtoroDiagnostic 'MIRROR_MATCH_COUNT' $diagnosticStage }
    $mirror = $mirrors[0]
    Require-Fields $mirror @('parentCID', 'initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount', 'positions')
    if ($mirror.parentCID -ne $agent.realCid) { Stop-EtoroDiagnostic 'MIRROR_AGENT_MISMATCH' $diagnosticStage }
    # Reject missing/non-numeric amounts instead of silently interpreting them as zero.
    foreach ($name in @('initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount')) {
        if ($mirror[$name] -isnot [ValueType] -or $mirror[$name] -is [bool]) { Stop-EtoroDiagnostic 'INVALID_AMOUNT' $diagnosticStage $name }
    }
    $report.mirror = Select-Fields $mirror @('mirrorID', 'parentCID', 'parentUsername', 'initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount', 'closedPositionsNetProfit', 'isPaused', 'pendingForClosure', 'mirrorStatusID', 'stopLossAmount', 'stopLossPercentage')
    $report.netContributedUsd = [decimal]$mirror.initialInvestment + [decimal]$mirror.depositSummary - [decimal]$mirror.withdrawalSummary
    $report.availableMirrorCashUsd = [decimal]$mirror.availableAmount
    $report.positions = @($mirror.positions | ForEach-Object {
        Select-Fields $_ @('positionID', 'instrumentID', 'parentPositionID', 'units', 'amount', 'isBuy', 'leverage', 'stopLossRate', 'isNoStopLoss', 'isTslEnabled')
    })
    $report.orderCounts = [ordered]@{}
    foreach ($name in @('ordersForOpen', 'ordersForClose', 'ordersForCloseMultiple', 'delayedOrderForOpen', 'delayedOrderForClose', 'entryOrders', 'exitOrders')) {
        $report.orderCounts[$name] = Get-EtoroCollectionCount $mirror $name
    }
    $report.ownerAllocationVerified = $true
} catch {
    # All exceptions raised above are deliberately sanitized. Do not persist
    # unexpected library error text, which might contain account response data.
    $report.error = 'Funding verification incomplete. Check endpoint statuses and account/field matching.'
    $report.errorDetails = Get-EtoroDiagnosticError $_.Exception $diagnosticStage
    Write-Warning $report.error
}
$report.checks = @($checks.ToArray())
$stateDirectory = Join-Path $projectRoot 'state'
[IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
$json = $report | ConvertTo-Json -Depth 12
[IO.File]::WriteAllText((Join-Path $stateDirectory 'etoro-owner-funding.json'), $json)
Write-Output $json
if (-not $report.ownerAllocationVerified) { exit 1 }
