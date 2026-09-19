# Decision rules

Apply after evidence collection and reconciliation. This is the decision-making
part of the skill; it does not fetch data or execute orders. Policy:
`2026-09-19.3`, recorded in root `PLANNING.md`. A **cycle** is one Bravos opening
and its subsequent changes; another genuine opening is another cycle.

## 1. Required inputs and result

| Inputs | Required facts |
| --- | --- |
| Source | Strategy, cycle/event/revision IDs, chronology, still-open status, original entry, latest weight/stop, explicit target quantities |
| Broker | Exact instrument/currency, eligibility and leverage, linked units/orders, actual stops, current real Bravos equity, spendable cash, costs/minimums/precision |
| Quote | Executable ask, source timestamp, exchange-open status |
| Memory | Prior decisions/intents, consumed quantities, our stop/early exits, pending user requests |

For each evaluation, record the applicable rule IDs, evidence references and
separate **source**, **price**, **sizing** and **readiness** results. Missing inputs
block dependent conclusions; they are not zero or a failed price comparison.
Result names below describe evaluations, not broker fills or proposal statuses.

## 2. Route the instruction

Process every instruction in a bundled article; a trim must not hide a stop change.

| Rule | Instruction / condition | Decision route |
| --- | --- | --- |
| R1 | Quant or another strategy | Outside this Tactical book; never change Tactical holdings from its signal. |
| R2 | New or watchlisted eligible opening | Apply opening gates O1–O9. |
| R3 | Addition, with linked holding | Apply addition rules A1–A4. |
| R4 | Addition, without linked holding | Reassess only an already eligible original opening under O1–O9. Its ceiling stays original entry +2%; do not create an independent add entry or enroll an otherwise ineligible old opening. |
| R5 | Reduction or full close, with linked holding | Apply sizing S3/S4, then proposal rules P1–P4. |
| R6 | Reduction or close, without linked holding | Record `no_matching_position`. Update the source cycle; do not open a holding. |
| R7 | Stop change, including one bundled with another action | Apply protection rules T1–T4. |
| R8 | Target/profit-taking instruction | Apply target rules T5/T6. |
| R9 | User-requested early exit or observed stop exit | Apply exit rules U1–U4. |
| R10 | Funding change alone | Refresh budget evidence. Create no trade, rebalance or stop instruction. Evaluate watchlisted openings only through their normal route. |
| R11 | Material revision of a previously decided event | Retain both versions; `needs_review`. Do not repeat a trade or automatically reverse a completed decision. |

## 3. Opening gates — in order

Use for a long opening. Record all independently established findings, but do
not pass a gate with unknown inputs. An earlier terminal/blocking result is not
overridden by a later price pass. Indicative arithmetic may still be reported.

| Rule | Check | If it fails / applies | If it passes |
| --- | --- | --- | --- |
| O1 | Is this an eligible opening with resolved history? | Missing baseline: initialization needed. Ambiguous cycle, chronology or dashboard mismatch: block affected proposal. | Continue. |
| O2 | Does Bravos still hold this cycle? | No: `excluded_closed`; supersede any unexecuted opening. Do not replay buys/sales from the missed interval. | Continue. |
| O3 | Have we already entered or ended our participation? | Reconcile existing/partial/pending exposure; no duplicate opening. A confirmed stop exit or user full early exit forbids reopening this cycle. | Continue if never entered. |
| O4 | Is exact exposure eligible and unleveraged? | Unknown/unavailable: `blocked_instrument`. Leveraged exposure: exclude. Never substitute a similar asset; resolve non-long price/stop semantics before applying these long-buy gates. | Continue. |
| O5 | Is the ask current, comparable and executable? | Closed exchange, quote older than 60 seconds, missing quote or uncertain currency: `waiting_quote`. | Continue. |
| O6 | Is the latest published stop usable? | Missing/ambiguous/incompatible stop: block. Ask at or below the long stop: `excluded_crossed_stop`, requiring fresh setup/clarification. Never move the stop to fit. | Continue. |
| O7 | Is ask ≤ original Bravos entry × 1.02? | No: `watching_price`; reassess on later reviews while Bravos holds. | Record `price_pass`, then continue. |
| O8 | Can the accepted size be funded and represented? | Unknown equity/mapping/costs: readiness blocked. Cash/minimum failure: skip the unsizeable event and report it, without rounding up or reallocating its weight. | Use S1/S2. |
| O9 | Is a ceiling-enforcing entry method verified? | No: block execution readiness; a quote check or similarly named order type is insufficient. | Prepare a planning proposal through P1–P4. |

