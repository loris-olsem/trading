# Bravos trading program

An owner-operated Java application that follows Bravos **Tactical** alerts on its
website and places eligible orders through the existing eToro Bravos Agent
Portfolio. It parses supported alerts deterministically, keeps a durable journal,
reconciles the agent with the owner's real allocation, and applies the agreed
price, sizing and stop rules. Gmail and an AI assistant are not runtime dependencies.

**Readiness:** implemented and tested with isolated fixtures and read-only service
checks. No trades, initial enrollment or schedule have been created. Purchases
remain blocked by unverified copy capabilities and empty asset profiles in
[`config/trading.json`](config/trading.json). In particular, an agent limit order
does not yet establish a hard ceiling on the owner's copied fill. See the
[broker contract](docs/broker-contract.md).
The [independent review](docs/REVIEW-2026-09-21.md) also identified four unresolved
recovery/reporting defects that must be addressed before live use.

## Start here

Latest validation: [Monday rehearsal, 21 September](docs/REHEARSAL-2026-09-21.md),
including live read-only account/eligibility checks and defects found and fixed.

| Document | Purpose |
| --- | --- |
| [Operations](docs/operations.md) | Install, configure, plan, initialize, run and recover |
| [Trading rules](docs/trading-rules.md) | Effective decisions in a compact table |
| [Architecture](docs/architecture.md) | Discovery, policy, execution and state ownership |
| [Broker contract](docs/broker-contract.md) | API boundaries and outstanding verification |
| [Constraint review](docs/constraint-review.md) | Requirements mapped to code and tests |
| [Independent review](docs/REVIEW-2026-09-21.md) | Major findings, reproduction probes and unresolved fixes |
| [Engineering](ENGINEERING.md) | Build, coverage, PIT and repeatable checks |
| [Policy provenance](PLANNING.md) | Detailed agreements and original decision records |
| [Agent instructions](AGENTS.md) | Rules for repository maintenance |

After installing the pinned tools through vfox, from the repository root:

```powershell
.\scripts\build.ps1 check pitest installDist
.\scripts\trading.ps1 help
.\scripts\trading.ps1 plan
```

`plan` reads current data and saves evidence without submitting orders.
`initialize` establishes the one-time enrollment window without trading.
The owner's `run` command submits eligible trades; it is not proposal-only.
Read the operations guide before invoking it. No schedule is installed.

## Layout and historical evidence

- `src/main/java`, `src/test/java`: current application and isolated tests.
- `config/`: non-secret settings; `.vfox.toml` and wrapper pin toolchains.
- `secrets/`: ignored credentials, restricted to their intended service.
- `state/runtime/`: ignored authoritative journal, history and kill switch.
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
