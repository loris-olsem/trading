# Repeated agent order rejection: 22 September 2026

Status: unresolved execution failure. No claim of a working live trading path.
The development investigation made only read/what-if calls; no orders were submitted.

Owner-authorized next attempt: the app now submits openings/additions through
the documented v3 asynchronous endpoint, retaining the exact limitIOC payload.
This is a controlled compatibility change, not evidence that error 1065 is fixed.
`gr run` uses it automatically. The v2 lookup reconciles both old and new orders;
HTTP 202 never marks an entry completed. There is no automatic endpoint fallback.

## Verified evidence

Seven journaled buy attempts returned terminal Rejected, error 1065 and an empty
`positionExecutions` array. Three instruments were affected: BRK.B (1118), EOG
(1581), ADI.US (4264). Both accounts are active, linked correctly, have available
cash and complete empty pending-order collections. No position was opened.

Fresh eligibility permits long real X1 orders, fractional quantities, sizing by
amount, and fixed stops for all three instruments on the agent and owner accounts.
Eligibility exposes `allowMitOrders`, but no explicit `allowLimitIOC` flag. MIT
permission is not evidence of IOC execution support.

`gr orderCompatibility` compared the latest saved buy request with a market-shaped
what-if request for each instrument and each account. It changed only the account
amount and, for the market comparison, removed `limitRate` and set `orderType=mkt`.
All twelve cost requests returned HTTP 200. No execution endpoint was called.
The private evidence is `state/capture/order-compatibility.json`.

The request shape matches the [v2 order contract](https://api-portal.etoro.com/api-reference/trading--real/create-an-order).
The [agent guide](https://raw.githubusercontent.com/etoro-builders/etoro-agent-skills/main/skills/etoro-agent-portfolios/SKILL.md)
says agents use the same trading endpoints as main accounts. Neither source
establishes this account's working IOC route. The documented v3 path changes
submission to asynchronous processing; it does not document a remedy for 1065.
The [public status page](https://status.etoro.com/) reported operational APIs and
trading with no incident for 22 September when checked. That does not exclude a
problem restricted to agent accounts or a particular execution route.

No evidence supports changing settlement, order sizing or API version as a fix.
Market and market-if-touched orders do not enforce the required purchase ceiling,
so they are not automatic fallbacks. A successful what-if request is not a fill.

## Prepared support request — not sent

Subject: Agent Portfolio limitIOC orders consistently rejected with error 1065

Please escalate to the Public API/trading execution team. My Bravos Agent
Portfolio receives error 1065 on every limitIOC opening attempt across three
US stocks. The order lookup says:

> OrderValidation - Order attempt has failed due to a connectivity or technical issue on an HBC only path. Order will be rejected back to the client

All executions are empty; the account has no pending orders. The agent token is
used for execution and the owner's separate read-only token only for reads.
Latest reproducible order references, all on 2026-09-22, UTC:

| Instrument | Order ID | X-Request-Id | Approximate submission time |
| --- | --- | --- | --- |
| BRK.B | 1592630664 | 4a539f4f-2298-4146-b228-5c21e19eab22 | 18:46:04 |
| EOG | 1591647454 | c6c52e8f-eb6a-42d1-99d1-9881c07b4d78 | 18:47:04 |
| ADI.US | 1591655469 | 9b0d70c1-c26a-457d-8832-3688a7831078 | 18:48:10 |

Example request to `POST /api/v2/trading/execution/orders`:

```json
{
  "action": "open",
  "transaction": "buy",
  "instrumentId": 1118,
  "settlementType": "real",
  "orderType": "limitIOC",
  "limitRate": 516.63,
  "leverage": 1,
  "amount": 800,
  "orderCurrency": "usd",
  "stopLossRate": 480,
  "stopLossType": "fixed"
}
```

Fresh eligibility allows real long X1 with stops and amount sizing. The exact
limitIOC request also returns 200 from `/api/v2/trading/info/costs` on both agent
and owner accounts (with their respective amounts).

Please trace the reference IDs and identify the actual rejection cause. Does an
Agent Portfolio support limitIOC orders with a fixed stop, and is an account
enablement or request change required? If this route is unsupported, which API
order type enforces a maximum purchase price for Agent Portfolios? A market or
market-if-touched replacement without an execution cap does not meet the requirement.

## Next evidence needed

An eToro trace/confirmed compatibility correction, followed by an owner-operated
run and verified agent/copied fills, amounts and stops. Existing order references
remain in the journal. Do not reset state or declare success from fixture tests.