**Price ceiling:** use original opening price × 1.02, never average cost or a
later addition price; never round the ceiling upward. A session's expired order
attempt does not expire the opening opportunity. Reconcile outstanding quantities
before considering another attempt. No proposal here places or cancels an order.

## 4. Additions to an existing holding

| Rule | Condition | Required result |
| --- | --- | --- |
| A1 | Explicit addition with unambiguous before/after source weights | Size with S5; do not resize previously held units. |
| A2 | Price assessment | Use the addition's published price as ceiling, with **no opening-style 2% tolerance**. Require exact exposure/currency, an open exchange, ask age ≤60 seconds, usable published stop and verified ceiling enforcement. |
| A3 | Event lifetime | Retain first-evaluated-session expiry for additions. Session end does not authorize an unreconciled retry. |
| A4 | Missing inputs, partial fills or unexplained holdings/orders | Block the dependent proposal; reconcile before another addition. Never add merely because cash increased. |

## 5. Sizing

Weights are percentages: 5 means 5%, not a factor of five. Real equity is the
owner's current Bravos allocation, not the agent's internal capital or free cash.

| Rule | Action | Size |
| --- | --- | --- |
| S1 | New opening | Source weight ÷ 100 × current real Bravos equity. |
| S2 | Eligible late/watchlisted entry; separate catch-up when later authorized | Use current still-open weight once, original opening ceiling and latest stop; do not replay old additions/trims. |
| S3 | Reduction | Linked current units × (weight before − weight after) ÷ weight before. Example: 5→4 sells 20% of our units. Missing/inconsistent weights require review. |
| S4 | Full close | All remaining linked units. |
| S5 | Explicit addition to a held position | (Weight after − weight before) ÷ 100 × current real Bravos equity. Example: 5→8 adds 3% of equity. |
| S6 | Cash/cost/minimum checks | Reserve pending commitments and costs; verify precision and actual copied-size mapping. No rounding up, skipped-weight redistribution or funding-triggered rebalance. Reconcile exit proceeds before spending them. |

## 6. Stops and targets

| Rule | Condition | Required result |
| --- | --- | --- |
| T1 | Proposed entry | Include the exact latest published stop price; never a replacement percentage based on our entry. |
| T2 | Existing holding differs from published stop | Prepare `set_stop` for remaining units, including updates bundled with adds/trims. Preserve current protection while resolving incompatibility. |
| T3 | Stop information missing/ambiguous/incompatible | Block the dependent new exposure/update. Do not invent, omit or clear the stop. Unknown broker stop data does not mean protected. |
| T4 | Determining whether a stop was applied | Require actual copied-position read-back of price and enabled/mode status. Portfolio copy stops and API request acceptance do not establish this. |
| T5 | Explicit take-profit instructions and quantities | Follow the stated quantities and later changes; link the target action to its source. |
| T6 | Target prices without quantities | Record every target, research the published convention, and otherwise follow explicit reduction/profit-taking alerts. Never invent equal thirds. |

## 7. Our exits and unexpected changes

| Rule | Evidence | Required result |
| --- | --- | --- |
| U1 | User requests an early exit through this helper | Record the cycle, quantity, request and pending status separately from Bravos instructions. Request is not completion. |
| U2 | Broker confirms a full user early exit or stop exit | End our participation in that cycle; no automatic reopening while Bravos continues holding. |
| U3 | Broker confirms a requested partial early exit | Preserve the remaining holding; do not restore the sold units to an old target weight. |
| U4 | Unlinked positions, unexplained changes or ambiguous execution | `needs_reconciliation` before another affected proposal. Actual holdings are authoritative; never infer a fill from balance movement or silently undo an unexplained change. |

## 8. Proposal order, identity and completion

| Rule | Requirement |
| --- | --- |
| P1 | After reconciliation: exits/reductions first, stop updates on remaining holdings second, additions/openings third. Preserve chronology within each cycle and link bundled actions. |
| P2 | Competing entry/add events: publication order, then stable ID for equal dates. This tie-break cannot resolve ambiguous chronology within a cycle. |
| P3 | Match `cycleKey + eventKey + operation` against existing intents using the state contract's exact key format. Refresh its history/evidence; changed quotes, funding, policy or revisions do not create a second intent. Check consumed events and remaining quantities. |
| P4 | Include source revisions, rule/policy version, account snapshot, quote/time, quantity, operation and blockers. Only broker order/position/fill evidence establishes observed partial/completed status; stop application requires T4. |

These tables produce planning decisions. Broker compatibility remains subject
to the unresolved checks in root `ENGINEERING.md`; a passed price gate does not
establish execution readiness.
