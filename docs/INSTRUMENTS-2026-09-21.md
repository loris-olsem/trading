# Instrument investigation, 21 September 2026

Read-only checks used the existing agent and owner tokens with the official
eToro API. No orders or account changes were made. The private response is at
`state/capture/instruments.json`; it is not committed. These observations are
identity and eligibility evidence. The sourced execution profiles below use it.

| Bravos symbol | eToro symbol / ID | API display name | Opening allowed on both accounts | Non-potential long X1 settlement |
| --- | --- | --- | --- | --- |
| CF | CF / 1890 | CF Industries Holdings Inc | Yes | real |
| BRK.B | BRK.B / 1118 | Berkshire Hathaway Inc | Yes | real |
| ARGT | ARGT / 3421 | Global X MSCI Argentina ETF | Yes | cfd |
| MAGS | Not identified | — | Unknown | Unknown |
| IBIT | IBIT / 1367 | iShares Bitcoin Trust | No | cfd configuration exists, opening disabled |
| EOG | EOG / 1581 | EOG Resources Inc | Yes | real |
| ETHA | ETHA.US / 12152 | iShares Ethereum Trust ETF | No | cfd configuration exists, opening disabled |
| SMH | SMH / 6357 | VanEck Vectors Semiconductor ETF | Yes | cfd |

An exact ticker lookup for **ETHA** returned ID 100125, **Ethereum / VAULTA**,
type Crypto. This is not the requested fund. A name search for `iShares Ethereum`
returned the correct ETF under **ETHA.US**. Matching a ticker alone is insufficient.

MAGS did not appear in the requested-symbol response. Searching `Magnificent`
returned YMAG, a different options-income fund. Searching `Roundhill` returned
DRAM and METV, also different funds. These are not substitutes. The searches do
not prove that every possible name search for MAGS will fail.

Eligibility is account-specific and time-dependent. An X1 configuration can
exist even when `allowOpenPosition` is false. The program must check both facts.
Likewise, a configuration permitting stop orders does not prove that copied
owner positions inherit the exact requested stop or entry limit.

## Reproduce without trading

```powershell
. ./env.ps1
gr instrumentAudit -Psymbols=CF,BRK.B,ARGT,MAGS,IBIT,EOG,ETHA.US,SMH
gr instrumentAudit '-Pquery=iShares Ethereum'
gr instrumentAudit '-Pquery=Magnificent'
```

Symbol mode reads `/api/v2/market-data/instruments`, then calls the documented
read-only `/api/v2/trading/info/eligibility` calculation for both accounts.
Search mode reads `/api/v2/market-data/instruments/search` and produces candidates
only. Its separate private output is `state/capture/instrument-search.json`.
Neither mode edits configuration or authorizes orders. Incomplete paginated
symbol responses fail rather than presenting missing instruments as absent.

## Execution profiles

Configured: CF, BRK.B, EOG, ARGT and SMH, with the IDs and settlement types in
the table. The three ordinary US common stocks have no embedded multiplier:
[CF issuer](https://ir.cfindustries.com/Investors/company-profile/default.aspx),
[Berkshire SEC cover](https://www.sec.gov/Archives/edgar/data/1067983/000119312526083899/R1.htm),
[EOG SEC filing](https://www.sec.gov/Archives/edgar/data/821189/000082118926000149/eog-20260630.htm).
[ARGT](https://www.globalxetfs.com/funds/argt) and
[SMH](https://www.vaneck.com/us/en/investments/semiconductor-etf-smh/) have ordinary
index-tracking objectives, not leveraged multiples. These profiles refer to their
US-listed, dollar-quoted instruments; exact eToro identity and both accounts'
long X1 eligibility are rechecked before entry. An X1 CFD is still a CFD and can
have financing costs; the live owner-side costs calculation remains mandatory.

Price calculations round down to cents (`priceScale: 2`). Cent-aligned limits
fit both the $0.01 and $0.005 US equity tick increments described in the
[SEC rule](https://www.sec.gov/files/rules/final/2024/34-101070.pdf).
Fractional partial-sale calculations round down to five decimals (`unitScale: 5`),
grounded in eToro's published fractional-share support up to five decimals
([eToro registration statement](https://investors.etoro.com/static-files/f7f8a7db-abc1-4f30-8250-dcca695b57fa)).
The Public API accepts positive numeric units and does not publish a per-asset
decimal maximum. Five is a conservative calculation granularity, not a claim of
fresh asset-specific precision metadata or a CFD execution guarantee. Full exits
use all actual units returned by the broker, without this rounding. Exact Bravos
stop rates are never rounded or altered to fit a profile.

For limits, the API also rejects prices more than 10% from market. The program
uses the lower of the strategy ceiling and fresh ask × 1.09, rounded down, then
rechecks the 10% bound immediately before submission. This only tightens the
maximum; a rejected or completely unfilled opening remains eligible for a later
fresh assessment. A partial fill is retained without an automatic top-up.

IBIT and ETHA are currently unavailable for opening on these accounts. They are
not configured; ETHA also has the corporate-action boundary in the broker guide.
MAGS remains unidentified. None receives a substitute. These exclusions do not
block the five configured instruments.

## Metadata limitations

The captured OpenAPI schema exposes currency and price precision in
`InstrumentMetadataSlim`, nested under watchlist enrichment. The symbol metadata
and eligibility responses used above do not expose supported fractional-unit
precision. The former implicit defaults were not broker evidence. Configuration
requires explicit calculation precision; the sourced choices above replace those
defaults. Omitting either rejects the profile.

The [broker contract](broker-contract.md) separately tracks owner-copy price,
sizing and stop propagation. Account eligibility does not settle those questions.

The documented read-only `POST /api/v2/watchlists` endpoint was also checked through
`gr watchlistMetadata`, with both `ensureBuiltinWatchlists` and `addRelatedAssets`
explicitly false. The returned `instrumentMetadataSlim` fields were null, so this
did not establish price precision or quote currency. The response stays ignored
at `state/capture/watchlist-metadata.json`. No watchlists or assets were created.
