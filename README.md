# Bravos trading program

An owner-operated Java application that follows Bravos **Tactical** alerts on its
website and places eligible orders through the existing eToro Bravos Agent
Portfolio. It parses supported alerts deterministically, keeps a durable journal,
reconciles the agent with the owner's real allocation, and applies the agreed
price, sizing and stop rules. Gmail and an AI assistant are not runtime dependencies.

**Readiness:** configured for owner execution. Execution profiles cover CF,
BRK.B, ARGT, EOG, SMH and now ADI (eToro's ADI.US). The app checks IBIT/ETHA's
account restrictions and searches for MAGS on every plan, reporting the actual
availability reason. See the [22 September correction](docs/INSTRUMENTS-2026-09-22.md).
Each run reassesses current conditions. No trades or schedule were created during development. See the
[latest readiness check](docs/READINESS-2026-09-21.md) and
[broker operating model](docs/broker-contract.md#operating-model).
The four defects from the [independent review](docs/REVIEW-2026-09-21.md) have
regression-tested fixes. External broker capability questions remain unresolved.

## Start here

Latest code verification: [Engineering](ENGINEERING.md), including the recovery
regressions, coverage and mutation results. Current read-only service evidence:
[readiness check, 21 September](docs/READINESS-2026-09-21.md).

| Document | Purpose |
| --- | --- |
| [Operations](docs/operations.md) | Install, configure, plan, initialize, run and recover |
| [Current execution failure](docs/ETORO-1065-INVESTIGATION.md) | Repeated 1065 rejections, compatibility checks and prepared support report |
| [Latest readiness check](docs/READINESS-2026-09-21.md) | Configured instruments, current broker-data holds and remaining verification |
| [Trading rules](docs/trading-rules.md) | Effective decisions in a compact table |
| [Market hours](docs/market-hours.md) | Accepted US calendar, holidays, early closes and update deadline |
| [Quote refresh](docs/QUOTE-REFRESH.md) | Automatic live-price fallback and bounded retries within the same run |
| [Architecture](docs/architecture.md) | Discovery, policy, execution and state ownership |
| [Broker contract](docs/broker-contract.md) | API boundaries and outstanding verification |
| [Instrument investigation](docs/INSTRUMENTS-2026-09-21.md) | Verified IDs, account restrictions and the ETHA symbol collision |
| [Instrument availability correction](docs/INSTRUMENTS-2026-09-22.md) | ADI support, fresh broker restrictions and automatic listing diagnostics |
| [Constraint review](docs/constraint-review.md) | Requirements mapped to code and tests |
| [Independent review](docs/REVIEW-2026-09-21.md) | Original findings and the implemented recovery fixes |
| [Engineering](ENGINEERING.md) | Build, coverage, PIT and repeatable checks |
| [Policy provenance](PLANNING.md) | Detailed agreements and original decision records |
| [Agent instructions](AGENTS.md) | Rules for repository maintenance |

After installing the pinned tools through vfox, from the repository root:

```powershell
. ./env.ps1
gr check pitest
gr appHelp
gr plan
```

To start trading with the agreed initial 30-day window, run `gr initialize` once,
then `gr run`. The last command submits real orders and rechecks current conditions;
it does not blindly execute a saved plan. Later use `gr plan` for a dry run or
`gr run` for execution. The [operations guide](docs/operations.md) covers holds
and recovery. The configured US calendar requires updating before 2027 entries.

`gr plan` reads current data and saves evidence without submitting orders.
Stale quotes trigger a brief live-stream refresh and a final REST retry within
the same invocation; there is no need to rerun just to request that refresh.
Plans and runs show each analysed instrument in a separate status block, with
labelled amounts, price limit and stop, and explanations wrapped to 76 columns.
Unfilled buys show the broker reason under `NOT FILLED`; confirmed zero-fill buys
do not stop independent opportunities. `gr orderAudit` looks up saved orders
read-only when investigating an execution result.
Confirmed holdings are not bought
again by tomorrow's plan; see [planning after execution](docs/operations.md#planning-after-execution).
`gr initialize` establishes the one-time enrollment window without trading.
State-changing Gradle operations automatically commit the authoritative ledger
and numeric history to local Git, including after failed runs. `gr checkpointState`
does this explicitly. Git must be installed and the repository author configured.
Nothing is pushed; a separate backup or private remote is
still needed for disk loss. See [state checkpoints and recovery](docs/operations.md#automatic-local-state-checkpoints).
The owner's `gr run` command submits eligible trades; it is not proposal-only.
Read the operations guide before invoking it. No schedule is installed.

Dot-source `env.ps1` once per PowerShell session. It activates vfox from
`.vfox.toml` and defines the session-local `gr` alias; it contains no application
operations. `gr tasks --group bravos` lists the operations. Gradle compiles current
sources automatically; a separately built launcher is no longer required.

## Layout and historical evidence

- `src/main/java`, `src/test/java`: current application and isolated tests.
- `config/`: non-secret settings; `.vfox.toml` and wrapper pin toolchains.
- `secrets/`: ignored credentials, restricted to their intended service.
- `state/runtime/ledger.json` and numeric `history/*.json`: authoritative recovery
  state, automatically committed locally after state-changing Gradle operations.
  Lock files, staged writes, rejected-source captures and the kill switch stay ignored.
- `state/bravos/`: minimal Git-reviewable source and decision projections.
  The old `ledger.json` is historical planning evidence, not runtime state.
- `docs/archive/`: retired Markdown skill, Python planning tools and preserved
  alternative Python draft. These are not active entrypoints.

Read-only checks on 19 September 2026 found **$4,610 real owner equity**, all cash,
and **$10,000 internal agent equity**, zero positions. Every run refreshes these.
Historical research: [expanded backlog](BRAVOS-PREPARATION-2026-09-19.md),
[Monday report](MONDAY-TRADE-PLAN-2026-09-21.md),
[earlier 30-day report](BRAVOS-REVIEW-2026-09-19.md),
[funding investigation](ETORO-FUNDING.md). Historical quotes are not executable prices.
