# Engineering and verification

The active implementation uses Java 25 and Gradle 9.7.1, managed locally by vfox.
See [operations](docs/operations.md) for setup and [architecture](docs/architecture.md)
for code ownership. Dependencies are pinned with Gradle configuration locks.
The [implementation plan](docs/IMPLEMENTATION-PLAN.md) was committed before code.

## Checks

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
