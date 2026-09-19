# Bravos helper specification

Policy version: `2026-09-19.3`. Reviews are started by the user in a dedicated
chat, without a schedule. The implementation reviews and prepares proposals;
there is no trading executor. Initial allocation will be discussed separately.

Clarification from the user, 2026-09-19: the intended product executes trades on
normal runs, with explicit planning/dry-run requests suppressing execution. The
initial backlog assessment was a planning exception. The current analysis-only
implementation does not fulfill that execution requirement. The assistant cannot
submit financial trades through API or browser; this is an assistant capability
boundary, not the user's preference or a missing eToro agent account/key.

## Provenance and conflicts

[Original form answers](decisions/2026-09-19-form.json) preserve all 49 selections
verbatim. Responses expressing confusion are not acceptance. Technical correctness
is the implementer's responsibility, not a portfolio preference. Later explicit
answers take precedence over conflicting form options.

Follow-up answers on 2026-09-19:

- Entry tolerance: **Allow up to 2% above Bravos's entry**.
- Missing holding followed by an addition: **Keep the original opening's ceiling**.
- Early exits: the user expects no other manual changes; early exits will be
  requested through this helper so the action is recorded.

## Strategy and instruments

- Follow Bravos Tactical alerts on its website; Gmail is not required. A03 gave
  qualified assent to Tactical, while asking what Quant is. Quant is a separate
  signal/allocation system; its previously read guide included QQQ/TQQQ mixes.
  TQQQ has built-in leverage, incompatible with the rule below. Do not add Quant.
- **Never use leverage**, including leverage embedded in a product. A broker's
  X1 setting alone does not establish that the instrument is unleveraged.
- Follow any Bravos instrument this eToro account supports without leverage;
  do not impose the rejected stocks/ETFs-only restriction. Verify exact asset,
  currency, account eligibility and broker treatment. Report unavailable exposure;
  a similar ticker, underlying crypto or unrelated fund is not a replacement.
- Broad eligibility is not proof every order type is implemented. The price
  rules below describe long buys. If an alert needs short-sale or other price/stop
  semantics, resolve that specific compatibility case before proposing it. Do
  not silently use long-buy arithmetic or claim all eToro products work already.

