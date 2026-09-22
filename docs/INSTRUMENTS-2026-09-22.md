# Instrument availability correction, 22 September 2026

The plan previously collapsed missing configuration and broker refusal into
`INSTRUMENT_UNVERIFIED`. That made a newly supported stock indistinguishable
from an investment eToro would not allow this owner to buy.

Read-only symbol lookup and eligibility checks on 22 September established:

| Bravos symbol | eToro identity | Current result |
| --- | --- | --- |
| ADI | ADI.US / 4264, Analog Devices Inc | Both accounts allow real long X1 with fixed stops; execution profile added |
| IBIT | IBIT / 1367, iShares Bitcoin Trust | Both accounts return `allowOpenPosition: false` |
| ETHA | ETHA.US / 12152, iShares Ethereum Trust ETF | Both accounts return `allowOpenPosition: false` |
| MAGS | Neither MAGS nor MAGS.US returned | No exact listing found; no replacement selected |

ADI's [SEC filing](https://www.sec.gov/Archives/edgar/data/6281/000000628126000052/adi-20260502.htm)
identifies Nasdaq common stock under ADI. The eToro response identifies the same
company as Stocks, exchange 4, with the ADI.US symbol. Its calculation precision
and US market calendar use the existing sourced [execution conventions](INSTRUMENTS-2026-09-21.md#execution-profiles).
Each entry still rechecks identity, both accounts' eligibility, costs, quote,
price ceiling and exact stop. A profile is not unconditional permission to buy.

## Automatic checks and their boundary

`config/trading.json` now separates executable `assets` from `lookupOnlyAssets`.
IBIT and ETHA have lookup-only identities: their exact IDs and aliases allow the
program to report current account restrictions on every run. They cannot produce
orders even if eToro later allows opening; an execution profile must first be
established. In particular, ETHA's pending corporate action still requires the
source price/stop basis to be reconciled, as recorded in the broker contract.

For an unknown symbol, the app checks the Bravos ticker and its `.US` alias.
No match produces a listing message; a candidate produces an explicit application
setup message. Paginated/incomplete responses produce a retryable lookup failure,
not a claim that the investment is unavailable. These lookups never populate an
execution profile or authorize a similarly named asset automatically. Metadata
alone does not establish product leverage and source identity for every asset.
This improves automatic diagnosis; it is not fully automatic onboarding of
arbitrary new investments.

The published OpenAPI contract for `GET /api/v2/market-data/instruments` defines
HTTP 404 as no instrument matching the supplied criteria. The unknown-symbol
lookup treats that response like an empty result. Known identities returning 404
and other HTTP failures remain data errors, rather than being described as absent
listings. This was exercised against the live MAGS/MAGS.US lookup and fixtures.

The same fresh account checks distinguish refusal on both accounts, only the
agent, only the owner, and incompatible order/stop settings. A failure holds only
the affected entry. No historical restriction is treated as permanent.

Reproduce via `. ./env.ps1`, then
`gr instrumentAudit '-Psymbols=ADI.US,IBIT,ETHA.US,MAGS,MAGS.US'` and `gr plan`.
Raw responses remain ignored in `state/capture/`; no trades were submitted.

## Live plan verification

Two full `gr plan` runs completed and checkpointed generations 30 and 32.
ADI was READY in both, proposing $230.50 owner / $500 internal, limit $390.02
and the published stop $347.50. The first run also had BRK.B, ARGT and EOG READY;
the second held those for quotes older than 60 seconds. SMH remained above its
entry ceiling. The second run confirmed the corrected MAGS no-listing paragraph
and explicit both-account refusals for IBIT/ETHA. No current holding or price is
inferred from an older plan, and none of these checks submitted orders.
