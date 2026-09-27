<#
.SYNOPSIS
  Unattended Bravos trading run, started by Windows Task Scheduler.

.DESCRIPTION
  Installed by scripts/install-schedule.ps1 (task "Bravos trading run").
  The task fires at two local times per slot because the US and EU switch
  daylight-saving time on different dates. This script converts the current
  time to New York time and only proceeds inside a slot window:

    open  slot: 09:40-10:30 New York (target 09:45)
    close slot: 15:25-15:50 New York (target 15:30)

  Outside a window, on weekends, or when the slot already ran today, it exits
  without doing anything. Holidays, early closes and every trading rule are
  enforced by the Java application itself (gr run), unchanged.

  Each run writes the full application output to state/scheduler/<time>-run.log,
  a short summary to state/scheduler/latest-report.txt, appends one line to
  state/scheduler/history.log, posts a report to Discord when
  secrets/discord/webhook-url.txt exists (sent only to discord.com) and shows
  a Windows notification.

  Requires Windows PowerShell 5.1 (powershell.exe) for the notification.

.PARAMETER DryRun
  Use the read-only `plan` operation instead of the live `run`.
.PARAMETER Force
  Ignore the time window and the once-per-slot guard (for testing with -DryRun).
#>
[CmdletBinding()]
param(
    [switch]$DryRun,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $Root 'state\scheduler'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$History = Join-Path $LogDir 'history.log'

function Write-History([string]$Text) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $Text
    Add-Content -LiteralPath $History -Value $line -Encoding UTF8
}

function Show-Notification([string]$Title, [string]$Body, [string]$OpenPath) {
    try {
        [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime]
        [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime]
        $esc = { param($s) [System.Security.SecurityElement]::Escape($s) }
        $uri = ([System.Uri]$OpenPath).AbsoluteUri
        $xmlText = '<toast activationType="protocol" launch="' + (& $esc $uri) + '" duration="long">' +
            '<visual><binding template="ToastGeneric">' +
            '<text>' + (& $esc $Title) + '</text>' +
            '<text>' + (& $esc $Body) + '</text>' +
            '</binding></visual></toast>'
        $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
        $xml.LoadXml($xmlText)
        $toast = New-Object Windows.UI.Notifications.ToastNotification $xml
        $appId = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe'
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)
    } catch {
        Write-History ('NOTIFICATION_FAILED ' + $_.Exception.Message)
    }
}

# --- Decide whether this trigger is inside a New York slot window -----------
$nyZone = [TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')
$nowNy = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $nyZone)
$minute = $nowNy.Hour * 60 + $nowNy.Minute
$slot = $null
if ($minute -ge 580 -and $minute -lt 630) { $slot = 'open' }    # 09:40-10:30
if ($minute -ge 925 -and $minute -lt 950) { $slot = 'close' }   # 15:25-15:50
$weekend = $nowNy.DayOfWeek -eq 'Saturday' -or $nowNy.DayOfWeek -eq 'Sunday'
$nyDate = $nowNy.ToString('yyyy-MM-dd')

if (-not $Force) {
    if ($weekend -or -not $slot) { exit 0 }   # the other DST candidate trigger; nothing to log
    $marker = Join-Path $LogDir ('last-' + $slot + '.txt')
    if ((Test-Path -LiteralPath $marker) -and ((Get-Content -LiteralPath $marker -Raw).Trim() -eq $nyDate)) {
        Write-History ("SKIPPED $slot slot already ran for $nyDate")
        exit 0
    }
    Set-Content -LiteralPath $marker -Value $nyDate -Encoding ASCII
}
if (-not $slot) { $slot = 'manual' }

# --- Run the pinned Gradle exactly as env.ps1's `gr` alias does -------------
$operation = 'run'
if ($DryRun) { $operation = 'plan' }
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmm'
$log = Join-Path $LogDir ($stamp + '-' + $operation + '.log')
$report = Join-Path $LogDir 'latest-report.txt'
$nyLabel = $nowNy.ToString('HH:mm') + ' New York'

try {
    $env:JAVA_HOME = (Resolve-Path -LiteralPath (Join-Path $Root '.vfox\sdks\java')).Path
    $gradle = (Resolve-Path -LiteralPath (Join-Path $Root '.vfox\sdks\gradle\bin\gradle.bat')).Path
} catch {
    Write-History "FAILED $slot toolchain not found under .vfox\sdks (run . ./env.ps1 once)"
    Show-Notification 'Bravos run could not start' 'Pinned Java/Gradle not found. Open PowerShell in the repo and run: . ./env.ps1' $History
    exit 1
}

$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'   # Gradle writes warnings to stderr
& $gradle --project-dir $Root --console=plain --no-daemon $operation 2>&1 |
    ForEach-Object { "$_" } | Out-File -LiteralPath $log -Encoding UTF8
