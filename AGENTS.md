# Project working rules

- Current phase: planning and read-only testing. Do not submit trades, create or
  fund portfolios, move money, change stops, or configure a live trading schedule.
- Read `PLANNING.md` and `README.md` before extending this project. Distinguish
  agreed rules, proposals, and unverified observations.
- Never print, copy into prompts, commit, or log the contents of `secrets/`.
  Read keys locally and send them only to the intended official eToro API host.
- Keep private account snapshots in the ignored `state/` directory.
- Internal agent balances are not the owner's real-money allocation. Preserve
  that distinction in calculations and reporting.
- Use supported, documented read APIs for diagnostics. A successful GET does
  not authorize subsequent writes.
