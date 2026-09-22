# Engineering and verification

The active implementation uses Java 25 and Gradle 9.7.1, managed locally by vfox.
See [operations](docs/operations.md) for setup and [architecture](docs/architecture.md)
for code ownership. Dependencies are pinned with Gradle configuration locks.
The [implementation plan](docs/IMPLEMENTATION-PLAN.md) was committed before code.

## Checks

Order-result correction, 22 September: **208 tests pass**, instruction coverage
**12,459/13,904 (89.61%)**, branch coverage **1,683/2,079 (80.95%)** and
**982/1,088 PIT mutations killed (90.26%)**, with 90 survivors, 16 uncovered
and no timeout/error kills. `gr spotlessApply check pitest installDist orderAudit`
passed through the vfox environment. Fixtures verify terminal zero-fill buys
continue to other cycles without consuming the opening; uncertain/partial results
still halt; rejection details survive parsing; the CLI returns incomplete-work
status with a NOT FILLED block; and later confirmed holdings are not bought twice.
The read-only order audit confirmed eToro rejection 1065 with zero executions.
No financial writes were made, and the broker-side technical failure is not
claimed resolved. See [broker evidence](docs/broker-contract.md#observed-order-rejection-22-september-2026).

Latest quote-refresh verification, 22 September: **205 tests pass**, **90.45%
instruction coverage** (12,069/13,343), **81.20% branch coverage** (1,620/1,995),
and **967/1,073 PIT mutations killed (90.12%)**. There are 90 survivors and 16
uncovered mutations, with no timeout/error counted as a kill. Streaming classes
were added to PIT's scope; gates are unchanged. Formatting and packaging pass.
Both REST and WebSocket boundaries are injected in tests, including mutation
runs. Coverage includes same-run refresh, final snapshot retry, ceilings and
trading suspensions after refresh, stale account preflight, fragmented/oversized
messages, initial socket demand, authentication, interruptions and late-connection
cleanup. Two integrated live dry runs found BRK.B/EOG/ADI ready; ARGT still lacked
a fresh quote after recovery. See [quote refresh](docs/QUOTE-REFRESH.md).

Latest instrument-availability verification, 22 September: **195 tests pass**,
**90.95% instruction coverage** (11,461/12,601), **80.91% branch coverage**
(1,551/1,917), and **921/1,018 PIT mutations killed (90.47%)**. There are 83
survivors and 14 uncovered mutations, with no timeout or execution error counted
as a kill. Formatting, coverage gates and distribution packaging pass unchanged.
New fixtures cover both accounts' opening permissions, lookup-only identities
never authorizing orders, changed identity, exact/US ticker candidates, incomplete
lookups, documented no-match 404 versus service failure, configuration collisions,
and ADI's deployed alias through quote and pre-submission rechecks. Two full live
plans submitted no orders; see [availability evidence](docs/INSTRUMENTS-2026-09-22.md).

Latest local state-checkpoint verification, 21 September: **189 tests pass**,
**90.74% instruction coverage** (11,183/12,324), **80.31% branch coverage**
(1,497/1,864), and **895/993 PIT mutations killed (90.13%)**. Formatting, coverage
gates and packaging pass, with no timeout/error counted as a kill. The Git adapter
is tested in isolated temporary repositories: full ledger/history restoration
including an unresolved order reference, preserving unrelated staged work,
no-op repeat checkpoints, credential/transient-file exclusion, lock conflicts,
corrupt state and Git failure without journal replacement. The adapter has
JUnit/JaCoCo coverage; the existing financial/persistence PIT scope is unchanged.
`gr plan --dry-run` confirms the Gradle finalizer without contacting services.

Earlier paragraph-report verification, 21 September: **187 tests pass**, **90.89%
instruction coverage** (10,890/11,982), **80.51% branch coverage** (1,475/1,832),
and **895/993 PIT mutations killed (90.13%)**. There are 84 survivors and 14
uncovered mutations; no timeout/error counted as a kill. Formatting, coverage
gates and distribution packaging pass. Two full live dry runs displayed the
paragraph output without submitting orders. CLI tests execute against a synthetic
broker and plan the next day, proving no duplicate opening and an unchanged-holding
paragraph. Mixed-holding tests prevent a held/new action from also being described
as unchanged. Quote tests cover missing, stale, future, wrong-currency and
non-executable quotes. The surviving diagnostic age-boundary mutation is guarded
by the unchanged policy freshness test before that explanation is reached.
`ReportFormatter` is presentation code covered by JUnit/JaCoCo; the existing
financial PIT target scope and gates are unchanged.

Earlier cost-response correction, 21 September: **181 tests pass**, **90.53%
instruction coverage** (10,530/11,631), **79.72% branch coverage** (1,411/1,770),
and **882/980 PIT mutations killed (90.00%)**. There are 84 survivors and 14
uncovered mutations, with no timeout or error counted as a kill. Formatting,
coverage gates and distribution packaging pass; no gates changed. Regression
tests cover new requests per amount, old generated figures, future timestamps,
wrong instruments, backward clocks and the exact 60-second request boundary.
An earlier PIT run was interrupted when the owner answered the cost-policy
question; these results are from the completed replacement run.

Two corrected full live dry runs completed without orders. The second had all
five configured instruments READY; MAGS/IBIT/ETHA remain unavailable or unconfigured.
See [the current result](docs/READINESS-2026-09-21.md#after-the-cost-correction).

Earlier verification, 21 September (configured owner-run path): **180 tests pass**,
**90.68% instruction coverage** (10,450/11,524), **79.90% branch coverage**
(1,403/1,756), and **879/978 PIT mutations killed (89.88%)**. There are 85
survivors and 14 uncovered mutations; the final run has no timeout or run error
counted as a kill. An earlier run had one child-process error and was superseded
by this clean run. Gates are unchanged. Formatting and `installDist` pass.

New regression coverage checks capped limits at depressed prices, the exact 10%
pre-submit boundary, bounded read-back polling, interruption/kill persistence,
no resubmission while waiting, per-instrument data failures, credential-free
read diagnostics and the deployed configuration's ability to prepare a synthetic
eligible order. The default no-wait polling boundary survivor was caught and
killed with an additional observation-count assertion.

Calendar regression tests cover holidays, early closes, daylight saving changes,
session boundaries and refusal outside the published year. All 11 calendar and
34 configuration mutations are killed. CLI regressions also verify held-copy and
unexplained-account outcomes return exit code 2, and confirmed partial fills report
the actual amount.

The full live dry run found eligible BRK.B and ARGT entries; other candidates
remain held for stale costs or unavailable instruments. See
[current readiness](docs/READINESS-2026-09-21.md). These results do not prove a live fill.

```powershell
. ./env.ps1
gr spotlessApply check pitest installDist
gr appHelp
```

`check` includes JUnit, formatting and JaCoCo gates: 85% instruction and 75%
branch coverage over all production Java. PIT has an 85% mutation-score gate
over domain policy, source reconstruction/parser, persistence, orchestration,
executor, broker adapter and payloads. CLI, HTTP transport and HTML navigation
have separate tests but are not mutation targets. Gates are floors, not proof
of correctness; survivors and external broker semantics still need review.

Reports: `build/reports/tests/test/index.html`,
`build/reports/jacoco/test/html/index.html`, `build/reports/pitest/index.html`.
JUnit uses synthetic fixtures, temporary state and loopback HTTP. It never loads
project credentials or submits real/demo orders. See the
[constraint review](docs/constraint-review.md) for coverage and limitations.

Verified after instrument discovery and independent owner eligibility on
21 September 2026:

- **142 JUnit tests passed**. Discovery tests use synthetic responses and reject
  malformed/incomplete metadata without requesting trading endpoints.
- JaCoCo: **90.57% instructions** (9,761/10,777), **79.18% branches** (1,297/1,638).
- PIT: **800/902 killed (88.69%)**, 87 survived, 15 uncovered; no timeout/error
  counted as a kill. All mutations of the extracted opening-configuration check
  were killed. The pre-existing exact-60-second cost-age boundary survivor remains.
- Formatting, coverage gates and `installDist` passed. The diagnostic CLI is
  covered by unit tests for its discovery/search behavior, but is not a PIT target;
  the financial broker adapter remains a mutation target. Gates are unchanged.
- Live read-only name search resolved the ETHA ticker collision to ETHA.US.
  Both accounts disallowed opening that fund and IBIT. Details and reproducible
  Gradle commands are in the [instrument investigation](docs/INSTRUMENTS-2026-09-21.md).
  These checks did not establish copy-side execution guarantees or enable trading.

Subsequent precision validation removes guessed price/unit scale defaults and
rejects omitted or null precision fields. All 142 tests and packaging pass;
coverage is 9,764/10,780 instructions (90.58%) and 1,298/1,638 branches (79.24%).
PIT passed again at 800/902 kills before the final null-deserialization check;
that final change is in configuration loading, outside the unchanged PIT target
set, and was verified by the full test suite. No gates were lowered.

Verified after the recovery fixes on 21 September 2026:

- **114 JUnit tests passed**, including 25 additional regression cases.
- JaCoCo: **91.32% instructions** (9,465/10,365), **79.20% branches** (1,253/1,582).
- PIT: **796/899 killed (88.54%; PIT displays 89%)**, 88 survived, 15 uncovered.
  No timeout or error was counted as a kill. Gates remain 85% instruction,
  75% branch and 85% mutation coverage; no exclusions were added.
- New tests prove accepted-source durability, private rejected evidence, safe
  legacy-orphan recovery, known-fill protection repair and refusal cases,
  explicit blocked-close reports, and cancellation/receipt persistence across
  restarts. The original characterization tests were replaced by regular tests.
- `gr appHelp` and operation discovery worked; `gr` also worked from another
  directory and propagated a deliberately failing Gradle invocation's exit code.
  No live operation was invoked. Java execution uses only fixtures/temp state.
- Formatting, coverage verification and distribution packaging passed.

Verified 19 September 2026 on the final Java sources:

- **86 JUnit tests passed**, no failures.
- JaCoCo: **90.56% instructions** (8,691/9,597), **77.62% branches** (1,113/1,434).
- PIT: **714/814 mutations killed (87.71%; PIT displays 88%)**, 84 survived,
  16 had no coverage. All configured gates passed; no timeout counted as a kill.
- Spotless checks and `installDist` succeeded; the packaged CLI help command ran.
- PowerShell diagnostic fixtures passed; no network calls in those checks.
- Current documentation links and `git diff --check` passed. Password/API-key
  scanning found no secret values in candidate tracked text files.

## Read-only evidence

Price-risk policy update, 21 September 2026: **147 tests pass**. Coverage is
**90.54% instructions** (9,873/10,904) and **79.37% branches** (1,316/1,658).
PIT is **835/937 killed (89.11%)**, with 87 survivors and 15 uncovered; no timeout
or error counted as a kill. `Configuration` is now a mutation target, and all
32 of its mutations are killed. Regression tests verify that accepted copy-price
risk does not bypass sizing or stops, that copied overpayment remains unresolved,
and that restarting cannot submit more purchases or a corrective sale. Formatting,
coverage gates and distribution packaging passed; no broker orders were submitted.

The [21 September independent review](docs/REVIEW-2026-09-21.md) identified four
recovery/reporting defects: source-state poisoning, protection blocked
by an unresolved fill, a silently held conflicting close, and a kill-cancelled
close that cannot resume. These now have fixes and regular regression coverage in
`RecoveryTest`, `SourceBookTest`, `MainTest` and the existing executor/broker tests.
Original defect probes are retained in Git history at `d57e494`; they are not
current passing-behaviour tests. The review preserves Fable's original opinion.

The [21 September rehearsal](docs/REHEARSAL-2026-09-21.md) found and corrected the
live `value` versus documented `amount` cost-component mismatch and extended the
deterministic parser for explicit older prose forms. The updated suite passes
**89 tests**, **90.70% instruction / 78.09% branch coverage**, and PIT **724/824
killed (87.86%; displayed 88%)**, with 84 survivors and 16 uncovered mutants.
The 19 September totals above are retained as historical evidence.

The private capture from 19 September contains 143 Tactical articles starting in
May. All parsed and reconstructed the 15 current dashboard holdings and weights.
This is one observed corpus; unfamiliar wording is held. Full member content
stays ignored. Replay it offline:

```powershell
gr replayCapture
```

The read-only Java broker diagnostic refreshes identity, owner-agent linkage,
equity, positions and pending collections:

```powershell
gr brokerDiagnostics
```

Latest observation: $4,610 owner equity/cash, $10,000 internal equity/cash, zero
positions, active allocation, complete empty order collections. `/portfolio`
provides collections missing from `/real/pnl`. This does not verify copied-order
sizing, stop propagation or copied fill ceilings. Those remain
[broker contract gaps](docs/broker-contract.md).

The legacy PowerShell launchers/diagnostics and their helper tests were removed;
Gradle invokes the Java diagnostics and JUnit covers their account validation.
`env.ps1` only activates tools and the `gr` alias; there is no application scripting.
Retired Python planning helpers/tests are under `docs/archive/planning-tools/`.
They are not application dependencies. Do not lower gates to make a change pass.
