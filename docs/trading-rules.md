# Effective trading rules

Policy `2026-09-23.1`. Detailed agreements and original answers:
[PLANNING.md](../PLANNING.md), [decision records](../decisions/).

| Situation | Required behavior |
| --- | --- |
| Source | Bravos Tactical website; deterministic parser; ambiguous formats held |
| Initial enrollment | Last 30 calendar days once, unless explicit `--since` selects another floor |
| Research | Backfill as needed to reconstruct holdings; do not expand enrollment |
| Routine scan | Entire gap from last complete checkpoint; deduplicate and audit revisions |
| Never-entered open cycle | Watch while Bravos holds it |
| Opening price | Original opening × 1.02, rounded down to precision; compare executable ask |
| Purchase price | Market order after fresh agent ask passes source ceiling; final agent/copied price can exceed it. Over-ceiling read-back holds further purchases; no automatic undo sale |
| Later add before our opening | Keep original ceiling; enter latest total weight once |
| Held addition | Add weight increase × current owner equity; ceiling is addition price, no 2% tolerance |
| Missed add then reduction | Expire unexecuted add; trim actual held units proportionally |
| Opening size | Latest source weight × real total equity of owner's Bravos allocation |
| Agent conversion | Sourced realized-copy-capital model; verify actual copied amount after every fill; internal capital is not owner money |
| Funding | No rebalance or order; cash available for future qualifying actions |
| Cash/minimum/cost failure | Report/skip; do not inflate size or redistribute skipped weights |
| Cost estimates | Fresh owner-side what-if request for each amount and before submission; older figure-generation dates accepted, not a final-fee guarantee. Prices retain their separate 60-second limit |
| Final partial fill | Keep protected units, report shortfall, consume event; no automatic top-up |
| Weight 5 → 4 | Sell 20% of linked units, rounded down to supported precision |
| Full close | Close all remaining linked units, including dust |
| Stops | Exact published latest prices, including bundled changes; verify copied positions |
| Missing/ambiguous/crossed stop | Block entry; never invent, widen or remove protection |
| Targets | Record prices; do not invent fractions. Unsupported quantified conditional instructions held |
| Early exit | Record cycle/fraction; next owner `run` processes and verifies it; never restore sold units |
| Full early/stop exit | End participation; no old-cycle re-entry |
| Instrument | Exact supported exposure, broker leverage X1; no substitute ticker. Funds with embedded leverage are permitted when Bravos recommends them. New US stocks/ETFs resolve automatically without a configured ticker; other product/currency gaps remain explicit |
| Quote | Published US core session for the configured US profiles, eToro tradability, realtime USD ask ≤60 seconds old, not future-dated; other profiles retain broker exchange flag |
| Broker limit range | Applies only in retained IOC mode; market orders send no limit or trigger price |
| Order | Reconcile, exits/reductions, stops, then openings/adds by source publication order |
| Uncertain submission | Reconcile original attempt; no blind retry |
| Addition expiry | First evaluated open New York trading-date session; outstanding attempts still reconciled |
| Opening expiry | Reconcile every submitted attempt; never-entered source-open opportunity stays watched |
| Unexpected broker difference | Hold and report; never silently undo it |

Normal owner `run` executes; `plan` never writes to the broker. Suggested times
were 10:00 and 15:30 America/New_York on US trading days. No schedule is installed.

Current adapter support is long, USD, unit-based real positions or configured
unleveraged X1 CFDs. Unsupported semantics stay held; this implementation boundary
does not replace the broader rule to follow supported unleveraged Bravos exposure.
