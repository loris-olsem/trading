# Monday rehearsal — 21 September 2026

Read-only external testing, started about 13:36 Luxembourg time (11:36 UTC),
before the US regular session. No trading command, initial enrollment, broker
order, stop change, funding action or schedule was invoked.

## Account and instrument observations

Documented identity, owner-agent matching, PnL and full portfolio reads verified:

- Real owner's Bravos equity/cash: **$4,610**.
- Internal agent equity/cash: **$10,000**, not additional owner money.
- Zero positions, no pending activity, active allocation; required activity
  collections complete. Optional collections printed as unknown by the diagnostic
  are not treated as zero or used to claim completeness.

Read-only instrument metadata and eligibility calculations were checked for both
the agent and owner credentials. They agreed on these X1 long treatments:

| Instrument | Exact ID | Supported settlement observed | Minimum amount reported |
| --- | ---: | --- | ---: |
| CF | 1890 | Real | $10 |
| BRK.B | 1118 | Real | $10 |
| ARGT | 3421 | CFD, X1 only in this comparison | $10 |
| EOG | 1581 | Real | $10 |

All four reported fractional units and support for initial/editable stops. This
does not establish actual copy propagation, copied-position minimums or exact
decimal precision. Profiles remain unconfigured; no capability evidence was invented.

ARGT's X1 CFD is distinct from holding the ETF directly. The accepted policy allows
unleveraged supported exposure, but CFD treatment and costs must remain explicit.

## Price observations, not executable orders

Sampled about 11:36–11:37 UTC. All four reported **exchange closed**. Some were
marked currently tradable outside regular hours; the program correctly requires
the exchange-open flag as well. `quoteType=realtime` did not imply freshness:
ARGT still carried a Friday timestamp.

| Instrument | Observed ask | Quote timestamp UTC | Existing opening ceiling | Latest reconstructed stop |
| --- | ---: | --- | ---: | ---: |
| CF | $126.50 | Sep 21 11:32:48 | $132.97 | $121.50 |
| BRK.B | $508.73 | Sep 21 11:35:47 | $516.63 | $480.00 |
| ARGT | $92.98 | Sep 18 19:59:39 | $98.36 | $89.00 |
| EOG | $144.83 | Sep 21 11:31:43 | $153.17 | $138.00 |

The four still fell numerically between stop and ceiling in this snapshot.
They are **not executable recommendations**: market hours, fresh quotes/costs,
complete source discovery and the unresolved broker contract still apply.
At unchanged source weights/equity, conditional owner targets remain $184.40,
$368.80, $230.50 and $230.50 respectively. These are not agent order amounts.

## Defects found and corrected

1. **Cost response mismatch.** The saved official OpenAPI `CostBreakdown` schema
   calls the component `amount`; today's live `/api/v2/trading/info/costs`
   response uses `value`. The old adapter would reject the response. It now
   supports either numeric field, rejects conflicting dual fields and rejects
   missing values. A test reproduces the observed shape and checks $1.00 fee +
   $1.17 spread = $2.17 conservative cost allowance. Stale cost estimates remain
   blocked; no stale number was used to create an order.
2. **Older source grammar.** `plan --since 2026-05-01` acquired history starting
   January 31 to reconstruct cycles. The first pass safely rejected 11 February/
   March alerts: prose-only openings, “booking profits” reductions and explicit
   stop-change variants. Those precise forms now parse. Missing/conflicting
   facts still fail; an uncurrency-marked reduction price stays unknown because
   proportional selling depends on explicit weights, not that price. The private
   replay parses all 11 rejected articles with their stated stops/targets intact.

The first failed scan saved partial evidence with a null checkpoint, null initial
enrollment and zero attempts. No scan error was bypassed or manually cleared.

## Final run result

The corrected online `plan --since 2026-05-01` completed successfully at about
13:47 Luxembourg time. Its reconstructed book contains **218 article identities,
81 position cycles and 15 current holdings**, matching the live Tactical dashboard.
The latest observed alert date was **18 September**; no later alert appeared in
the scanned feed. Current weights and stops match the earlier 15-holding review.
Some oldest observations precede the first available opening of their symbol and
are excluded from cycle reconstruction; dashboard agreement is still mandatory.

Every current cycle reported `BLOCKED INSTRUMENT_UNVERIFIED`, as expected with
the intentionally empty profiles. These are holds, not order requests. Runtime
generation 3 has **zero attempts, no entered cycles, no completed execution events,
null enrollmentFloor and null checkpoint**. The broad May research view did not
activate an allocation window. Minimal evidence is in
[`runtime-ledger.json`](../state/bravos/runtime-ledger.json) and the
[run report](../state/bravos/runs/runtime-3.md).

Verification after corrections: **89 JUnit tests, zero failures/errors**;
**90.70% instruction coverage, 78.09% branch coverage**; PIT **724/824 killed
(87.86%, displayed 88%)**, 84 survivors and 16 uncovered. Formatting, coverage
gates, mutation gate, distribution packaging, PowerShell diagnostic fixtures,
changed-document links and password/API-key scans passed. Existing gates were
not lowered. These checks do not prove zero possible defects or live broker
guarantees. The fixes are committed as `2873d20`.

**Readiness: read-only rehearsal passed after fixes; live execution remains
blocked.** Market-open quotes/costs, verified instrument profiles and the copy
contract evidence below are still required. NTRA also remains ineligible because
its $250 source stop exceeds its $225.42 opening ceiling; MAGS remains unmapped.

## Broker uncertainty remains

Today's review of the official [copy mechanics](https://www.etoro.com/copytrader/how-it-works/)
and [Agent Portfolio API](https://api-portal.etoro.com/api-reference/agent-portfolios/create-agent-portfolio-v2)
did not establish a binding owner fill-price cap inherited from an agent limit IOC.
Ordinary CopyTrader documentation supports realized-equity sizing and stop copying,
but is not sufficient evidence for all Agent Portfolio guarantees after funding.
See [broker contract](broker-contract.md) for exact unresolved requirements.

The concrete support question is: **For an Agent Portfolio's new X1 limit-IOC
order, is every owner's copied fill bound by the same limitRate, how is copied
size calculated after later deposits and unrealized PnL, and are fixed stop prices
copied exactly on opening and amendment?** Also confirm cash-only top-ups and
supported price/unit precision. No test trade was used to manufacture evidence.

Private diagnostics and raw member articles are ignored under `state/capture/`.
Only minimal source facts, run evidence and this report are eligible for Git.
