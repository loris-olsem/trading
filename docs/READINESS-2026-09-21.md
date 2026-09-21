# Readiness check, 21 September 2026

The configuration no longer globally disables purchases. CF, BRK.B, EOG, ARGT
and SMH have sourced profiles. The configured copy model is documented with its
inferences and runtime checks in the [broker contract](broker-contract.md).
MAGS has no verified match; IBIT and ETHA were unavailable for opening on both
accounts. No substitute instruments were selected.

The owner-run path is tested with synthetic accounts, including loading the
deployed configuration, calculating an eligible CF order, and performing its
read-only preflight. No broker orders, enrollment or schedule were created.
The subsequent full live dry run found eligible BRK.B and ARGT entries. This
establishes readiness for an owner-run first execution, not a claim of live fills.

## Live read-only result

`gr instrumentPreflight` queried the configured profiles, costs and quotes.
`gr plan` completed source discovery and evaluated all eight enrolled-window
candidates without submitting orders:

| Symbol | Result |
| --- | --- |
| CF | Cost estimate timestamp too old |
| BRK.B | READY: owner target $368.80; internal order $800; maximum $516.63; exact stop $480 |
| ARGT | READY: owner target $230.50; internal order $500; maximum $98.36; exact stop $89 |
| MAGS | No verified instrument match |
| IBIT | Opening unavailable; no execution profile |
| EOG | Cost estimate timestamp too old |
| ETHA | Opening unavailable; no execution profile |
| SMH | Cost estimate timestamp too old |

At about 17:38 UTC, the cost timestamps for CF/EOG were about 64 minutes old
and SMH about 106 minutes old. BRK.B/ARGT cost timestamps were current. A later
read at about 17:41 UTC returned recent quotes for all five, but the search
endpoint still returned `isExchangeOpen: false` and `isCurrentlyTradable: true`.
Requesting the documented `isOpen` field returned no value for it. The owner then
accepted using the [published US calendar](market-hours.md) with a fresh quote and
the separate broker tradability flag. The full plan after that change, completed
around 17:55 UTC, produced the two READY entries above. Amounts and prices will
be reassessed by the owner's run; this plan does not reserve orders or prices.

Adding `Cache-Control: no-cache` to account, cost and eligibility reads did not
resolve the observed cost-age failures. The program still rejects stale costs
and quotes outside the accepted session. These are external data observations, not evidence
that the exchange was actually closed or that a real order would be rejected.

Private raw evidence remains ignored in `state/capture/instrument-preflight.json`.
It contains response timestamps and public instrument data, without request
headers or secrets. Repeat `gr instrumentPreflight` to diagnose reads without a
Bravos source crawl; `gr plan` performs the full dry run.

## Changes made during this check

- Configure five exact instruments and explicitly source the copy operating model.
- Tighten the execution limit when needed to meet eToro's 10% price-deviation
  restriction, while preserving the strategy maximum and exact source stop.
- Wait at most three 30-second intervals for post-order account read-back;
  never repeat a write during waiting. Preserve unresolved outcomes across restarts.
- Hold one instrument with unavailable data while evaluating the other instruments.
- Apply the explicitly accepted US core-session calendar, including holidays,
  early closes, daylight saving and a fail-closed coverage deadline.
- Keep fully unfilled openings eligible for later runs; confirm the existing
  workflow already gives retries distinct durable references. Partial fills are
  retained without topping up.

## First owner use and remaining limits

From PowerShell in the project: dot-source `env.ps1`, run `gr initialize` once
to enroll the agreed initial 30-day window, then `gr run` to execute qualifying
actions. Later `gr plan` remains the dry run and `gr run` remains the live command.
No new credentials, browser login or schedule are required.

Cost-age holds and unavailable instruments remain skipped; their weights are not
redistributed. Actual copy propagation can only be checked after the owner's
execution; the program checks each fill before allowing another purchase.
Calendar coverage currently ends in 2026 and must be updated from published data
before 2027 entries. This is explicit in the operating guide.
