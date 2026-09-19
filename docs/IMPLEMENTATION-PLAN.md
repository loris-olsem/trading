# Standalone Java trading application — implementation plan

Status: planned before implementation, 2026-09-19. The owner has requested Java,
Gradle, vfox, coverage, PIT mutation testing, staged commits and documentation
cleanup. The owner selected deterministic parsing with ambiguous alerts held
for review. This supersedes the Python implementation direction in the existing
untracked draft; that draft and its code will be preserved during migration.

## Product and commands

A local command-line application reads Bravos Tactical alerts, reconstructs
source cycles, reconciles the existing eToro Agent Portfolio and owner copy,
applies the agreed policy and records every action durably. No model runs in
the execution path. The owner launches the executable; an external scheduler
can later invoke the same command. This work does not install a schedule.

- `plan`: collect and assess; no broker writes. Optional historical lookback is
  research only and never activates trading.
- `initialize`: explicitly establish the opening enrollment/discovery baseline,
  with a stated lookback (default 30 calendar days); no broker writes.
- `run`: owner-invoked execution under configured rules, without per-order prompts.
- `status`: show baseline, unresolved submissions, holdings and blockers.
- `early-exit`: record the owner's full/partial exit instruction against a cycle;
  execution uses the same durable intent path, never a separate broker shortcut.
- `capture`: read-only source capture for local replay and diagnostics.

No-argument/help invocations perform no network activity. Tests and builds never
load real credentials. The assistant will not invoke `run` against a broker,
including as a validation shortcut.

## Architecture

One Java 25 application with Gradle 9.7.1; both pinned with project-scoped vfox,
plus a checked-in Gradle wrapper and dependency versions. Use BigDecimal for
all prices, money, weights and units. Inject clock/transport for repeatable tests.

1. **Source adapter:** restricted-host authentication, bounded archive traversal,
   stable post identities, date-only boundary overlap, final first-page recheck,
   complete authored-body hashes, revision retention and dashboard reconciliation.
   Active/unresolved articles reread every run; historical revisions audited on
   the next invocation after seven days. Login/parse/page failures are not empty
   successful discovery. Unknown relevant wording blocks affected actions.
2. **Parser and source book:** recognize labelled openings and explicit prose
   adds/reductions/closes/stop changes; preserve bundled instructions and original
   cycle identity. No ticker-only merging of closed/reopened cycles. Corrections
   require resolution, never automatic reversal or duplicate execution.
3. **Pure policy:** original opening +2%; additions to held positions use their
   own source price with zero tolerance; late entries use current weight once.
   Source weights apply to current real owner equity, never internal agent cash.
   Trims use fractions of linked units; funding alone produces no orders.
   Follow exact latest stops and explicit target quantities; no invented thirds.
   Source close/our full early exit/our stop exit terminate the appropriate cycle.
   Reconcile before exits/reductions, protection, then new exposure.
4. **eToro adapter:** documented endpoints with agent/owner credential separation,
   exact identity mapping, unleveraged instrument eligibility and USD conversion,
   fresh exchange-open asks, costs/minimums/precision and order/position read-back.
   Open with `limitIOC` and an explicit fixed stop; a market-if-touched order does
   not enforce a fill ceiling. Separate close-by-units and stop-update endpoints.
   Copy sizing and stop propagation must have evidence; unsupported/unknown
   capabilities block dependent execution rather than silently guessing.
5. **Durable state/executor:** OS-level exclusive lock, versioned state, atomic
   replace and write-ahead intent journal forced to disk before network writes.
   Stable intent/reference IDs, no blind write retries. Lost responses and partial
   fills reconcile before a later attempt. Broker acceptance is not a fill;
   copied-position stop read-back is required. Unknown close outcomes stay blocked
   if no documented correlation route resolves them. A kill file prevents writes.
6. **CLI/reporting:** useful blockers and next actions; no credentials or broad
   owner dumps. Runtime broker IDs stay in ignored local state. Export minimal
   audit summaries separately. Existing planning ledger remains historical
   evidence, never silently imported as executed positions or activation.

## Implementation commits and acceptance

1. Commit this plan, build pins and verification strategy before domain code.
2. Build toolchain and pure policy/parser with boundary and regression tests.
3. Durable workflow and source/broker adapters with local HTTP contract tests,
   timeout/crash/replay/partial-fill scenarios and an offline end-to-end run.
4. CLI integration, coverage/PIT analysis, correction of surviving material
   mutations and a constraint-by-constraint review. Record actual reports and
   limitations; never substitute mocked success for observed broker behavior.
5. Convert the skill to ordinary source/policy/state docs, remove its discovery
   entrypoint, archive the earlier Python draft, and replace README with a short
   purpose/setup/commands/documentation index. Preserve decisions, historical
   reports and the existing ledger; do not publish secrets or account identifiers.

## Verification strategy

JUnit covers policy thresholds, rounding, stop inheritance, discovery gaps,
duplicate/revised alerts, ambiguous prose, cash reservations, terminal cycles,
funding changes, early exits, broken state and submission recovery. Local HTTP
servers verify real serialization/routing/redirect restrictions and response
handling without broker credentials. Temporary directories isolate state tests.

JaCoCo reports all production code, with gates of 85% instruction and 75% branch
coverage. PIT mutates parser, policy and execution/state behavior with an initial
85% mutation-score gate. Review every survivor in financially consequential
logic; document equivalent mutants rather than concealing weak tests behind
broad exclusions. Keep integrations visible in coverage even when mutation scope
focuses on decisions and recovery. `check` and `pitest` must run successfully.

## Known uncertainties to resolve during implementation

- Real owner-to-agent copy sizing after funding changes; no arbitrary $500/$10k ratio.
- Exact per-instrument eligibility, order increments, fees and minimums.
- Stop propagation and copy-level liquidation semantics are distinct contracts.
- Pending collection completeness and correlation of uncertain close/stop requests.
- Full raw authenticated articles are not in the existing ledger. Synthetic
  fixtures derived from observed facts are not a full-page parser validation.
  Capture/replay and realistic authenticated-page fixtures must expose that gap.

Current API evidence is the official eToro OpenAPI schema captured September 19
under ignored `state/`; source contracts will be retained without private data.
Build sources: [Gradle releases](https://gradle.org/releases/),
[vfox project scope](https://vfox.dev/guides/quick-start.html),
[PIT Gradle plugin](https://plugins.gradle.org/plugin/info.solidsoft.pitest).
