# Implementation and verification work

Updated 2026-09-19. These are engineering responsibilities, not questions about
the user's preferred strategy. Confusion in a form answer is not endorsement.

## Local changes

- Preserved 49 form answers and three follow-ups; updated effective policy/skill.
- Allowlisted minimal ledgers, history and reports for local Git; secrets and
  private broker snapshots remain ignored. Set repository-local author identity.
- Fixed close-order counts: `ordersForClose`, `ordersForCloseMultiple` and general
  `orders` are distinct; absent/malformed collections are unknown. Basis: official
  OpenAPI `ClientPortfolio` schema cached privately on 2026-09-19.
- Added sanitized owner diagnostic stage/code/field errors for credentials,
  transport, HTTP, JSON, fields, scopes, matching and amounts.
- Added a dated correction to historical stop/price/sizing guidance, retaining
  the original closed-market observation.
- Implemented state claims, OS serialization, reference/history validation,
  hashing, prior-generation retention, atomic replacement and read-back. These
  helpers contain no trading executor or strategy decision engine.

## Checks performed

Thirteen isolated Python tests exercise corrupt-ledger preservation, independent-process claim collision,
wrong-owner release, mutex contention, commits/read-back/history, repeated commit,
interrupted replacement/retry, changed base, incomplete coverage, immutable
evaluations, broken references/duplicate intents, invalid JSON/amounts and hashes.
No operational ledger or initial baseline was created.

PowerShell fixtures cover general/close order separation, missing versus empty
collections, structured errors and suppression of arbitrary exception text.

Live GET refresh at 07:57–07:58 UTC on 2026-09-19: owner identity/link/funding
checks all returned 200; net contribution and cash are **$4,610**, with no positions
(initial $500 + subsequent deposits $4,110, no withdrawals). Agent identity and
portfolio GETs returned 200, with $10,000 internal credit and zero positions or
pending orders. Agent-scoped portfolio listing still returns the expected 403;
the owner listing succeeds. This is not evidence of top-up behavior with holdings,
agent-to-copy order sizing or stop propagation. Skill frontmatter and PowerShell
syntax validation also passed.

Repeat from the root with Python 3.11+ and PowerShell 7+:

```powershell
python -m unittest discover -s tests -p 'test_*.py' -v
pwsh -NoProfile -File tests/test-etoro-diagnostics.ps1
```

## Remaining verification

| Items | Work and present limitation |
| --- | --- |
| V01 | Establish real-equity fields and agent-to-copy sizing after top-ups. A funding GET is not sizing proof. Use docs/read data, then independently authorized observed fills if needed. |
| V02 | Keep cash-only top-up guidance attributed. Compare units/orders at a future user-initiated top-up with positions; do not initiate one to test. |
| V03, F05 | Validate exact stop modes and propagation to actual copied positions. Empty holdings cannot prove this; asynchronous acceptance is not setting read-back. |
| V04 | Establish Agent Portfolio liquidation semantics of mirror stop fields; do not infer them from names or change settings. |
| V05, V06 | Check account-specific exact asset eligibility, non-leveraged treatment, currency, minimums, fees, precision and partial closes. |
| F02, F03 | Implement complete documented order/fill history and agent-to-copy ID associations. The skill requires both observations; current diagnostics do not implement complete reconciliation. Counts do not prove fills. |
| D07 | Verify an entry method that enforces the ceiling; do not assume a market or similarly named order suffices. |
| A04, D05 | Research Bravos target-quantity convention; until known, use explicit quantities/actions without invented fractions. |
| V07 | Supervised user-launched chat with existing login; unattended persistence/access deferred. No schedule or scraper exists. |
| V08 | Exercise the English workflow with source fixtures: duplicates, edits, 45-day gap, partial scans, stale quotes and ambiguous/partial fills. Helper tests cover only file/data invariants. |

Record dated evidence, not questionnaire acceptance, as verification. If a check
needs an existing position or future top-up, state that dependency; do not perform
a financial action simply to manufacture test data. Continue independent checks.
