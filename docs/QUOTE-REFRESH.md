# Obtaining a usable price within one run

Previously the program read one REST quote and abandoned that entry when its
timestamp was older than 60 seconds. The owner correctly identified that this
made repeated manual runs necessary even during market hours.

`EtoroClient.quote` now follows this sequence in both planning and the checks
immediately before a live submission:

1. Read the normal v2 rate and broker trading flags. Use a fresh realtime quote
   immediately. Closed/suspended markets, delayed data and future timestamps do
   not initiate a stream.
2. For an otherwise executable but stale quote, connect to eToro's documented
   `wss://ws.etoro.com/ws` endpoint. Authenticate with the existing agent/API
   keys, subscribe only to `instrument:<exact instrument ID>` with a snapshot,
   and wait at most 20 seconds for a positive Ask with its own valid timestamp.
3. On stream timeout or failure, perform one final v2 REST quote request. Accept
   it only if it is realtime, not future-dated, and within 60 seconds. Otherwise
   explain that the refresh attempts were exhausted. Interruption stops refresh.
4. Recheck trading flags and market hours after refreshing. Execution also rejects
   an account snapshot that became older than 60 seconds during these checks.

The original agent entry ceiling, Bravos stop, sizing and copy checks still apply.
No timestamps are replaced with the receipt time. Updates without an Ask do not
refresh an old price: the live stream was observed sending Date/PriceRateID-only
messages, and there is no established contract here for treating them as a
confirmation of an unchanged ask. The socket closes after success, failure,
interruption or timeout, including a connection established after the deadline.
Individual HTTP requests retain the existing 30-second timeout; 20 seconds bounds
the stream stage, not the entire plan.

## Evidence and operational limits

The [official overview](https://api-portal.etoro.com/core/websocket/overview),
[authentication](https://api-portal.etoro.com/core/websocket/authentication),
[topics/schema](https://api-portal.etoro.com/core/websocket/topics), and
[example](https://api-portal.etoro.com/core/websocket/example-code) document the
endpoint, authentication envelope, instrument subscription and Ask/Date fields.
On 22 September the server rejected unauthenticated subscriptions, despite the
example claiming public instrument topics need no authentication. Authenticated
checks returned fresh BRK.B and EOG asks approximately 1.5–1.7 seconds old.
Other checks timed out, so streaming is not a guarantee that every instrument
will have a fresh quote on every run. The bounded REST fallback covers snapshots
that advance while waiting. Neither mechanism changes broker availability or
allows an above-ceiling purchase.

The connection uses the fixed official TLS endpoint, with no redirects or
configurable host. No private portfolio subscription or financial write is sent.
Keys, authentication responses and raw messages are never logged. Diagnostics
print only selected prices, quote ages and application-generated error codes.

After `. ./env.ps1`, `gr quoteAudit` checks streaming prices for configured
instruments without orders; `gr plan` exercises the integrated refresh path.
Fixtures cover stale-to-fresh recovery, failed-stream REST recovery, wrong topics,
missing prices, future/stale timestamps, fragmented and oversized messages,
authentication rejection, interruption, connection cleanup, unchanged price
ceilings, trading suspension during the wait, and expired account snapshots.

Two integrated live plans on 22 September checkpointed generations 36 and 38. BRK.B,
EOG and ADI were READY in both. ARGT remained held after both the stream and final REST
request failed to supply a sufficiently recent quote. SMH remained above its
ceiling; MAGS remained unlisted under its checked tickers; IBIT/ETHA still had
both-account opening restrictions. No orders were submitted. This establishes
working price acquisition and bounded recovery, not guaranteed availability of
fresh prices for every instrument.
