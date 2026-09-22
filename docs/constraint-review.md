# Constraint review — updated 22 September 2026

This reviews the implemented program against policy `2026-09-22.1`. Original
answers and their dispositions are preserved in [PLANNING.md](../PLANNING.md).
Passing local tests establishes program behavior against fixtures, not eToro's
live copying guarantees. No trades or funding actions were used as tests.

## Trading and source requirements

| Requirement | Implementation and evidence | Remaining limit |
| --- | --- | --- |
| Tactical website, no email dependency | `BravosSource`, deterministic `AlertParser`; authenticated read-only capture/replay of 143 articles, current 15 holdings matched | Changed layouts/wording stop discovery |
| Initial 30 days separate from research | `Main`, `Workflow.initialBook`; explicit enrollment floor, no trading during initialize; CLI integration tests | Latest local status is initialized; historical reports alone are not activation |
| Routine full gap, deduplication, revisions | Checkpoint-day overlap, post IDs, authored-body hashes, source revisions, active rereads and seven-day audit; source/domain tests | No claim to discover every unseen backdated article |
| Partial scans cannot advance coverage | `acceptScan`, first-page recheck/retry, dashboard match; persisted failure tests | Source errors conservatively block the run's new actions |
| Opening watchlist and +2% | `Policy.opening`; executable ask, floored ceiling, terminal/source-open state tests | Current market mode checks the quote; agent and copied fills may exceed it |
| Never-entered add keeps original ceiling | Opening uses reconstructed current weight exactly once; absorbed event keys tested | No historical adds replayed as separate purchases |
| Held additions use delta and their own price | `Policy.addition`, first-evaluated-session expiry, source ordering tests | New York date is the supported session model; non-US sessions need explicit support |
| Missed add followed by reduction expires | `Workflow.hasLaterReduction`; planning/live tests | Accepted follow-up recorded; no automatic buying of removed exposure |
| Real equity versus internal capital | `EtoroClient.account`, `Policy.buy`; $4,610 vs $10,000 read-only evidence; PnL/cash fixtures | Sourced realized-equity inference; verify every copied amount rather than claim an advance guarantee |
| Funding only creates cash | No funding-triggered action; repeated run and unit-drift tests | Broker-side cash-only behavior is attributed help-bot guidance, untested with holdings |
| Cash, minimums, fees, rounding | Decimal policy; no upsizing/redistribution; fresh eligibility/cost preflight; boundary tests | Five sourced profiles configured; broker restrictions and unsupported instruments remain held |
| Proportional trims, full closes | Actual linked units, rounded-down partials, all-unit full closes; two-lot interrupted reduction test | Unknown close response requires evidence-backed recovery |
| Exact published stops | Parser preserves standalone/bundled changes; exact fixed-stop payload and agent/owner read-back; tests reject trailing/disabled/wrong stops | Sourced copy-stop model configured; actual protection requires position read-back |
| No entry without usable stop | Missing/ambiguous/crossed stop blocks; no invented or widened price | Existing protection is not cleared to resolve a conflict |
| Targets and explicit quantities | Targets retained; no invented fractions; explicit actual reductions supported | Conditional target quantities are held as unsupported, not executed yet |
| Entry price handling | Market payload omits IOC limit/trigger fields; fresh ask checked before submit; historical IOC intents preserved | Owner authorized relaxed execution requirements. Market fills may exceed the check; detected overpayment holds further purchases without automatic sale |
| Final partial fill kept, shortfall reported | Filled internal/owner amounts persisted; event consumed; restart test proves no top-up; later add still possible | Ongoing partial/copy/stop uncertainty blocks until reconciled |
| No old-cycle re-entry after stop/full early exit | Durable terminal cycle and event participation; workflow tests | Unexplained changes held, never silently restored |
| Early exits through program | Durable fraction request, duplicate pending request rejection, next-run processing; CLI/workflow tests | Source failures also block unsubmitted early exits |
| Never leverage or substitute | X1 and long/settlement eligibility, exact asset ID/symbol, required issuer evidence | Current long USD unit-based adapter; unsupported semantics held |
| Reconcile → reduce → protect → expose | Workflow ordering, global publication-order exposure sort; multi-event tests | No routine schedule installed |
| Fresh executable quotes | Accepted official US 2026 calendar, broker tradability, realtime ≤60-second ask, future/stale rejection; pre-submit reread | Calendar requires updating before 2027; no promise that broker fill equals the observed quote |

