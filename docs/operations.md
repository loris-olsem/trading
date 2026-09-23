# Operating the program

Use PowerShell in this repository. Retain and privately back up `state/runtime`
across upgrades. The old `state/bravos/ledger.json` is historical planning state.

Current [market-order mode](market-order-mode.md) checks the price before buying
but cannot guarantee the final price. Run `gr plan` to inspect proposals, then
the owner can use `gr run`. Keep existing state; no reinitialization is needed.

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

Read the [broker operating model and its limits](broker-contract.md#operating-model).
`config/trading.json` stores non-secret capability evidence and `assets`, keyed by
Bravos ticker. Each asset requires exact `instrumentId`, `brokerSymbol`,
`settlementType` (`real` or unleveraged `cfd`), `unleveragedEvidence`, `priceScale`
and `unitScale`. Do not guess or substitute products. Evidence strings record
sources and any inference; their presence alone proves no execution guarantee.
Six instruments are configured, including ADI mapped to ADI.US. Actual quotes,
eligibility, amounts and stops are checked at runtime. IBIT and ETHA have
lookup-only identities for fresh restriction checks, never order authorization.
Unknown tickers such as MAGS are looked up under the exact ticker and `.US` alias;
a candidate still requires an established execution profile. See the
[availability correction](INSTRUMENTS-2026-09-22.md).
The profiles use the explicitly accepted [US market calendar](market-hours.md)
plus fresh prices and broker tradability. Calendar coverage currently ends on
31 December 2026; a future calendar update is required before 2027 entries.

The configured `copyPricePolicy` is `AGENT_LIMIT_WITH_COPY_CHECK`, following the
owner's latest decision. It still submits capped agent orders and checks the real
copied purchase afterward. `COPIED_PRICE_CEILING_BREACHED` means a copied fill was
above the intended maximum: the attempt stays unresolved and further purchases
stop. Existing protection is preserved; no automatic corrective sale occurs.
This mode accepts a price risk, not a verified broker guarantee, and does not
bypass sizing, stop or instrument checks. The default `REQUIRE_COPY_GUARANTEE`
requires documented `copyPriceCeilingEvidence` instead.

## Commands

```powershell
gr appHelp
gr status
gr plan
gr plan -Psince=2026-08-21
gr initialize -Psince=2026-08-21
gr earlyExit -Pcycle=bravos:post:12345 -Pfraction=0.25
gr brokerDiagnostics
gr instrumentPreflight
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
`instrumentPreflight` reads the configured instruments, hypothetical $100
owner-side cost estimates and current quotes. It never creates an order and saves
private timestamp evidence for diagnosing stale cost/quote responses.
`watchlistMetadata` reads existing owner lists without creating lists or adding
assets. It saves an ignored private response for investigating available currency
and precision fields; it does not supply missing fields or change execution profiles.

The owner chooses `gr initialize` (same optional `-Psince` syntax) to establish the
one-time baseline without orders. The owner's subsequent `gr run` executes eligible
actions. There is no per-trade confirmation and no installed schedule.

For the first use with the agreed 30-calendar-day window:

```powershell
. ./env.ps1
gr plan
gr initialize
gr run
```

The last command trades. Run `initialize` only once; later invocations use `gr plan`
for a dry run or `gr run` to trade. A `WATCH_PRICE` result means the price is too
high; `WAIT_QUOTE` means the market/quote is not executable yet. Neither spends
money. The program checks prices again during `run`, so a plan is not a promise
that a later order will qualify.

After submission, execution can wait up to 90 seconds (plus API request time)
for cached account data to catch up. It never resubmits during that wait. A
confirmed completely unfilled opening can be reconsidered on a later run;
partially filled openings are not topped up. Keep unresolved journal entries.

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

`gr orderAudit` reads the broker's current status for journaled order IDs without
submitting anything or changing the journal. It shows the broker's status, error
code and explanation; raw responses stay in ignored `state/capture/` files.

`gr orderCompatibility` compares hypothetical costs for the latest saved buy
per instrument on agent and owner accounts, using the exact limit-order shape
and a market-order comparison. It only calls the read-only costs endpoint; it
does not submit trades or change state. HTTP 200 is not proof the order can fill.
See the [1065 investigation](ETORO-1065-INVESTIGATION.md).

The current `gr run` submits new buys through v3 asynchronously. No reset or
`initialize` is needed after this upgrade. Existing v2 attempts still reconcile
through the same order lookup. A receipt/HTTP 202 is not a confirmed purchase,
and the app never switches back to v2 or removes the price limit after failure.
This owner-authorized compatibility attempt has not yet demonstrated a live fill.

A terminal buy with confirmed zero fills is shown as **NOT FILLED**, with the
broker explanation and order number in that instrument's block. The run continues
to other opportunities, without retrying that buy in the same invocation. A later
owner `gr run` can try it again after fresh checks. Unknown outcomes, unprotected
partial fills and failed protection/exit operations still hold further submissions.
Exit code 2 (Gradle's red `FAILED`) continues to flag incomplete work, including
unfilled orders; it does not mean the journal failed to save or that no other
order executed. Read each instrument's result.

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
`plan` and `run` print one block per analysed instrument, with a ticker/status
heading and labelled action, owner amount, agent price limit, Bravos stop and
internal funds. Explanations wrap at 76 columns with aligned continuation lines.
Quote holds include the observed
age or other failed checks. Existing holdings with no new action are reported
as unchanged. The private journal and `status` retain diagnostic codes for recovery.
Quote waiting can change between runs; the executable ask must still be realtime
and at most 60 seconds old.
An initially stale quote now triggers an authenticated live-price subscription
(up to 20 seconds), then a final REST retry if needed. Market flags are rechecked
afterward. A remaining hold means the refresh failed too, rather than only the
first snapshot being stale. `gr quoteAudit` diagnoses the stream without orders.
See [quote refresh](QUOTE-REFRESH.md) for the exact sequence and limits.
Availability messages distinguish an absent listing, an application setup gap,
and a fresh eToro refusal on the agent, main account or both. Missing profiles
do not prove broker unavailability, and a discovered ticker cannot authorize a
purchase. `lookupOnlyAssets` is solely for reporting known identities' restrictions.
Live execution retains exit code 2 for blocked or unresolved work (including
protection incidents); code 1 means an operation failed, including a failed plan
scan or API call. Gradle reports nonzero codes as a failed task; inspect the
application output and `gr status`. A protection repair
ends that invocation without new purchases; review read-back before the next run.
`Confirmed by broker read-back` identifies broker-confirmed orders. A run can complete some trades
and still return code 2 because other instruments remain held; the final summary
states this explicitly. Do not interpret Gradle's failure heading as proof that
no trade occurred. Repeated runs reconcile durable attempts before considering
new actions.

### Planning after execution

After an owner `run`, tomorrow's `plan` reconciles actual orders and positions,
then reads Bravos updates since the saved checkpoint. Confirmed openings are
not repeated or topped up because equity or available cash changed. A protected
terminal partial fill is kept without topping up its shortfall. Never-entered
openings remain candidates while Bravos holds them and are checked against the
same original-price ceiling, current weight and latest stop. New additions,
reductions, exits and stop changes are assessed separately. A verified exit ends
participation in that old cycle; it is not automatically reopened.

`plan` can update local source and reconciliation records, but submits no orders
and does not consume a merely proposed purchase as a fill. Uncertain earlier
execution blocks new submissions until reconciled. The next owner `run` refreshes
checks rather than blindly executing a saved plan. Do not initialize again.

Authority: `state/runtime/ledger.json`, history and kill switch. Minimal exports:
`state/bravos/runtime-ledger.json`, `history/runtime-*.json`, `runs/runtime-*.md`.
These generated exports stay local and ignored; they duplicate the checkpointed
runtime journal and are not required for recovery. Historical planning records
remain tracked. Raw review responses, captures and account diagnostics stay ignored. Successful commands export;
after failure, inspect local `status` and the private journal.

### Automatic local state checkpoints

The owner requested versioning the authoritative state. Gradle now finalizes
`plan`, `run`, `initialize` and `earlyExit` with `checkpointState`, on both success
and failure. The task acquires the application lock and commits only
`state/runtime/ledger.json` and existing numeric `state/runtime/history/*.json`.
The complete journal retains broker IDs and order references needed for recovery.
Credentials, raw API/page captures, rejected-source evidence, process locks,
incomplete staged writes and `KILL` remain ignored. Unrelated staged files are
left staged and excluded from the checkpoint commit. No changes means no commit.

To checkpoint explicitly, use `gr checkpointState`. No network or broker calls
are made and nothing is pushed. The task requires this project's Git repository
and a working Git author identity. A lock conflict, corrupt ledger or Git failure
fails the task without changing the trading journal. Fix the cause and retry the
checkpoint; do not repeat a trading run just to fix a Git error. Failed Git work
may leave the intended state files staged. A failed finalizer does not undo trades.

This is **local version history**, not an independent backup: this repository
currently has no remote. Keep any future remote private because the recovery
journal contains financial records. A disk loss still needs a private remote or
backup of the repository to a separate device/location. A hard crash before the
finalizer can leave uncommitted state; the journal's atomic saves remain primary.

For recovery, stop all runs first and retain any surviving newer files. Restore
the ledger and its history from the same known checkpoint into a recovery copy,
then set the local kill switch with `gr kill` before operating on it. `gr plan`
can reconcile read-only while killed. Compare broker history as well as current
positions against the restored attempt records: an old checkpoint might predate
an opening and its later closure, which an empty current portfolio cannot reveal.
Do not initialize, resume or submit orders until that gap is resolved. There is
no automatic rollback command, and Git restore alone does not prove trading state
is current. Never merge conflicting trading journals as ordinary code changes.
