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
. ./env.ps1
gr check pitest
```

Dot-source `env.ps1` in each PowerShell session. It activates vfox, reads the pins
from `.vfox.toml`, selects project SDKs and defines the `gr` alias. It does not
change global tool selections or your PowerShell profile. `gr` invokes the pinned
Gradle with this repository as project directory, even from a different directory,
and preserves its exit code. Gradle compiles current Java sources before operations.
Use `gr tasks --group bravos` to list operations; `gr help` shows Gradle help.

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
gr appHelp
gr status
gr plan
gr plan -Psince=2026-08-21
gr initialize -Psince=2026-08-21
gr earlyExit -Pcycle=bravos:post:12345 -Pfraction=0.25
gr brokerDiagnostics
gr instrumentAudit -Psymbols=CF,EOG
gr instrumentAudit '-Pquery=iShares Ethereum'
gr watchlistMetadata
gr capture -Psince=2026-08-21
gr replayCapture
```

`plan` saves evidence/reports and reconciles read-only. Before initialization it
uses the last 30 calendar days by default; a research backfill does not expand
enrollment. Once initialized, the persisted floor/checkpoint controls processing;
`-Psince` does not reset it. `brokerDiagnostics` reads both accounts;
`capture` acquires Bravos pages; `replayCapture` reads only the saved private capture.
`instrumentAudit` reads symbol metadata and both accounts' opening eligibility;
`-Pquery` instead searches names and symbols for identity review. Its private
output does not configure or approve instruments. See the
[instrument investigation](INSTRUMENTS-2026-09-21.md) for the ETHA symbol collision.
`watchlistMetadata` reads existing owner lists without creating lists or adding
assets. It saves an ignored private response for investigating available currency
and precision fields; it does not supply missing fields or change execution profiles.

The owner chooses `gr initialize` (same optional `-Psince` syntax) to establish the
one-time baseline without orders. The owner's subsequent `gr run` executes eligible
actions. There is no per-trade confirmation and no installed schedule.

`gr earlyExit -Pcycle=CYCLE -Pfraction=FRACTION` records a durable request for the next owner `gr run`.
CYCLE is the opening event key, FRACTION is `(0,1]`: `0.25` means a quarter and
`1` means all. Duplicate pending requests for one cycle are rejected. The program
verifies completion and never restores sold units to a target weight.

## Stop and recover

Use `gr kill` to create `state/runtime/KILL` and stop further submissions. It does not cancel an
existing order or close holdings. The executor checks before preflight and after
saving the attempt. Use `gr resume` only after resolving the stopping reason;
it removes the marker without starting a trading run.

`status` is local-only and shows reports and attempts, including partial-fill
shortfalls. The private journal contains broker IDs and saved UUID references.

| Condition | Recovery |
| --- | --- |
| Unknown/unresolved order | Inspect saved reference/order and broker history. Opening lookup may resolve on the next invocation. Never delete an attempt to retry. |
| Lost close response | Reconcile close history and both accounts. No automatic resubmission. |
| Filled agent position missing its stop | Next owner run may repair only known PARTIAL fills with matching source stop, fresh complete accounts, no pending broker orders and verified lot links. No purchases are allowed during recovery. Dry runs only report the problem. |
| Owner copy missing its stop | The report names the copied position and required stop. Owner intervention is required if propagation fails; the program has no owner-side write credential. Do not assume an agent stop acknowledgement fixed the copy. |
| Other copy/stop pending | Leave attempt intact for next read-back. Terminal protected partial fills are confirmed, never topped up. |
| Incomplete source scan | Fix the parser/read failure and rediscover. Accepted source state and checkpoint are unchanged. Rejected observations and reasons remain private under `state/runtime/source-review/`. |
| Legacy orphan from an incomplete scan | A complete corrected scan can replay an unattached, unrevised orphan and validate the dashboard; execution history is preserved. |
| Source mismatch/material revision | Inspect the rejected evidence and resolve source facts. Material corrections are not automatically accepted; evidence-backed maintenance and tests are still required. Never advance the checkpoint or erase attempt history by hand. |
| Kill cancelled an attempt before submission | Remove the kill only after resolving its cause. Next owner run may create a new reference for the proven unsubmitted batch member. Confirmed members are never repeated; unknown outcomes are never retried. |
| Unexpected units/stops | Compare journal and both accounts; preserve protection. Do not create replacement positions to mask it. |
| Corrupt journal | Preserve file/history. Restore a verified generation only after reconciling later broker activity. |
| Lock held | Wait for its process to exit; do not bypass it by deleting the lock file. |
| Network/broker failure | Inspect status; next invocation reconciles before new actions. No blind write retry. |

Source failures block new decisions, including unsubmitted early exits.
Reconciliation runs before source login; existing broker stops operate independently.
Unknown close outcomes and material source corrections still need evidence-backed
maintenance changes, tested before execution. A conflicting close is reported as
BLOCKED with its before/after source weights and retained agent units; it cannot
silently become “nothing to do.”

Completed plans exit successfully, including when every candidate is blocked:
the printed report and summary distinguish readiness from successful evaluation.
`INSTRUMENT_UNVERIFIED` requires a verified asset profile and broker eligibility;
an empty `assets` map blocks every symbol and does not prove broker unavailability.
Live execution retains exit code 2 for blocked or unresolved work (including
protection incidents); code 1 means an operation failed, including a failed plan
scan or API call. Gradle reports nonzero codes as a failed task; inspect the
application output and `gr status`. A protection repair
ends that invocation without new purchases; review read-back before the next run.

Authority: `state/runtime/ledger.json`, history and kill switch. Minimal exports:
`state/bravos/runtime-ledger.json`, `history/runtime-*.json`, `runs/runtime-*.md`.
Raw captures and account diagnostics stay ignored. Successful commands export;
after failure, inspect local `status` and the private journal. The program does
not run Git commits; review and commit only minimal allowlisted projections.
