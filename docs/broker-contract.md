# eToro contract and outstanding evidence

The existing agent has a trading token; the owner token is read-only. Requests go
only to `https://public-api.etoro.com`. Credentials never enter reports or error
output. Agent activity affects **real owner money**, despite the internal virtual
balance. No demo/live trades were made during development.

## Observed order rejection, 22 September 2026

**Current override:** the owner subsequently authorized relaxing requirements.
New buys use [market-order mode](market-order-mode.md) with a pre-trade price
check, unchanged amount and exact stop. Historical IOC findings below do not
prohibit the now-authorized market mode. IOC still rejects CFDs; market mode
uses their verified eligible settlement. Live market execution remains unverified.

A read-only lookup of the owner's BRK.B attempt returned terminal status
`Rejected`, error code `1065`, and zero position executions. The broker described
a connectivity or technical failure on an HBC-only path. It recorded the intended
`limitIOC` request, internal amount 800 USD, limit 516.63 and fixed stop 480.
This establishes the reported rejection, not its underlying infrastructure cause
or that a subsequent attempt will succeed. `gr orderAudit` refreshes saved order
results without submitting. Stops remain exact in either execution mode.

Status name, error code and error message are provided by the documented
[order lookup](https://api-portal.etoro.com/api-reference/trading--real/get-order-information-and-position-details).
The app preserves those selected fields for terminal empty buys, bounds the
message and strips terminal controls. Other opportunities can continue after a
proven empty buy; unknown/partial outcomes still stop further submissions.

The owner's later v3 run also returned 1065 for BRK.B/EOG/ADI. ARGT returned
2039: IOC orders require real settlement, whereas its profile is CFD. The app
rejects CFD IOC openings/additions before submission at the broker preflight,
policy and payload boundaries. SMH has the same known incompatibility. This is
an order-type limitation, not absence of instrument eligibility. No market-order
automatic fallback or invented real-settlement profile is permitted; the explicit
market mode is a separate owner-authorized choice.

## Identity and sizing

Match the owner mirror to the authenticated agent using the owner's agent listing,
mirror ID and copied customer ID. Require USD. Owner total equity is mirror cash
plus invested amounts plus unrealized PnL. Agent equity is separate. Pending order
collections come from `/portfolio`, cross-checked against PnL positions.

The policy target is `source weight × owner total equity`. Implemented conversion:

```
owner copy capital = owner cash + owner invested amounts
agent copy capital = agent cash + agent invested amounts
internal order = owner target × agent copy capital / owner copy capital
```

Floor the result to cents, check both cash balances/costs and broker minimums,
then verify proportional copied dollars within one cent. The realized-equity
copy basis differs from total-equity target sizing. eToro documents it for new
CopyTrader trades; its application to Agent Portfolios after deposits remains
unverified. Sources: [CopyTrader](https://www.etoro.com/copytrader/how-it-works/),
[equity](https://api-portal.etoro.com/core/guides/calculate-equity),
[agent creation](https://api-portal.etoro.com/api-reference/agent-portfolios/create-agent-portfolio-v2).

## API boundaries

`EtoroClient` uses official identity, agent listing, PnL, portfolio, instrument,
rates, market-search, eligibility and costs endpoints. Eligibility/cost POSTs
calculate read-only information and never submit orders. Development used the
official OpenAPI version 1.379.0 captured on 19 September 2026.
Opening preflight checks eligibility independently for agent and owner, including
the exact settlement type, non-potential X1 long configuration and stop support.
An agent permission does not establish that the owner can receive the trade.
See the [instrument investigation](INSTRUMENTS-2026-09-21.md) for observed IDs
and account restrictions.

The 21 September read-only rehearsal observed cost components using `value`,
while that schema calls the field `amount`. The adapter accepts either numeric
field and rejects conflicting dual values or missing values. This discrepancy
was tested using the observed response shape; see the
[rehearsal report](REHEARSAL-2026-09-21.md).

Cost freshness follows the [21 September owner follow-up](../decisions/2026-09-21-cost-estimates.json).
The official [what-if cost endpoint](https://api-portal.etoro.com/api-reference/trading--real/get-what-if-trading-cost-breakdown)
returns an estimate for execution now, while `lastUpdated` describes when its
figures were generated. A new successful request for the proposed owner amount
is required during evaluation and again before submission. It is not cached by
the app. The response may contain older figures; these are accepted as the
broker's current estimate, not a guaranteed fee. Reject a future/invalid timestamp,
wrong instrument, invalid currency, failed request or request taking over 60 seconds.
The separate executable quote must still be no more than 60 seconds old. The old
60-second figure-generation cutoff was an implementation assumption, not an API
requirement or the user's price-freshness rule.

Execution routes are restricted to agent credentials:

- `POST /api/v3/trading/execution/orders`: asynchronous `mkt` in current mode
  (`limitIOC` in retained capped mode), leverage 1,
  explicit settlement type and exact fixed stop. HTTP 202 is acceptance only.
- `GET /api/v2/trading/info/orders:lookup`: order ID or durable UUID reference.
- `PATCH /api/v2/trading/positions/{id}`: exact fixed-stop update.
- `POST /api/v1/trading/execution/market-close-orders/positions/{id}`: full/partial close.
- `GET /api/v1/trading/info/real/close-orders/{id}`: close execution proof.

The owner authorized trying v3 after repeated v2 error 1065. This changes only
the opening/addition submission route. Amount sizing, ceiling, stop and shared
v2 order lookup stay the same. No automatic v2, market-order or quantity fallback
is used after failure. Existing journal references remain valid. The official
v3 contract is also preserved in `state/etoro-openapi.json`, path
`/api/v3/trading/execution/orders`. Successful live execution remains unverified.

## Operating model

The owner asked to proceed using the best available information after support
could not establish private Agent Portfolio contracts. The configured model uses
the official [Agent Portfolio description](https://raw.githubusercontent.com/etoro-builders/etoro-agent-skills/main/skills/etoro-agent-portfolios/SKILL.md)
of proportional copy trading and the more specific
[CopyTrader rules](https://www.etoro.com/copytrader/how-it-works/) for new-order
realized-equity sizing and copying stop changes. Applying those CopyTrader details
to this agent is an **inference**, not an observed Agent Portfolio guarantee.
The model is now configured rather than requiring support to certify it before
the first order. `copySizingEvidence` and `copyStopsEvidence` identify that source
and its limits; they do not assert successful live testing.

Execution must still request the exact fixed stop with the opening order, check
both accounts' eligibility, and verify each copied amount, instrument, entry price
and exact stop. It submits one order at a time. No next purchase proceeds while
an outcome is unresolved or a copied amount/price/protection check fails. A fill
is not confirmed merely because an API accepted its order. The program waits up
to three 30-second intervals for cached portfolio reads after an order receipt;
timeout preserves the attempt for reconciliation rather than resubmitting it.

This detects a broker discrepancy after execution; it cannot guarantee the broker
never creates a differently sized or temporarily unprotected copy. Stop discrepancies
are reported with the affected owner position, and the existing narrow agent-stop
repair path remains. There is no automatic corrective sale or owner-account write.

## Remaining broker uncertainty

`config/trading.json` contains sourced operating-model references and five exact
asset profiles. No live execution evidence or copy-side price guarantee is claimed.

1. **Purchase price:** current `MARKET_WITH_PRICE_CHECK` mode guarantees neither
   agent nor copied fill price. Earlier `AGENT_LIMIT_WITH_COPY_CHECK` mode used
   an agent IOC limit without an established copied-fill guarantee. Keep
   `copyPriceCeilingEvidence` empty: consent is not broker evidence. Post-fill
   `openRate` checks detect overpayment and block further purchases; they cannot
   prevent or undo it. No automatic corrective sale is authorized. The default
   `REQUIRE_COPY_GUARANTEE` mode remains available and requires actual evidence.
2. **Copy sizing:** `REALIZED_EQUITY_RATIO` uses the sourced model above. Verify
   actual copied dollars for every order, including after deposits and with PnL;
   a discrepancy blocks subsequent purchases rather than adjusting exposure.
3. **Stop propagation:** exact fixed stops are requested on entries and edits.
   Empty accounts cannot prove propagation. Only actual position read-back
   establishes whether the requested protection has been applied.
4. **Cash-only funding:** the owner supplied an eToro help-bot answer saying agent
   top-ups stay cash without rebalancing. This is attributed guidance, not an
   observed test with holdings. The program never rebalances from funding and
   detects unit drift; it cannot control broker-side behavior.
5. **Asset profiles:** verify exact ID/symbol, USD quote basis, absence of embedded
   leverage, settlement and supported precision. Eligibility, costs and quotes
   are refreshed before attempts. MAGS was not found; no substitute is configured.

Those points remain distinct from the owner's accepted price risk. The latest
[price decision](../decisions/2026-09-21-copy-price-risk.json) changes only the
copy-price gate, not sizing, stop protection, identity or leverage requirements.
The [prepared support questions](ETORO-SUPPORT-QUESTIONS.md) describe the exact
missing contract. They have not been sent.

## Corporate actions and unsupported instructions

Never compare pre/post-split reference prices without reconciling price, stop and
unit bases. The ETHA issuer announces a reverse split after close on **5 October
2026**, adjusted trading from **6 October**. Keep ETHA unconfigured across that
boundary until reconciled. This release has no automatic corporate-action
adjuster. Held-unit drift is detected; never-entered reference prices also need
review. [Issuer information](https://www.ishares.com/us/products/337614/ishares-ethereum-trust-etf).

Quantified conditional target instructions are held as unsupported formats.
There is no invented sales fraction or automatic approximation. Short, non-USD
and non-unit semantics need explicit policy/adapter support.
