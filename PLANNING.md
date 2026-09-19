# Bravos helper: decisions and open questions

Updated 2026-09-19. No live trading or funding changes are authorized at this
stage. The current work is planning and read-only testing.

## Agreed requirements

- Use the Bravos website as the primary source. Gmail is optional, not a required
  dependency. Authenticated browser access to the full articles was verified.
- $500 allocated to the eToro agent named "bravos" is now API-verified. The allocation
  may increase later; any sizing model must avoid confusing the owner's allocation
  with the agent's internal balance.
- New capital must remain cash for future qualifying investments. A deposit or
  allocation increase must not itself buy, sell, or resize existing positions.
  Existing share quantities stay unchanged by funding; their percentage weights
  may decrease. Do not rebalance them back to their previous weights.
- Added capital must not revive previously skipped opening alerts. How to size
  a later explicit Bravos addition remains a separate decision.
- Carry Bravos's published stop-loss price into proposed position settings and
  follow later explicit changes, including changes bundled with additions or
  reductions. Compare actual eToro position stops against those values. Do not
  reduce them to informational references. Current work remains planning-only;
  missing or incompatible stop settings must be flagged rather than invented.
- At startup, consider opening alerts from the preceding **30 calendar days**.
  This window applies only to the initial catch-up. Routine discovery resumes
  from the last completed scan, with its boundary date reread and deduplicated
  by article identity. After downtime, cover the full gap without a 30-day cap.
- A candidate must remain open according to subsequent Bravos updates.
- Enter only when the executable buy quote is at or below Bravos's original
  published entry price, comparing the same instrument and currency.
- If the price is above that entry, skip that opening alert permanently. Do not
  place it on a pullback watchlist. A genuinely new opening alert is a new event.
- Do not treat reductions or close alerts as instructions to open missing holdings.
- Schedule remains undecided. Twice daily was proposed, not configured.
- Today's diagnostic is not the catch-up run and has not permanently rejected or
  accepted any alert. Start date, quote freshness, and evaluation time must be
  defined before recording durable entry decisions.

## Verified Bravos semantics

Source: [Tactical Portfolio guide](https://bravosresearch.com/ideas/).

- Weight 5 means 5% of the portfolio.
- The guide discusses reassessment/reference levels. The user's explicit
  project instruction is to mirror published stop-loss settings and subsequent
  updates; the guide's wording must not be used to omit those settings. Target
  prices do not by themselves establish our automatic take-profit policy.
- Explicit alerts cover opening, additions, reductions, and exits.
- The Tactical and Quant portfolios are independent. Quant's cash signal must
  not close Tactical holdings.
- [Quant guide](https://bravosresearch.com/signal-journal/) specifies Aggressive
  as 50% QQQ / 50% TQQQ, Moderate as 75% / 25%, and Cash as 100% cash.

Representative articles read while signed in:

| Date | Event | Published values |
| --- | --- | --- |
| 2026-09-18 | ETHA opening | Entry $19.23; weight 3; reference stop $17.60; targets $22/$24/$26 |
| 2026-09-17 | NTRA reduction | Weight 2 to 1; reference price $360 |
| 2026-09-14 | BRK.B increase | Weight 5 to 8; reference price $513.55 |
| 2026-09-16 | SRUUF close | Full exit; reference price $19.28 |

These are research examples, not current recommendations or pending orders.

## eToro funding guidance supplied by the user

On 2026-09-19 the user pasted an answer from eToro's help bot stating that:

- Agent Portfolio top-ups remain cash available for future trades and do not
  automatically resize existing holdings or trigger reallocation.
- This differs from ordinary CopyTrader; no public toggle is required because
  cash-only additions are described as the Agent Portfolio default.
- The bot reports $500 net funding, 100% cash, and zero open positions for Bravos.

Use this as the working product assumption, attributed to the help-bot response.
The $500 funding and cash were subsequently verified through the owner's API.
The top-up behavior has not been tested with positions already open. An empty portfolio cannot demonstrate the
absence of reallocation when holdings exist. Preserve the exact user-provided
answer in conversation as source evidence; do not infer additional guarantees.

The helper must never generate rebalancing orders merely because capital changes.
Before relying on increased funds for sizing, determine how the updated real
allocation and copy cash are exposed: the agent's internal $10,000 balance alone
does not establish the owner's current funded amount. At a future user-directed
top-up, compare actual position units and pending orders before and after, as well
as cash, without making trades or funding changes merely to test this claim.

Investigation now documented in [ETORO-FUNDING.md](ETORO-FUNDING.md): the owner's
mirror schema exposes initial investment, subsequent deposits, withdrawals and
available cash. The owner read-only token and Agent Portfolio mirror lookup now
work: $500 net contributions, $500 available cash, no open positions and an
active copy relationship. Existing agent credentials still expose only internal
capital. `scripts/inspect-etoro-funding.ps1` provides the repeatable GET diagnostic.

## Decisions not yet settled

1. Confirm Tactical-only scope or a separate allocation for Quant.
2. Define whether an increase/reduction changes units proportionally or targets
   a percentage of current equity; also define the sizing basis for new entries.
3. Decide how an older catch-up opening is sized after subsequent Bravos changes.
4. Decide how to handle candidates already below a Bravos reassessment level.
   Flagging for review was proposed but has not been accepted as a final rule.
5. Bravos stop-loss settings are to be followed. Any additional portfolio loss
   limits or automatic take-profit rules remain separate decisions.
6. Define sizing of future entries and explicit Bravos additions after a funding
   increase, while preserving the no-funding-triggered-trades requirement above.
7. Define run times, market-hours behavior, holidays, stale quotes, missed runs,
   and the alert's evaluation/expiry time.

## Feasibility checks before a dry run

- Completed: owner API confirms the $500 allocation, active status and matching
  Bravos account. No main-account browser login is needed for this lookup.
- Verify the funding behavior described in the supplied help-bot guidance when
  appropriate. Ordinary [CopyTrader reallocation rules](https://www.etoro.com/copytrader/how-it-works/)
  must not be assumed to apply to Agent Portfolios. Conversely, our software
  declining to rebalance cannot by itself prevent any broker-side adjustment.
- Verify instrument eligibility for this account and exact ticker/share-class
  mapping. Never silently replace an ETF with its underlying asset or a CFD.
- Verify minimum positions and copied-trade thresholds: on a $500 allocation,
  1% is $5 and 3% is $15. Do not round up without an agreed sizing rule.
- Verify fees, spread, partial closes, and copied-order behavior.
- Test browser-session persistence and unattended availability. A successful
  manual session does not establish that scheduled access will remain signed in.
- Bravos's [published terms](https://bravosresearch.com/terms-and-conditions/)
  prohibit automated website access. Establish an approved access route before
  depending on an unattended reader.

## Proposed state model (not implemented)

Use article IDs/URLs and publication times to identify events. Keep original
entry, subsequent changes, current strategy weight, skip reason, source passage,
and any associated position IDs. Store source updates separately from actual
holdings. Report login failures and incomplete scans as failures, not "no alerts".
Reconcile the latest dashboard with the alert history without automatically
buying every existing holding or rebalancing unchanged positions on every run.
