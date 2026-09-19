# Operating the program

Use PowerShell in this repository. Retain and privately back up `state/runtime`
across upgrades. The old `state/bravos/ledger.json` is historical planning state.

## Setup

Install vfox, then its plugins and the pinned tools:

```powershell
vfox add java
vfox add gradle
vfox install java@25.0.4.1+1-tem
vfox install gradle@9.7.1
.\scripts\build.ps1 check pitest installDist
```

The build script activates vfox, selects project versions and uses project SDKs.
It does not change global selections. `.vfox.toml` and the wrapper pin versions.
The launcher uses the built distribution; rebuild `installDist` after code changes.

Existing ignored credentials:

| File under `secrets/` | Purpose |
| --- | --- |
| `bravos/username.txt`, `bravos/password.txt` | Bravos login |
| `etoro-bravos-agent/bravos-public-key.txt` | eToro application key |
| `etoro-bravos-agent/bravos-private-key.txt` | Existing agent key |
| `etoro-main-readonly/private-key.txt` | Owner read-only key |

Never paste values into commands, reports or commits. The program reads them
locally and uses stable error codes instead of HTTP bodies. It logs in afresh
each invocation; no browser session is needed.

Resolve the [broker contract](broker-contract.md) before enabling purchases.
`config/trading.json` stores non-secret capability evidence and `assets`, keyed by
Bravos ticker. Each asset requires exact `instrumentId`, `brokerSymbol`,
`settlementType` (`real` or unleveraged `cfd`), `unleveragedEvidence`, `priceScale`
and `unitScale`. Do not guess or substitute products. Evidence strings record
externally established facts; their presence alone proves nothing. Committed
configuration remains unverified and blocks purchases.

## Commands

```powershell
.\scripts\trading.ps1 help
.\scripts\trading.ps1 status
.\scripts\trading.ps1 plan
.\scripts\trading.ps1 plan --since 2026-08-21
```

`plan` saves evidence/reports and reconciles read-only. Before initialization it
uses the last 30 calendar days by default; a research backfill does not expand
enrollment. Once initialized, the persisted floor/checkpoint controls processing;
`--since` does not reset it.

The owner chooses `initialize` (same optional `--since` syntax) to establish the
one-time baseline without orders. The owner's subsequent `run` executes eligible
actions. There is no per-trade confirmation and no installed schedule.

`early-exit CYCLE FRACTION` records a durable request for the next owner `run`.
CYCLE is the opening event key, FRACTION is `(0,1]`: `0.25` means a quarter and
`1` means all. Duplicate pending requests for one cycle are rejected. The program
verifies completion and never restores sold units to a target weight.

## Stop and recover

Create `state/runtime/KILL` to stop further submissions. It does not cancel an
existing order or close holdings. The executor checks before preflight and after
saving the attempt. Remove the file only after resolving the stopping reason.

`status` is local-only and shows reports and attempts, including partial-fill
shortfalls. The private journal contains broker IDs and saved UUID references.

| Condition | Recovery |
| --- | --- |
| Unknown/unresolved order | Inspect saved reference/order and broker history. Opening lookup may resolve on the next invocation. Never delete an attempt to retry. |
| Lost close response | Reconcile close history and both accounts. No automatic resubmission. |
| Copy/stop pending | Leave attempt intact for next read-back. Terminal protected partial fills are confirmed, never topped up. |
| Source mismatch/ambiguity | Inspect alert/revision, extend parser or resolve chronology, test and rediscover. Do not manually advance checkpoint. |
| Unexpected units/stops | Compare journal and both accounts; preserve protection. Do not create replacement positions to mask it. |
| Corrupt journal | Preserve file/history. Restore a verified generation only after reconciling later broker activity. |
| Lock held | Wait for its process to exit; do not bypass it by deleting the lock file. |
| Network/broker failure | Inspect status; next invocation reconciles before new actions. No blind write retry. |

Source failures block new decisions, including unsubmitted early exits.
Reconciliation runs before source login; existing broker stops operate independently.
There is no automatic state-repair CLI. Unknown close outcomes and material source
corrections need evidence-backed maintenance changes, tested before execution.

Authority: `state/runtime/ledger.json`, history and kill switch. Minimal exports:
`state/bravos/runtime-ledger.json`, `history/runtime-*.json`, `runs/runtime-*.md`.
Raw captures and account diagnostics stay ignored. Successful commands export;
after failure, inspect local `status` and the private journal. The program does
not run Git commits; review and commit only minimal allowlisted projections.
