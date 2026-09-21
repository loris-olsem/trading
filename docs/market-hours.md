# Market hours

The owner [accepted](../decisions/2026-09-21-market-hours.json) using the official
US trading calendar, fresh prices and eToro's `isCurrentlyTradable` flag after
live API reads reported `isExchangeOpen: false` during regular trading hours.

The five configured US instruments use `marketHours: US_EQUITIES_2026`. This
requires a weekday core session in America/New_York, normally 09:30 inclusive
to 16:00 exclusive. The published 2026 holidays are closed; November 27 and
December 24 close at 13:00. Java's time-zone rules handle daylight saving.
There is no pre-market or after-hours entry.

Sources checked 21 September 2026:
[NYSE](https://www.nyse.com/trade/hours-calendars),
[Nasdaq](https://www.nasdaq.com/market-activity/stock-market-holiday-schedule),
[Nasdaq Trader calendar](https://www.nasdaqtrader.com/Trader.aspx?id=Calendar).
Both exchanges publish matching 2026 dates. `UsEquityCalendar` encodes those
dates directly; it does not extrapolate generic federal-bank holidays.

A realtime, non-future ask no older than 60 seconds, eToro's separate tradability
flag, both accounts' eligibility, the strategy ceiling and the exact stop are
still required. Pre-submission reads recheck the same conditions. A published
calendar is not a live halt feed; the broker tradability check and immediate
limit order remain necessary. If a broker restriction is reported, skip the
instrument. No market order or queue-until-open fallback is used.

The calendar currently covers **2026 only**, the year verified for both exchanges.
Beyond that, `MARKET_CALENDAR_EXPIRED` blocks entries until the next published
calendar is reviewed, encoded and tested. Extend the documented year coverage
together with holidays, early closes and boundary tests; do not silently assume
future dates or default to weekdays. Apply announced exceptional closures through
a reviewed calendar update. Existing broker stops do not depend on this calendar.

Unconfigured future instruments default to `BROKER_FLAG`, which retains the
original API exchange-open check. Assign a calendar only after identifying the
instrument's actual market; this US calendar is not applicable to other exchanges,
options, futures or continuously traded crypto.
