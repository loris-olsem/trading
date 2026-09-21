# eToro contract and outstanding evidence

The existing agent has a trading token; the owner token is read-only. Requests go
only to `https://public-api.etoro.com`. Credentials never enter reports or error
output. Agent activity affects **real owner money**, despite the internal virtual
balance. No demo/live trades were made during development.

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

Execution routes are restricted to agent credentials:

- `POST /api/v2/trading/execution/orders`: `limitIOC`, leverage 1, exact fixed stop.
- `GET /api/v2/trading/info/orders:lookup`: order ID or durable UUID reference.
- `PATCH /api/v2/trading/positions/{id}`: exact fixed-stop update.
- `POST /api/v1/trading/execution/market-close-orders/positions/{id}`: full/partial close.
- `GET /api/v1/trading/info/real/close-orders/{id}`: close execution proof.

## Unverified capabilities

`config/trading.json` has blank evidence and no asset profiles. Do not bypass
these gaps with placeholder strings. They are contract verification, not a
per-order approval flow.

1. **Owner price cap:** agent `limitIOC` caps the agent fill. We have not established
   that the owner's copy inherits the same cap. The owner subsequently accepted
   that risk, choosing `copyPricePolicy: AGENT_LIMIT_WITH_COPY_CHECK`. Keep
   `copyPriceCeilingEvidence` empty: consent is not broker evidence. Post-fill
   `openRate` checks detect overpayment and block further purchases; they cannot
   prevent or undo it. No automatic corrective sale is authorized. The default
   `REQUIRE_COPY_GUARANTEE` mode remains available and requires actual evidence.
2. **Copy sizing:** verify the realized-equity ratio above for Agent Portfolio new
   orders with existing PnL and later deposits. Only then select
   `REALIZED_EQUITY_RATIO` and set `copySizingEvidence`.
3. **Stop propagation:** establish exact fixed stops on initial copied positions
   and later edits. Read-back exists, but empty accounts cannot prove propagation.
   Record verified evidence in `copyStopsEvidence`.
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
