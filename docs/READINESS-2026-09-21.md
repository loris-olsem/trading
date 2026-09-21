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

### Reproduction after the owner's blocked plan

A fresh `gr instrumentPreflight plan` at about 18:13–18:14 UTC reproduced
CF/EOG/SMH cost-age holds. BRK.B remained READY; ARGT passed the separate
preflight but its quote was too old during the full plan. A READY result is
an observation for that run, not a promise for the next invocation.

The 60-second **cost** age cutoff was an implementation choice; the user's
60-second agreement concerns executable **prices**. eToro's
[cost endpoint](https://api-portal.etoro.com/api-reference/trading--real/get-what-if-trading-cost-breakdown)
describes a current hypothetical estimate and `lastUpdated` as the generation
time of the cost figures. It does not specify a 60-second validity window.
Live CF/EOG figures were about 71 minutes old, SMH about two hours old, despite
fresh requests. In response to the proposed use of freshly requested estimates,
the owner instructed us to proceed; the [answer and interpretation](../decisions/2026-09-21-cost-estimates.json)
are preserved. The app now requires a new successful cost request for each amount,
including pre-submission, without treating the figure-generation time as a
60-second expiry. Future/invalid timestamps and stale requests still fail.
The independent 60-second executable-price check is unchanged.

Independent eligibility reads again returned `allowOpenPosition: false` for
IBIT (1367) and ETHA.US (12152) on both accounts. The exact MAGS lookup returned
no instrument; name search returned YMAG, which is not a substitute. These are
broker restrictions or identity gaps, not cost-age failures.

### After the cost correction

The repeated full `gr plan status` at about **18:24 UTC** completed successfully:

| Symbol | Owner amount | Maximum price | Exact Bravos stop |
| --- | --- | --- | --- |
| CF | $184.40 | $132.97 | $121.50 |
| BRK.B | $368.80 | $516.63 | $480 |
| ARGT | $230.50 | $98.36 | $89 |
| EOG | $230.50 | $153.17 | $138 |
| SMH | $230.50 | $598.36 | $535 |

**5 ready, 0 waiting, 3 blocked.** The remaining three are the unavailable or
unconfigured MAGS/IBIT/ETHA instruments. No orders were submitted. Local status
showed `initialized=true`; enrollment had already been performed by the owner,
so it must not be repeated. Live execution refreshes amounts, eligibility and prices.

The first corrected run showed why quote waiting is transient:

After the cost correction, the full plan completed around 18:21 UTC with:

| Symbol | Result |
| --- | --- |
| BRK.B | READY: owner $368.80, maximum $516.63, stop $480 |
| ARGT | READY: owner $230.50, maximum $98.36, stop $89 |
| SMH | READY: owner $230.50, maximum $598.36, stop $535 |
| CF, EOG | Waiting for executable quotes; no cost-age block |
| MAGS, IBIT, ETHA | Unverified profiles; current broker findings described above |

All five configured instruments passed the separate read-only cost preflight.
This run submitted no orders. The earlier two-entry result below is historical.

### Earlier successful candidates

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
resolve the observed cost-age failures. This describes the earlier implementation,
superseded by the cost-response rule above. Prices outside the accepted session
remain unusable. These are external data observations, not evidence
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

Failed cost requests and unavailable instruments remain skipped; their weights are not
redistributed. Actual copy propagation can only be checked after the owner's
execution; the program checks each fill before allowing another purchase.
Calendar coverage currently ends in 2026 and must be updated from published data
before 2027 entries. This is explicit in the operating guide.
