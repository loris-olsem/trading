# Market orders with a pre-trade price check

On 22 September the owner authorized reducing requirements to get execution
working after repeated IOC rejections: "make it work reduce our requirements
if needed to make it work". The implementation relaxes the guaranteed agent
purchase ceiling only. It preserves dollar sizing, unleveraged exposure, exact
Bravos stops, instrument identity, freshness, available cash and reconciliation.

`config/trading.json` now selects `MARKET_WITH_PRICE_CHECK`. New openings and
additions submit `orderType: mkt` through the existing v3 unified endpoint with
the monetary amount, eligible settlement, leverage 1 and fixed stop. They omit
both `limitRate` and `triggerRate`. This is an explicit mode change, not a retry
fallback after an uncertain order. No whole-share rounding is introduced.

The executable ask must still pass the original entry/addition threshold and
remain above the Bravos stop immediately before submission. Market orders can
fill at a worse price. The IOC-specific 10% limit-deviation rule does not apply.
If read-back finds an agent or copied fill above the threshold, the existing
review hold prevents further purchases. It does not sell automatically or undo
the fill. Missing copied protection, unknown results and partial pending orders
continue to hold execution. Terminal protected partial fills keep their existing
no-top-up behavior.

The plan labels this as a market buy with a **Price check**, not an agent limit.
`gr plan` submits no orders. The owner uses `gr run` for live execution; development
does not invoke it. Do not reinitialize or clear state. Old journal entries without
an order type deserialize as `limitIOC`; new entries persist their order type.
Confirmed holdings are not bought again after restart or a funding increase.

Configured X1 CFDs can now pass normal eligibility checks in market mode. The
previous error 2039 was specific to IOC/CFD, not a blanket ban on these instruments.
IBIT/ETHA account restrictions and missing MAGS listings remain broker constraints.

The [official order contract](https://api-portal.etoro.com/api-reference/trading--real/create-an-order)
documents market orders and these fields. A [developer's firsthand report](https://www.reddit.com/r/AskeToroTeam/comments/1vo3bur/agent_portfolio_api_amount_and_balance_appear_to/)
describes a successful amount-based market buy in an Agent Portfolio via v2.
That is evidence for trying the simpler order type, not proof this account's v3
execution will succeed or that error 1065 was caused by IOC. Only actual broker
read-back after an owner-operated run can establish that.