## Engineering and operations

| Requirement | Evidence |
| --- | --- |
| Java/Gradle through vfox | Java 25.0.4.1+1-tem and Gradle 9.7.1 project pins; wrapper, locked dependencies and reproducible build script |
| Plan before implementation, staged commits | `docs/IMPLEMENTATION-PLAN.md` committed before implementation; Git history records subsequent stages |
| Normal run trades, explicit plan does not | CLI integration with injected mock source/broker verifies modes; real credentials never used by tests |
| Explain each analysed position | Console paragraphs group proposed actions, confirmed outcomes and holds by instrument; quote holds report observed reasons. Next-day and mixed-holding tests prove unchanged holdings are not presented as new openings |
| Durable state and no duplicate submission | OS lock, forced atomic generations, immutable history, saved UUID before write; unknown-response/restart tests |
| Safe partial reduction restart | Original per-lot batch persisted; test interrupts after one lot and proves no second trim of it |
| Credential and owner separation | Secrets ignored; fixed host transport, no redirects for broker, bounded HTTP body, read-only owner scopes; sanitized errors and fixture tests |
| Git evidence and recovery | Minimal projections omit broker IDs/references; owner-requested authoritative ledger/history preserve those links in local Git. Credentials/raw captures remain ignored; checkpoint tests cover restoration and unrelated staged work |
| Skills retired and docs indexed | No repo-local active skill; archived ordinary Markdown/Python preserved; README links current architecture/rules/operations/contracts |
| No hidden automation or activation | No schedule installed or enrollment performed by development; latest owner journal is initialized |

## Verification results and interpretation

JUnit, JaCoCo, formatting, PIT and distribution packaging run through
`gr` after dot-sourcing `env.ps1`. The latest recorded totals are in [ENGINEERING.md](../ENGINEERING.md).
Tests cover execution only against synthetic brokers and loopback HTTP. Java
account validation replaces the retired PowerShell diagnostics. Secret-value scans exclude the existing author
email/contact identity and check actual passwords/API keys without printing them.

PIT is intentionally scoped to financial interpretation, orchestration, execution
and persistence. Surviving mutants include redundant downstream guards, monetary
tolerance boundaries, reporting differences and disk-force/lock-release calls
whose crash durability cannot be established by an in-process unit test. They
are not all claimed equivalent. Review exposed and corrected missing tests for
conflicting source facts, numeric cosmetic revisions, global event order, copied
lot identity, non-atomic account snapshots, partial-fill persistence and two-lot
recovery. No broad mutation exclusions were added to conceal these gaps.

## Readiness decision

**Ready for the owner's first execution.**
Five asset profiles and the sourced operating model replace the empty gates.
The full live dry run, using the explicitly accepted market-calendar rule, found
eligible BRK.B and ARGT orders. The subsequent cost-response correction removes
the implementation's 60-second fee-generation cutoff while retaining fresh
requests and independently fresh prices. Its repeated live dry run found all five
configured entries READY. Unavailable exposure remains held.
See the [readiness check](READINESS-2026-09-21.md). Exact stop and amount
read-back remain mandatory, and no fixture or configuration string proves broker
behavior. The owner accepted possible copied-price slippage; detected overpayment
blocks further purchases without an automatic corrective sale.

Corporate actions need reference-basis reconciliation; no automatic split adjuster
exists. ETHA's announced October reverse split is documented in the broker contract.
Unknown close outcomes and material source corrections require maintenance recovery.
