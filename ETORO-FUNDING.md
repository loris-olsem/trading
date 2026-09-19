# Reading the owner's Agent Portfolio funding

Investigated 2026-09-19. Research and GET requests only; no funding or trades.

## Recommended route: owner read-only API access

The public API exposes the required fields on the owner's copy relationship
(called a mirror). This route was live-verified with the user's new main-account
read-only key on 2026-09-19: **$500 net contributions and $500 available cash**.

1. With an owner token, GET `/api/v1/agent-portfolios`. Match Bravos using its
   `agentPortfolioGcid` against the identity already verified by the agent's
   `/api/v1/me`. Capture its `mirrorId`; do not match by display name alone.
2. With the same owner token, GET `/api/v1/trading/info/real/pnl`.
   Select `clientPortfolio.mirrors` by that mirror ID.
3. Record the fields below, along with timestamp, identity, mirror status,
   positions/units, and pending/delayed orders. Keep other owner holdings out of
   the saved projection. The token itself may still grant access to those holdings.

| Quantity | Documented fields / interpretation |
| --- | --- |
| Net contributed USD | `initialInvestment + depositSummary - withdrawalSummary` |
| Available mirror USD | `availableAmount`, described as cash reserved for mirror operations |
| Actual holdings | `positions`, including position IDs, units, amounts and PnL |
| Realized profit | `closedPositionsNetProfit`; this is not a deposit |
| Operational state | `isPaused`, `pendingForClosure`, `mirrorStatusID`, pending and delayed orders |

The schema explicitly defines `depositSummary` as deposits **after** the initial
investment, so omitting `initialInvestment` would understate funding. Net
contributions, current equity and available cash are different quantities.
Validate live field availability/casing and reconcile the values to the UI
before using them for sizing. Missing fields mean unknown, not zero.

The documented narrow read scopes are:

- `etoro-public:agent-portfolio:read` for listing owned agents.
- `etoro-public:trade.real:read` for the owner's portfolio/PnL.

Alternatively, the documentation lists `etoro-public:real:read` as sufficient
for both endpoints. These are alternatives to write scopes; no owner trading,
transfer or funding write permission is needed. Which choices are exposed in
the user's key-creation UI has not been inspected. A token's account identity
matters as well as its scope: the existing agent key does not become an owner
key merely because it already advertises real-read permissions.

If the agent-list permission is unavailable, a known mirror ID can be configured
and cross-checked against the PnL mirror's `parentCID` (the copied agent's real
customer ID, not its GCID). The explicit agent-list link is preferable.

## Other options

| Option | Assessment |
| --- | --- |
| Existing agent key alone | Verified internal balance and positions only. No documented owner-funding lookup was found that works with this key. It cannot independently confirm the $500 or later top-ups. |
| Owner balances API | GET `/api/v1/balances?accountTypes=Trading&includeSubAccounts=true&expand=equityDetails` requires `etoro-public:money.balance:read`. Useful for account totals; documentation does not establish that a Bravos copy allocation is separately identified here. Not a substitute for the mirror route without evidence. |
| Manual funding record | User records confirmed allocations/withdrawals, dates and mirror identity locally. Suitable for planning; cannot independently reconcile cash, fees, missed trades or broker changes. Main-account deposits must not count until actually allocated to Bravos. |
| Read the owner's browser UI | Could inspect displayed amounts with an authenticated session. User declined main-account sign-in here; no further access attempted. Session expiry and UI changes would need handling for unattended use. |

The balances API describes `totalBalance`/`displayBalance` as portfolio value,
not spendable cash. `equityDetails.available` is native-currency account cash;
it must not be mistaken for cash reserved specifically for Bravos. Cash-account
transaction endpoints describe banking/cash activity and are not documented as
an Agent Portfolio allocation ledger. Sub-account listings similarly do not
establish a connection to this agent's real copy funding.

## Verified calls with the existing agent key

- Earlier `/api/v1/me` and `/api/v1/trading/info/real/pnl`: HTTP 200; Bravos agent
  identity, internal credit $10,000, no holdings or mirrors.
- Earlier `/api/v1/agent-portfolios`: HTTP 403.
- Additional `/api/v1/trading/info/portfolio`: HTTP 200, credit $10,000, zero
  positions and zero mirrors. This alternative does not expose owner funding.
- Additional balances GET above: HTTP 403. The token's reported scopes omit
  `etoro-public:money.balance:read`, which the endpoint requires.

Projected results are in ignored `state/etoro-funding-probes.json`; the public
OpenAPI specification used for research is in `state/etoro-openapi.json`.
These agent-key checks predate the successful owner-key verification below.

## Verified owner access

`scripts/inspect-etoro-funding.ps1` now performs the documented lookup. It uses
the existing public application key and the new owner private key. The owner
identity differs from the agent, and every advertised owner scope ends in
`:read`. Owner identity, agent identity, owned-agent listing and owner PnL all
returned HTTP 200. IDs match across both accounts and the mirror link.

The saved projection `state/etoro-owner-funding.json` reports:

- Initial investment $500; subsequent deposits $0; withdrawals $0.
- Net contributed USD $500; available mirror cash $500.
- Zero open positions; active, unpaused, not pending closure.
- Returned opening/closing/delayed/entry/exit order arrays are empty. The
  `ordersForCloseMultiple` field is absent and remains unknown in the report.
- Copy stop-loss fields are present: amount $25 and percentage 5. These are
  recorded as returned; they are not a claim of a 5% maximum loss. The generic
  schema defines the amount as a mirror-value liquidation threshold. Agent
  applicability should be verified in the UI before relying on it.

This establishes access to the current real allocation. It does not test future
top-up behavior or broker execution. Re-running refreshes a single snapshot;
there is no scheduler, funding-change detector or trading implementation.

## Funding changes and sizing

A funding delta updates the recorded budget only. It must not generate orders,
resize holdings or revive skipped opening alerts. For example, an initial $500
plus a later $200 allocation produces $700 net contributions; it does not imply
$700 available cash if positions or orders already use some of that capital.

Do not keep using the initial $500/$10,000 ratio after a funding change.
Reading real funding does not by itself establish how eToro scales the next
agent order after a cash-only top-up. That mapping and order minimums remain
separate verification items. Existing units must be tracked directly rather
than reconstructed from a single changing ratio.

The help-bot claim that top-ups remain cash is still attributed guidance. At a
future user-directed top-up, compare mirror funding, cash, units and orders
before/after. Do not move money merely to test this. The generic mirror schema
also describes funding-related copy-stop-loss recalculation; applicability to
Agent Portfolios should be checked without changing any settings.

## Official sources

- [Owner agent listing and mirror link](https://api-portal.etoro.com/api-reference/agent-portfolios/get-agent-portfolios)
- [Portfolio/PnL and Mirror schema](https://api-portal.etoro.com/api-reference/trading--real/get-account-pnl-and-portfolio-details)
- [Alternative portfolio snapshot](https://api-portal.etoro.com/api-reference/trading--real/get-aggregated-portfolio-snapshot)
- [Aggregated balances, permissions and cash distinction](https://api-portal.etoro.com/api-reference/balances/get-aggregated-balances)
- [Agent creation and internal versus copied capital](https://api-portal.etoro.com/api-reference/agent-portfolios/create-agent-portfolio-v2)
- [Equity calculation](https://api-portal.etoro.com/core/guides/calculate-equity)
- [Machine-readable schema, version v1.379.0 when fetched](https://api-portal.etoro.com/api-reference/openapi.json)