Sources: [Bravos Quant guide](https://bravosresearch.com/signal-journal/), read
while authenticated during the earlier investigation; [TQQQ issuer description](https://www.proshares.com/our-etfs/leveraged-and-inverse/tqqq),
which specifies a 3x daily Nasdaq-100 objective.

## Discovery and entry eligibility

- Initial catch-up considers 30 calendar days, once. Its date and baseline are
  deferred by D12. Do not initialize from the old research snapshot.
- Routine discovery covers the entire gap since the last complete scan, without
  a 30-day cap. Reread the checkpoint date where publication times are date-only;
  deduplicate by article identity.
- A never-entered opening stays on a watchlist while Bravos holds that cycle.
  Reassess on later reviews; being above the ceiling or reaching session close
  does not permanently exclude the opening.
- **Long-entry ceiling = original Bravos opening price × 1.02.** Compare the
  executable ask in the same currency; never round the ceiling upward. This
  replaces A01's zero tolerance, A02's permanent skip and D08's first-session
  expiry of the opening opportunity.
- A later addition cannot supply a higher ceiling for a holding we never opened.
  Keep assessing an already eligible original opening using its original entry
  plus 2%. An addition alone cannot enroll an opening outside the agreed initial
  catch-up or subsequently discovered opening set. Do not buy the addition and
  the missed opening as separate positions or replay historical additions.
- Read later source updates first. A full Bravos close ends the opportunity.
  A planning observation is not an executed trade or a consumed opening.
- Reject a long entry at or below the latest published stop; do not lower the
  stop to make it fit. Require a fresh setup or explicit clarification.
- After our own verified stop exit, do not reopen that old cycle even if Bravos
  still holds. This is different from a never-entered opening awaiting a price.

## Sizing and capital

- New openings use Bravos weight × **current real equity of the owner's Bravos
  allocation**. Do not use internal agent capital or remaining cash as equity.
  Verify the equity calculation and conversion into actual copied order size.
- Respect cash after costs and pending commitments. Do not round up for minimums
  or redistribute skipped weights. Process entry/add events by source publication
  order, then stable ID for equal dates. This tie-break cannot resolve ambiguous
  source chronology within one position cycle.
- A source reduction from weight 5 to 4 sells 20% of our linked units. A full
  close sells all linked remaining units. Do not rebalance to current equity.
- An addition to an existing holding uses the increase in source weight × current
  real equity (5 to 8 adds 3% of equity). D03's accepted ceiling is that addition's
  own published price. The 2% follow-up specifically resolved opening entries;
  it has not changed the addition ceiling.
- Initial catch-up uses current still-open weight, original opening ceiling
  and latest stop, without replaying historical transactions. CF's example uses
  4%, subject to a fresh review. For a watched opening changed since discovery,
  use the reconstructed current source exposure once, not opening plus past adds.
- Funding changes alone create no entry, add, sale, rebalance or stop change.
  Existing units are untouched by the helper. Cash may fund future qualifying
  actions, but a deposit is not a signal. A watched opening may qualify later
  through normal review, independently of funding.
- Cash/minimum failures are recorded separately from above-ceiling watchlist
  results. D09 authorizes skipping an unsizeable event, not silently resizing it.

## Stops, targets and ordering

- Mirror exact published stop prices and explicit changes, including those in
  add/reduce articles. Do not substitute a percentage of our entry. Only actual
  copied-position read-back establishes application; API acceptance is not enough.
- Missing, ambiguous or incompatible stops block new entries. Preserve existing
  protection while resolving discrepancies; never silently clear it.
- Follow explicit take-profit instructions and quantities. Record all targets;
  prices alone do not define sale fractions. Research any published convention;
  absent quantities, follow explicit reductions/profit-taking, not invented thirds.
- Reconcile first; prioritize exits/reductions, stop updates on remaining holdings,
  then additions/openings. Preserve chronology and link bundled instructions.
- Require a verified broker entry method that enforces the ceiling. A quote
  check alone does not. Reconcile partial fills and outstanding orders before retry.
- Record user-requested early exits against the position cycle, separately from
  Bravos instructions. A request is pending until broker evidence confirms it.
  A confirmed full early exit ends our participation in that opening; never
  reopen it merely because Bravos still holds. For a requested partial exit,
  preserve remaining units and do not restore sold units to a target weight.
  No generic manual-change questionnaire is needed. Unexpected unexplained
  broker differences still require reconciliation; never silently undo them.

## Invocation and timing

- Start supervised reviews in a dedicated chat (V07), without automation.
  Bravos login credentials are in ignored `secrets/bravos/username.txt` and
  `password.txt`; use only for the intended Bravos login when needed. Do not
  print, log or commit them. A failed login is failed discovery, not no alerts.
- D08's accepted suggested times are 10:00 and 15:30 America/New_York on US
  trading days, with daylight-saving-aware conversion. These are suggested
  times, not a schedule or a restriction on manually invoking the skill.
- Evaluate entry/add prices while the relevant exchange is open, with asks no
  older than 60 seconds. Closed markets/stale quotes mean waiting data.
- An individual unfilled order attempt expires at session close and requires
  reconciliation before retry. The opening opportunity remains on its watchlist.
  Addition events retain D08's first-evaluated-session expiry. No orders are
  created by the current procedure.
- Reopen active/unresolved articles each run. After seven calendar days, check
  other known resolved URLs on the next user invocation; no background job.
- Recheck the archive first page after traversal; merge/retry once on change.
  If it changes again, save partial evidence without advancing coverage.
- Reconcile the source dashboard; expand targeted history to investigate gaps.
  Block affected proposals; do not claim to detect every unseen backdated post.

## Storage and engineering

- Version minimal JSON ledgers, immutable prior generations and Markdown reports
  in local Git. Ignore credentials and raw/private broker diagnostics. Omit
  unnecessary owner identity from tracked evidence; use local aliases for links.
- Hash normalized Bravos-authored bodies and compare material facts; exclude
  chrome/comments, retain revisions, never infer a new opening from a hash change.
- Keep the workflow in Markdown; deterministic helpers handle exclusive claims,
  structural validation, hashing and atomic commits. These do not prove broker
  execution or interpretation of Bravos instructions.
- Repository-local author: Loris Olsem <loris.olsem@gmail.com>. Keep old authors,
  global settings and remote configuration unchanged.

## Disposition of all form items

| IDs | Resolution |
| --- | --- |
| C01, C02 | Accepted scan separation and exact stops/read-back |
| A01, A02 | Conflicting parts replaced by watchlist and explicit 2% follow-up |
| A03 | Tactical; explain independent Quant and leverage conflict |
| A04, D05 | Accepted explicit target quantities; research convention |
| A05 | Follow-up keeps original opening ceiling, not later addition price |
| A06, A07 | Accepted no old-cycle re-entry after stop; no unprotected entry |
| A08, D10 | Supported Bravos exposure, never leverage, no unrelated substitute |
| A09, E05 | Accepted minimal versioned ledger/history/reports; private snapshots ignored |
| D01–D04 | Accepted equity basis, proportional trims, explicit additions/current catch-up weight |
| D06, D07 | Accepted crossed-stop rejection and enforceable ceiling |
| D08 | Timing/quote age accepted; opening lifetime superseded; no schedule |
| D09 | Accepted publication ordering and cash/minimum handling |
| D11 | Early exits requested through helper, recorded and reconciled; no automatic undo |
| D12 | Initial allocation/baseline explicitly deferred |
| V01–V06, V08 | Confusion, not endorsement; engineer-owned verification work |
| V07 | User-launched supervised chat; disk credentials for Bravos login |
| F01–F03, F05 | Confusion, not endorsement; fix defects and track verification as engineering |
| F04, F06–F09 | Accepted errors, historical correction, helpers, ordering and bounded coverage |
| F10 | Explicit repository-local author identity applied |
| E01 | Technical date-precision/deduplication choice, not an accepted portfolio preference |
| E02–E04, E06, E07 | Accepted revision cadence, retry bound, hashing and recovery |
| M01 | Redundant framing rejected; no execution authorization added |

## Evidence and remaining work

[ENGINEERING.md](ENGINEERING.md) tracks implementation duties and verification.
Do not mark a check passed because it appeared in the questionnaire.

Previously verified on 2026-09-19: $500 owner contributions, $500 mirror cash,
no positions; the agent reported $10,000 internal capital. A funding query does
not establish how the next internal order converts to copied dollars.

The supplied eToro help-bot answer says top-ups stay cash. Keep this attributed;
it was not tested with open positions. Do not move money or trade merely to test.

Latest refresh: 2026-09-19 07:57 UTC, **$4,610 net contributions and available
mirror cash, zero positions** ($500 initial + $4,110 deposits, no withdrawals).
Agent internal capital remains $10,000. This replaces the historical $500 as
the latest observed allocation; every actual review must refresh it again.
