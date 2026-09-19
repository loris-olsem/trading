# Bravos catch-up review — 19 September 2026

Planning snapshot only. No orders, permanent skips, or processed-alert decisions
were created. Scope: Tactical opening alerts from **21 August through 19 September
2026**, inclusive (30 calendar dates including today, Europe/Luxembourg).
There were no 20 August alerts in the scanned archive, so using the preceding
30 completed dates instead would produce the same opening set today.

Reviewed Trade Alert archive pages 1–3, crossing the boundary into 19 August:
27 in-window alerts, including nine openings and subsequent changes/closures.
Reconciled against the Tactical dashboard last updated 18 September. Seven of
the nine positions remain open. Quant is a separate strategy and is not included.

## Price comparison

eToro asks retrieved at **2026-09-19 06:42:09 UTC / 08:42:09 Luxembourg**.
The quotes themselves are from **18 September around 19:59 UTC / 21:59 Luxembourg**.
All six mapped instruments report `isExchangeOpen=false` and
`isCurrentlyTradable=false`. Their `quoteType=realtime` designation does not make
these old quotes executable now. Comparisons are indicative until market reopening.

All entry and ask amounts below are USD per share. Differences compare the
ask with the original opening price, not the latest addition price or the bid.

| Opening | Instrument | Original entry | Latest eToro ask | Difference | Snapshot outcome | Original → current Bravos weight |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 14 Sep | EOG | $150.17 | $144.19 | -3.98% | Passes price rule; still open | 5% → 5% |
| 31 Aug | CF | $130.37 | $127.79 | -1.98% | Passes price rule; still open | 5% → 4% |
| 2 Sep | ARGT | $96.44 | $92.98 | -3.59% | Passes price rule; still open | 5% → 5% |
| 2 Sep | BRK.B | $506.50 | $509.84 | +0.66% | Would skip: above original entry | 5% → 8% |
| 3 Sep | IBIT | $44.86 | $46.02 | +2.59% | Would skip: above original entry | 3% → 3% |
| 18 Sep | ETHA | $19.23 | $19.92 | +3.59% | Would skip: above original entry | 3% → 3% |
| 3 Sep | MAGS | $70.26 | Unavailable | — | Exact instrument not found on eToro; blocked | 5% → 5% |
| 26 Aug | EWW | $77.98 | Not needed | — | Exclude: Bravos closed 16 Sep at $73.80 | 5% → 0% |
| 21 Aug | SRUUF | $19.90 | Not needed | — | Exclude: Bravos closed 16 Sep at $19.28 | 5% → 0% |

The three price-pass candidates remain above their latest published reassessment
levels: EOG $138, CF $121.50 (raised from $119.50 on 2 September), ARGT $89.
These levels are not mechanically implemented stops.

## Instrument mapping and remaining checks

| Bravos symbol | eToro symbol | eToro instrument ID | Name / type |
| --- | --- | ---: | --- |
| EOG | EOG | 1581 | EOG Resources Inc / Stocks |
| CF | CF | 1890 | CF Industries Holdings Inc / Stocks |
| ARGT | ARGT | 3421 | Global X MSCI Argentina ETF / ETF |
| BRK.B | BRK.B | 1118 | Berkshire Hathaway Inc / Stocks |
| IBIT | IBIT | 1367 | iShares Bitcoin Trust / ETF |
| ETHA | ETHA.US | 12152 | iShares Ethereum Trust ETF / ETF |

**Ticker collision:** eToro's `ETHA` is Ethereum / VAULTA, a crypto pair with
instrument ID 100125. It must not be substituted for the ETF. Name search found
the correct ETF as `ETHA.US`; the price comparison uses ID 12152.

MAGS was absent from exact-symbol lookup and a free-text MAGS search returned
no matches. Searching Magnificent returned YMAG, a different options-income
fund. No substitution was made. This is an API search result, not a guarantee
that eToro will never offer MAGS.

