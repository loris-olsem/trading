#requires -Version 7.0
<#
Read-only eToro connection diagnostic. Only three fixed GET endpoints are used.
Reads keys locally; never prints credentials, headers, or raw API responses.
The reported balance belongs to the authenticated account and is NOT assumed
to be the owner's real-money investment in an Agent Portfolio.
#>
param([ValidateRange(0, 1000000000)][decimal]$ReportedAllocationUsd = 500)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyDirectory = Join-Path $projectRoot 'secrets/etoro-bravos-agent'
$publicKey = [IO.File]::ReadAllText((Join-Path $keyDirectory 'bravos-public-key.txt')).Trim()
$privateKey = [IO.File]::ReadAllText((Join-Path $keyDirectory 'bravos-private-key.txt')).Trim()
if ([string]::IsNullOrWhiteSpace($publicKey) -or [string]::IsNullOrWhiteSpace($privateKey)) {
    throw 'Both eToro key files must contain a key.'
}

function Read-EtoroEndpoint {
    param([ValidateSet('/me', '/agent-portfolios', '/trading/info/real/pnl')][string]$Path)
    $requestId = [guid]::NewGuid().ToString()
    try {
        $response = Invoke-WebRequest -Method Get -Uri ('https://public-api.etoro.com/api/v1' + $Path) `
            -Headers @{ 'x-api-key' = $publicKey; 'x-user-key' = $privateKey; 'x-request-id' = $requestId } `
            -TimeoutSec 30 -MaximumRedirection 0 -SkipHttpErrorCheck
    } catch {
        # Exception text and raw responses could contain sensitive request data.
        return @{ path = $Path; requestId = $requestId; status = $null; error = 'Transport failure; no response logged.' }
    }
    $result = [ordered]@{ path = $Path; requestId = $requestId; status = [int]$response.StatusCode }
    try { $body = $response.Content | ConvertFrom-Json -AsHashtable } catch {
        $result.error = 'Non-JSON response; body withheld.'
        return $result
    }
    if ($response.StatusCode -ne 200) {
        $result.errorCode = if ($body.errorCode -match '^[A-Za-z0-9_.-]{1,80}$') { $body.errorCode } else { 'Unspecified' }
        return $result
    }
    switch ($Path) {
        '/me' {
            $result.identity = @{ username = $body.username; gcid = $body.gcid; realCid = $body.realCid; scopes = $body.scopes }
        }
        '/agent-portfolios' {
            $result.portfolios = @($body.agentPortfolios | ForEach-Object {
                @{ name = $_.agentPortfolioName; id = $_.agentPortfolioId; gcid = $_.agentPortfolioGcid;
                   internalVirtualBalance = $_.agentPortfolioVirtualBalance; mirrorId = $_.mirrorId; createdAt = $_.createdAt }
            })
        }
        '/trading/info/real/pnl' {
            $portfolio = $body.clientPortfolio
            if ($null -eq $portfolio) {
                $result.error = 'Expected clientPortfolio missing; no balance inferred.'
                $result.responseFieldNames = @($body.Keys)
                break
            }
            $result.portfolio = @{
                credit = $portfolio.credit
                positionCount = @($portfolio.positions | Where-Object { $null -ne $_ }).Count
                pendingOpenCount = @($portfolio.ordersForOpen | Where-Object { $null -ne $_ }).Count
                pendingCloseCount = @($portfolio.orders | Where-Object { $null -ne $_ }).Count
                mirrorCount = @($portfolio.mirrors | Where-Object { $null -ne $_ }).Count
                positions = @($portfolio.positions | ForEach-Object {
                    @{ positionId = $_.positionID; instrumentId = $_.instrumentID; amount = $_.amount;
                       isBuy = $_.isBuy; leverage = $_.leverage; mirrorId = $_.mirrorID }
                })
                mirrors = @($portfolio.mirrors | ForEach-Object {
                    @{ mirrorId = $_.mirrorID; availableAmount = $_.availableAmount;
                       positionCount = @($_.positions | Where-Object { $null -ne $_ }).Count }
                })
            }
        }
    }
    return $result
}

$checks = @('/me', '/agent-portfolios', '/trading/info/real/pnl') | ForEach-Object { Read-EtoroEndpoint -Path $_ }
$report = [ordered]@{
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
    mode = 'read-only'
    ownerAllocationUsdReportedByUser = $ReportedAllocationUsd
    ownerAllocationVerified = $false
    checks = @($checks)
}
$stateDirectory = Join-Path $projectRoot 'state'
[IO.Directory]::CreateDirectory($stateDirectory) | Out-Null
$json = $report | ConvertTo-Json -Depth 12
[IO.File]::WriteAllText((Join-Path $stateDirectory 'etoro-readonly.json'), $json)
Write-Output $json
