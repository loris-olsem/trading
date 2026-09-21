# Instrument investigation, 21 September 2026

Read-only checks used the existing agent and owner tokens with the official
eToro API. No orders or account changes were made. The private response is at
`state/capture/instruments.json`; it is not committed. These observations are
identity and eligibility evidence, not approved execution profiles.

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

## Remaining profile evidence

The captured OpenAPI schema exposes currency and price precision in
`InstrumentMetadataSlim`, nested under watchlist enrichment. The symbol metadata
and eligibility responses used above do not expose supported fractional-unit
precision. Defaults of two price decimals and six unit decimals in the existing
configuration class are not evidence of broker support. Do not populate complete
execution profiles by copying those defaults.

The [broker contract](broker-contract.md) separately tracks owner-copy price,
sizing and stop propagation. Account eligibility does not settle those questions.