Catalog presence does not establish that the user's agent can open the actual
underlying security. Account eligibility, underlying versus CFD execution,
minimum copied amounts and costs remain to be checked before an actionable
proposal. No order previews or orders were submitted.

CF's later reduction means sizing needs a policy: entering at its current 4%
weight would differ from replaying the original 5% opening. For illustration
only, current weights of EOG 5%, CF 4%, ARGT 5% applied to $500 would total $70
($25 + $20 + $25), before costs/minimums. This sizing rule has not been adopted.

BRK.B's 14 September addition at $513.55 does not replace its original $506.50
entry ceiling. The latest ask is below the addition price but above the original
entry, so it still fails the user's price rule.

Other current dashboard holdings (EWJ, XLF, IBB, XLV, NTRA, TBBB, NVDA, TEVA)
have no opening in this window. Their recent reductions/additions do not create
new catch-up openings. Closing alerts for older positions likewise create no
entry candidate.

## Evidence and refresh

Primary Bravos sources read in the authenticated browser:

- [Trade Alert archive](https://bravosresearch.com/category/portfolio-update/), [page 2](https://bravosresearch.com/category/portfolio-update/page/2/), [page 3](https://bravosresearch.com/category/portfolio-update/page/3/)
- [Current Tactical dashboard](https://bravosresearch.com/research/)
- [EOG opening](https://bravosresearch.com/news-feed/initiating-long-on-eog-resources-inc-eog-breakout/)
- [CF opening](https://bravosresearch.com/news-feed/initiating-long-on-cf-industries-holdings-inc-cf-breakout/) and [2 September reduction](https://bravosresearch.com/portfolio-update/booking-partial-profits-in-cf-industries-holdings-cf-profit-booking/)
- [ARGT opening](https://bravosresearch.com/news-feed/initiating-long-on-global-x-msci-argentina-etf-argt-breakout/)
- [BRK.B opening](https://bravosresearch.com/news-feed/initiating-long-on-berkshire-hathaway-inc-brk-b-breakout-2/) and [14 September addition](https://bravosresearch.com/news-feed/increasing-exposure-to-berkshire-hathaway-inc-brk-b-technical-strength/)
- [IBIT opening](https://bravosresearch.com/news-feed/initiating-long-on-ishares-bitcoin-trust-ibit-breakout/)
- [ETHA opening](https://bravosresearch.com/news-feed/initiating-long-on-ishares-ethereum-trust-etf-etha-breakout/)
- [MAGS opening](https://bravosresearch.com/news-feed/initiating-long-on-roundhill-magnificent-seven-etf-mags-breakout/)
- [EWW closure](https://bravosresearch.com/news-feed/closing-ishares-msci-mexico-etf-eww-breakdown/) and [SRUUF closure](https://bravosresearch.com/news-feed/closing-ishares-sprott-physical-uranium-trust-sruuf-breakdown/)

eToro official API GETs, using the owner's read-only key:

- `/api/v2/market-data/instruments?symbols=ETHA,EOG,IBIT,MAGS,ARGT,BRK.B,CF&pageSize=100`
- `/api/v2/market-data/instruments/search` with iShares Ethereum, Magnificent, and MAGS queries.
- `/api/v2/market-data/rates?instrumentIds=1118,1367,1581,1890,3421,12152`
- `/api/v1/market-data/search` filtered by those six IDs, projecting instrument
  identity, exchange and market status. Returned IDs were checked against the filter.

Market-data snapshots are kept in ignored `state/bravos-instrument-lookup.json`,
`state/bravos-rates.json`, and `state/bravos-market-status.json`. Endpoint schemas
were checked against the official OpenAPI specification already downloaded in
`state/etoro-openapi.json`. Requests disabled redirects and never logged keys.

Before treating this as the actual catch-up evaluation, refresh later Bravos
alerts and executable eToro asks during market hours, confirm instrument
eligibility and sizing, then apply the agreed permanent-skip policy at that
defined evaluation time. This report does not create a pullback watchlist.
