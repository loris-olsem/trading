#requires -Version 7.0
<#
Read-only owner funding diagnostic. Resolves the agent by its authenticated GCID
and the owner's copy by mirror ID. Never saves raw responses or other holdings.
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$publicKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-bravos-agent/bravos-public-key.txt')).Trim()
$agentKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-bravos-agent/bravos-private-key.txt')).Trim()
$ownerKey = [IO.File]::ReadAllText((Join-Path $projectRoot 'secrets/etoro-main-readonly/private-key.txt')).Trim()
foreach ($key in @($publicKey, $agentKey, $ownerKey)) {
    if ([string]::IsNullOrWhiteSpace($key)) { throw 'An eToro key file is empty.' }
}
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
    } catch { throw 'eToro request failed; transport details withheld.' }
    $check.status = [int]$response.StatusCode
    if ($response.StatusCode -ne 200) { throw "eToro $Account GET $Path returned HTTP $($check.status)." }
    try { return ($response.Content | ConvertFrom-Json -AsHashtable) }
    catch { throw 'eToro response could not be parsed; body withheld.' }
}

function Require-Fields {
    param($Value, [string[]]$Names)
    foreach ($name in $Names) {
        if ($null -eq $Value -or -not $Value.Contains($name) -or $null -eq $Value[$name]) {
            throw "Required response field missing: $name. No funding inferred."
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
    $owner = Read-Endpoint -Path '/me' -Account owner
    $agent = Read-Endpoint -Path '/me' -Account agent
    Require-Fields $owner @('gcid', 'realCid', 'scopes')
    Require-Fields $agent @('gcid', 'realCid')
    $report.ownerIdentity = Select-Fields $owner @('username', 'gcid', 'realCid', 'scopes')
    $report.agentIdentity = Select-Fields $agent @('username', 'gcid', 'realCid')
    if ($owner.gcid -eq $agent.gcid) { throw 'The owner key resolves to the agent account.' }
    if (@($owner.scopes).Count -eq 0 -or @($owner.scopes | Where-Object { $_ -notmatch ':read$' }).Count -gt 0) {
        throw 'Owner token does not advertise exclusively read scopes; inspect its permissions.'
    }
    $listing = Read-Endpoint -Path '/agent-portfolios' -Account owner
    $matches = @($listing.agentPortfolios | Where-Object { $_.agentPortfolioGcid -eq $agent.gcid })
    if ($matches.Count -ne 1) { throw 'Expected exactly one owned portfolio matching the agent GCID.' }
    $link = $matches[0]
    Require-Fields $link @('mirrorId', 'agentPortfolioId')
    $report.agentPortfolio = Select-Fields $link @('agentPortfolioId', 'agentPortfolioName', 'agentPortfolioGcid', 'agentPortfolioVirtualBalance', 'mirrorId')
    $portfolioResponse = Read-Endpoint -Path '/trading/info/real/pnl' -Account owner
    $mirrors = @($portfolioResponse.clientPortfolio.mirrors | Where-Object { $_.mirrorID -eq $link.mirrorId })
    if ($mirrors.Count -ne 1) { throw 'Expected exactly one owner mirror matching the agent portfolio.' }
    $mirror = $mirrors[0]
    Require-Fields $mirror @('parentCID', 'initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount', 'positions')
    if ($mirror.parentCID -ne $agent.realCid) { throw 'Mirror copied account does not match the authenticated agent.' }
    # Reject missing/non-numeric amounts instead of silently interpreting them as zero.
    foreach ($name in @('initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount')) {
        if ($mirror[$name] -isnot [ValueType] -or $mirror[$name] -is [bool]) { throw "Invalid amount field: $name." }
    }
    $report.mirror = Select-Fields $mirror @('mirrorID', 'parentCID', 'parentUsername', 'initialInvestment', 'depositSummary', 'withdrawalSummary', 'availableAmount', 'closedPositionsNetProfit', 'isPaused', 'pendingForClosure', 'mirrorStatusID', 'stopLossAmount', 'stopLossPercentage')
    $report.netContributedUsd = [decimal]$mirror.initialInvestment + [decimal]$mirror.depositSummary - [decimal]$mirror.withdrawalSummary
    $report.availableMirrorCashUsd = [decimal]$mirror.availableAmount
    $report.positions = @($mirror.positions | ForEach-Object {
        Select-Fields $_ @('positionID', 'instrumentID', 'parentPositionID', 'units', 'amount', 'isBuy', 'leverage', 'stopLossRate', 'isNoStopLoss', 'isTslEnabled')
    })
    $report.orderCounts = [ordered]@{}
    foreach ($name in @('ordersForOpen', 'ordersForClose', 'ordersForCloseMultiple', 'delayedOrderForOpen', 'delayedOrderForClose', 'entryOrders', 'exitOrders')) {
        $report.orderCounts[$name] = if ($mirror.Contains($name) -and $null -ne $mirror[$name]) { @($mirror[$name]).Count } else { $null }
    }
    $report.ownerAllocationVerified = $true
} catch {
    # All exceptions raised above are deliberately sanitized. Do not persist
    # unexpected library error text, which might contain account response data.
    $report.error = 'Funding verification incomplete. Check endpoint statuses and account/field matching.'
    Write-Warning $report.error
}
$report.checks = @($checks.ToArray())
$stateDirectory = Join-Path $projectRoot 'state'
[IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
$json = $report | ConvertTo-Json -Depth 12
[IO.File]::WriteAllText((Join-Path $stateDirectory 'etoro-owner-funding.json'), $json)
Write-Output $json
if (-not $report.ownerAllocationVerified) { exit 1 }