$gradleExit = $LASTEXITCODE
$ErrorActionPreference = $previousPreference

# --- Summarise the application output ---------------------------------------
$lines = Get-Content -LiteralPath $log -Encoding UTF8
$blocks = New-Object System.Collections.ArrayList
$current = $null
$field = $null
foreach ($line in $lines) {
    if ($line -match '^([A-Z0-9][A-Z0-9.\-]*)  \[([A-Z ]+)\]$') {
        $current = [pscustomobject]@{ Symbol = $Matches[1]; Status = $Matches[2]; Fields = New-Object System.Collections.ArrayList; Text = '' }
        [void]$blocks.Add($current)
        $field = $null
    } elseif ($line -match '^\s*$') {
        $current = $null; $field = $null
    } elseif ($current -and $line -match '^-{10,}$') {
        continue
    } elseif ($current -and $line -match '^  (\S[^:]{0,15}):\s+(.*)$') {
        $field = [pscustomobject]@{ Label = $Matches[1]; Value = $Matches[2].Trim() }
        [void]$current.Fields.Add($field)
    } elseif ($current -and $field) {
        $field.Value = $field.Value + ' ' + $line.Trim()
    }
}
foreach ($b in $blocks) { $b.Text = ($b.Fields | ForEach-Object { $_.Label + ': ' + $_.Value }) -join ' ' }

$trades = @(); $notFilled = @(); $attention = @(); $watching = @()
foreach ($b in $blocks) {
    if ($b.Text -match 'Result: Confirmed by broker read-back:\s*([^.]*(\.\d+)?)') {
        $trades += ($b.Symbol + ' ' + $Matches[1].Trim())
    }
    if ($b.Status -eq 'NOT FILLED') { $notFilled += $b.Symbol }
    if ($b.Status -eq 'REVIEW' -or $b.Text -match 'Result: Not confirmed') { $attention += $b.Symbol }
    if (@('READY', 'PLANNED ACTION', 'WATCHING PRICE', 'WAITING FOR PRICE') -contains $b.Status) {
        $watching += ($b.Symbol + ' ' + $b.Status.ToLower())
    }
}
$finished = $lines | Where-Object { $_ -match '^(Run completed|Run finished with held|Plan completed)' } | Select-Object -Last 1
$killed = Test-Path -LiteralPath (Join-Path $Root 'state\runtime\KILL')

$color = 9807270   # grey
if (-not $finished) {
    $errorCode = ($lines | Where-Object { $_ -match '^[A-Z][A-Z0-9_]{2,100}$' } | Select-Object -Last 1)
    if (-not $errorCode) { $errorCode = 'Gradle exit ' + $gradleExit }
    $title = "Bravos $operation FAILED ($nyLabel)"
    $body = "No report produced: $errorCode. Nothing is retried automatically."
    $result = 'FAILED'; $color = 15548997
} elseif ($trades.Count -gt 0) {
    $title = "Bravos: $($trades.Count) trade(s) confirmed ($nyLabel)"
    $body = ($trades -join '; ')
    $result = 'TRADED'; $color = 5763719
} elseif ($attention.Count -gt 0 -or $notFilled.Count -gt 0) {
    $title = "Bravos: no trades, needs a look ($nyLabel)"
    $body = ''
    if ($notFilled.Count -gt 0) { $body += 'Not filled: ' + ($notFilled -join ', ') + '. ' }
    if ($attention.Count -gt 0) { $body += 'Review: ' + ($attention -join ', ') + '.' }
    $result = 'ATTENTION'; $color = 16753920
} else {
    $title = "Bravos: nothing to trade ($nyLabel)"
    $body = 'All positions unchanged; no new eligible Bravos actions.'
    $result = 'NOTHING'
}
if ($DryRun) { $title = '[DRY RUN - no orders] ' + $title }
if ($attention.Count -gt 0 -and $result -eq 'TRADED') { $body += ' | Review: ' + ($attention -join ', ') }
if ($watching.Count -gt 0 -and $result -ne 'FAILED') { $body += ' | Waiting: ' + ($watching -join ', ') }
if ($killed) { $body = 'KILL switch is set, no submissions. ' + $body }

