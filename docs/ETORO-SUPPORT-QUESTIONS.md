# Questions for eToro: Agent Portfolio execution contract

Prepared for the owner to send to eToro support. This has not been sent.
No account identifiers, tokens or balances are needed for these product questions.

> I use an existing Agent Portfolio through the Public API. The agent has its
> own trading token, and its trades are copied into my funded owner allocation.
> Please answer specifically for Agent Portfolios, rather than ordinary CopyTrader.
>
> 1. If the agent submits a v2 `limitIOC` long order with `execution.price = X`,
>    is the owner's copied position also guaranteed never to fill above X?
>    If not, is there a documented Agent Portfolio API field or order type that
>    enforces that cap on the owner's copied fill before execution?
> 2. What exact formula converts the agent's new order amount to the owner's
>    copied order amount after unrealized profit/loss and additional owner funding?
>    Is the denominator realized equity (cash plus invested amounts), total equity
>    including unrealized PnL, initial funding, or another value? Which API fields
>    supply those quantities? Public CopyTrader and agent examples do not resolve
>    this case consistently.
> 3. Does an agent order's fixed stop-loss rate propagate as the same absolute
>    rate to the owner's new copy? Do later fixed-rate edits propagate the same
>    way? Can copy-side limits change or omit that stop?
> 4. Where does the supported Public API expose quote currency, price precision
>    and supported fractional-unit precision for arbitrary instruments? Symbol
>    metadata and eligibility do not include all these fields. Watchlist
>    enrichment exposes price precision, but is not an arbitrary-instrument query.
> 5. When I add money to an existing Agent Portfolio that holds positions, does
>    that money remain cash without changing existing units? An earlier support
>    answer said yes; please confirm the Agent Portfolio contract and whether
>    any default reallocation can override this.

Do not fill capability evidence strings merely because support acknowledged the
question. Preserve the actual applicable answer and link it from the relevant
configuration evidence. A measured fill below a limit would not establish a
general copy-side price guarantee.
