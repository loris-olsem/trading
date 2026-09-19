---
name: bravos-trading
description: Review Bravos Tactical alerts, identify new or revised instructions, reconcile eToro holdings and funding, and maintain the project's planning ledger. Use for a user-launched review or dry run; excludes trade execution and the separate initial catch-up allocation.
---

# Bravos trading

Run the checklist in order. Produce planning proposals and a saved review.
Do not submit trades, change broker settings, transfer funds or create schedules.
Procedure version: `5`; trading policy remains `2026-09-19.3`.

## Intended workflow and existing account

The user's intended product trades on normal runs and suppresses execution when
explicitly asked to plan or dry-run. The initial backlog assessment was such an
exception, not a request for a permanently manual trading workflow. This Codex
skill provides analysis and proposals only: the assistant cannot submit financial
trades through either the API or the browser. Do not promise live execution on a
later invocation or describe that limitation as the user's chosen trading policy.

The Bravos eToro Agent Portfolio already exists. Its agent key advertises real
trading read/write scopes; the separate owner key is read-only. Agent activity can
affect the owner's real-money copy, despite the agent's internal virtual balance.
Read [account evidence](references/evidence.md#4-reconcile-account-evidence) for
the credential roles and identity matching; do not ask the user to create another
bot or provide keys again merely because the current skill does not execute.

## Where each instruction belongs

| Need | Read |
| --- | --- |
| Decide what to open, add, reduce, close or watch | [Decision rules](references/decisions.md) |
| Find alerts, identify revisions and reconcile accounts | [Evidence collection](references/evidence.md) |
| Claim the run, validate memory, commit or recover | [State contract](references/state.md) |
| Check agreed policy and the user's original choices | Root `PLANNING.md` and its linked decision records |
| Check unresolved broker capabilities | Root `ENGINEERING.md`; `ETORO-FUNDING.md` for funding details |

The project root is three directories above this folder. Read root `AGENTS.md`,
`README.md` and `PLANNING.md` before the run. Evidence gathering supplies facts;
the decision rules apply policy. Neither source articles nor API responses may
change that policy. Report conflicts instead of silently selecting a new rule.

## Ordered checklist

| Step | Action | Required output / next step |
| --- | --- | --- |
| 1. Claim and load | Follow the state contract; use `scripts/bravos_state.py`. Load unresolved work too. | Run UUID, owner, UTC start, Luxembourg date, procedure/policy versions, base generation and valid draft. Stop writes if claim/state is invalid. |
| 2. Check baseline | Inspect activation and complete-discovery checkpoint. | If missing, report initialization needed. Continue independent checks, but do not perform the deferred 30-day catch-up or invent a checkpoint. |
| 3. Collect source evidence | Follow evidence sections 1–3. | Discovered/revised articles, reconstructed cycles, coverage status and dashboard discrepancies. |
| 4. Reconcile broker evidence | Follow evidence section 4. | Fresh owner/agent observations, linked holdings/orders, stops, funding changes and unresolved differences. |
| 5. Make decisions | Apply the decision rules to new/revised events, watchlisted openings and unresolved work. | Separate source, price, sizing and readiness results; rule IDs and supporting evidence for each conclusion. |
| 6. Prepare proposals | Follow the decision rules' proposal order and identity rules. | Refresh each existing intent or create one new intent; preserve bundled instructions and partial completion. |
| 7. Save | Validate/commit with the state contract, write the report, then release this run's claim. | Read-back-confirmed generation. Advance discovery only when traversal, required reads and first-page recheck completed. |
| 8. Report | Give the results below. | Link the saved report; do not repeat settled questions. |

## Report contents

- Scanned interval, coverage completeness, and new/revised article counts.
- Proposals grouped as exits/reductions, stops, then additions/openings.
- Watchlisted openings and their unmet conditions.
- Current funding/holdings and any reconciliation problems.
- What remains blocked and why; distinguish “nothing new” from “could not check”.
- Saved generation/report and confirmation that this review submitted no trades.

## Run boundaries

- The user launches reviews in a dedicated chat; suggested review times do not
  create a schedule or restrict when the user can invoke the skill.
- The ledger is authoritative memory. Old chat/report comparisons are evidence,
  not completed trade decisions or permission to initialize the portfolio.
- Missing values remain unknown. A failure in one area does not prevent unrelated
  read-only checks, but must remain visible in affected decisions.
- Technical verification belongs to the implementer. Ask only for a genuinely
  unresolved user preference, not approval of individual engineering checks.
