# Scheduled trading runs

Owner request, 27 September 2026: run the live `gr run` automatically twice per
US trading day, in the background, with a report after every run.

| Slot | New York target | Window accepted | Luxembourg (usual) |
| --- | --- | --- | --- |
| After the open | 09:45 | 09:40-10:30 | 15:45 |
| Before the close | 15:30 | 15:25-15:50 | 21:30 |

## Pieces

- `scripts/install-schedule.ps1` registers the Windows task **Bravos trading run**
  (`-Uninstall` removes it). `scripts/bravos-trading-run.task.xml` is the same
  task for Task Scheduler's *Import Task*.
- Triggers fire weekdays at 14:45, 15:45, 20:30 and 21:30 local time. The US
  and EU change daylight-saving time on different dates, so each slot has two
  local candidates; `scripts/scheduled-run.ps1` converts to New York time and
  runs only the one inside the window, at most once per slot per day
  (`state/scheduler/last-*.txt`).
- The task runs hidden as the logged-on user (a locked screen is fine), wakes the
  PC from sleep, never overlaps and is stopped after 45 minutes. A shut-down PC
  or logged-out user means the run is skipped, never made up later.
- Holidays, early closes, prices, sizing, stops and the kill switch are all still
  enforced by the application. `gr kill` also stops scheduled submissions.

## Reports

Every run writes `state/scheduler/<time>-run.log`, `latest-report.txt` and one
line in `history.log` (all ignored by Git), shows a Windows notification and,
when `secrets/discord/webhook-url.txt` exists, posts to that Discord webhook:
trades confirmed (green), nothing to trade (grey), needs a look (orange) or
failed (red), with a short block per instrument. The webhook URL is a secret and
is sent only to discord.com.

Test without orders: `powershell -ExecutionPolicy Bypass -File scripts\scheduled-run.ps1 -DryRun -Force`.

## Calendar reminder

The application's US calendar covers one year at a time (`UsEquityCalendar`).
From 1 December, every scheduled report starts with a warning until the next
year's dates are in that file. If the current year has no dates at all, every
report says new entries are blocked. See [market hours](market-hours.md).
