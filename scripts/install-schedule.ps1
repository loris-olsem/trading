<#
.SYNOPSIS
  Registers (or updates) the Windows scheduled task "Bravos trading run".

.DESCRIPTION
  Owner-requested on 2026-09-27: run the live `gr run` twice per US trading day,
  at 09:45 and 15:30 New York time. Task Scheduler only knows local time, so
  each New York target gets every local time it can map to during the year
  (e.g. 15:45 and 14:45 in Luxembourg); scripts/scheduled-run.ps1 then keeps
  only the trigger that really falls inside the New York window.

  The task runs as the current user, hidden, only while the user is logged on
  (a locked screen is fine), wakes the PC from sleep and never overlaps itself.

  Remove with:  scripts/install-schedule.ps1 -Uninstall
#>
[CmdletBinding()]
param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$TaskName = 'Bravos trading run'
$Root = Split-Path -Parent $PSScriptRoot

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Output "Removed scheduled task '$TaskName'."
    return
}

$nyZone = [TimeZoneInfo]::FindSystemTimeZoneById('Eastern Standard Time')
$targets = @('09:45', '15:30')   # New York time
$localTimes = @{}
$today = [DateTime]::Today
foreach ($target in $targets) {
    $parts = $target.Split(':')
    for ($d = 0; $d -lt 366; $d++) {
        $day = $today.AddDays($d)
        $ny = New-Object DateTime ($day.Year, $day.Month, $day.Day, [int]$parts[0], [int]$parts[1], 0, [DateTimeKind]::Unspecified)
        $local = [TimeZoneInfo]::ConvertTime($ny, $nyZone, [TimeZoneInfo]::Local)
        $localTimes[$local.ToString('HH:mm')] = $true
    }
}

$days = 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'
$triggers = foreach ($time in ($localTimes.Keys | Sort-Object)) {
    New-ScheduledTaskTrigger -Weekly -DaysOfWeek $days -At $time
}

$script = Join-Path $Root 'scripts\scheduled-run.ps1'
$arguments = '--headless powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $script + '"'
$action = New-ScheduledTaskAction -Execute 'conhost.exe' -Argument $arguments -WorkingDirectory $Root
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -WakeToRun -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 45)

$task = New-ScheduledTask -Action $action -Trigger $triggers -Principal $principal -Settings $settings `
    -Description 'Bravos: live gr run at 09:45 and 15:30 New York time on weekdays. See docs/scheduling.md.'
Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null

Write-Output "Registered '$TaskName'. Local trigger times (weekdays): $((($localTimes.Keys | Sort-Object) -join ', '))"
Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo | Select-Object NextRunTime | Format-List | Out-String | Write-Output