# --- Market-calendar reminder (UsEquityCalendar covers one year at a time) ---
# From 1 December, every report nags until next year's dates are in the calendar;
# once a year has no dates at all, every report says trading is blocked.
$calendarNote = $null
$calendarFile = Join-Path $Root 'src\main\java\com\loris\bravos\domain\UsEquityCalendar.java'
$calendarText = ''
if (Test-Path -LiteralPath $calendarFile) { $calendarText = Get-Content -LiteralPath $calendarFile -Raw }
$year = $nowNy.Year
if ($calendarText -notmatch ('LocalDate\.of\(' + $year + ',')) {
    $calendarNote = "US market calendar has no $year dates: the app blocks every new entry (MARKET_CALENDAR_EXPIRED) until it is updated. See docs/market-hours.md."
} elseif ($nowNy.Month -eq 12 -and $calendarText -notmatch ('LocalDate\.of\(' + ($year + 1) + ',')) {
    $calendarNote = "Reminder: add the $($year + 1) US market calendar (holidays and early closes) before January, or trading stops on the first $($year + 1) run. See docs/market-hours.md."
}
if ($calendarNote -and $color -eq 9807270) { $color = 16753920 }

$summary = @(
    $title,
    ('=' * $title.Length),
    $body,
    '',
    ('Slot: ' + $slot + '   Operation: gr ' + $operation + '   Local time: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm')),
    ('Full log: ' + $log),
    '',
    '--- Application report ---'
) + ($lines | Where-Object { $_ -notmatch '^(> Task|BUILD |\d+ actionable task|Starting a Gradle Daemon|To honour the JVM settings|Daemon will be stopped)' })
Set-Content -LiteralPath $report -Value $summary -Encoding UTF8

# --- Discord report (secrets/discord/webhook-url.txt, sent only to Discord) ---
function Shorten([string]$Text, [int]$Max) {
    if ($Text.Length -le $Max) { return $Text }
    return $Text.Substring(0, $Max - 3) + '...'
}
$discordStatus = 'not configured'
$webhookFile = Join-Path $Root 'secrets\discord\webhook-url.txt'
if (Test-Path -LiteralPath $webhookFile) {
    $webhook = (Get-Content -LiteralPath $webhookFile -Raw).Trim()
    if ($webhook -notmatch '^https://(discord\.com|discordapp\.com)/api/webhooks/\d+/[A-Za-z0-9_\-]+$') {
        $discordStatus = 'invalid webhook URL in secrets\discord\webhook-url.txt'
    } else {
        $sections = New-Object System.Collections.ArrayList
        if ($calendarNote) { [void]$sections.Add(':warning: **' + $calendarNote + '**') }
        [void]$sections.Add($body)
        foreach ($b in $blocks) {
            $detail = New-Object System.Collections.ArrayList
            foreach ($f in $b.Fields) {
                if (@('Result', 'Partial fill', 'Your money', 'Price check', 'Agent limit', 'Bravos stop', 'Stop update', 'Sale', 'Reason') -contains $f.Label) {
                    [void]$detail.Add('> ' + $f.Label + ': ' + (Shorten $f.Value 220))
                } elseif ($f.Label -eq 'Details' -and $b.Status -ne 'UNCHANGED' -and $b.Status -ne 'NO ENTRY') {
                    [void]$detail.Add('> ' + (Shorten $f.Value 160))
                }
            }
            $headLine = '**' + $b.Symbol + '** - ' + $b.Status.ToLower()
            if ($detail.Count -gt 0) { $headLine = $headLine + "`n" + ($detail -join "`n") }
            [void]$sections.Add($headLine)
        }
        if ($finished) {
            $ending = $finished
            if ($finished -like 'Run finished with held*') { $ending = 'Run finished; some items are held (blocked/waiting items above are expected).' }
            [void]$sections.Add('_' + $ending + '_')
        }
        if (-not $finished) {
            $tail = ($lines | Where-Object { $_ -notmatch '^\s*$' } | Select-Object -Last 8) -join "`n"
            [void]$sections.Add('```' + "`n" + (Shorten $tail 900) + "`n" + '```')
        }
        $payload = @{
            username = 'Bravos trader'
            embeds = @(@{
                title = (Shorten $title 250)
                description = (Shorten ($sections -join "`n`n") 4000)
                color = $color
                footer = @{ text = ('slot ' + $slot + ' | gr ' + $operation + ' | full log on PC: state\scheduler\' + (Split-Path -Leaf $log)) }
                timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
            })
        }
        $json = $payload | ConvertTo-Json -Depth 6
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-RestMethod -Method Post -Uri $webhook -ContentType 'application/json; charset=utf-8' `
                -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 30 | Out-Null
            $discordStatus = 'sent'
        } catch {
            $discordStatus = 'failed: ' + $_.Exception.Message
        }
    }
}

if ($calendarNote) { $body = $calendarNote + ' ' + $body }
Write-History ("$result $slot gr $operation exit=$gradleExit discord=$discordStatus :: $body")
$toastBody = $body
if ($discordStatus -ne 'sent') { $toastBody = $body + ' (Discord: ' + $discordStatus + ')' }
Show-Notification $title $toastBody $report
if ($result -eq 'FAILED') { exit 1 }
exit 0
