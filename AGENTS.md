# Project working rules

- Current phase: planning and read-only testing. Do not submit trades, create or
  fund portfolios, move money, change stops, or configure a live trading schedule.
- Read `PLANNING.md` and `README.md` before extending this project. Distinguish
  agreed rules, proposals, and unverified observations.
- Never print, copy into prompts, commit, or log the contents of `secrets/`.
  Read eToro keys locally and send them only to the official eToro API host.
  Bravos credentials may be used only for the intended Bravos login.
- Keep private account snapshots ignored under `state/`. Only minimal Bravos
  ledgers, their history and run reports are allowlisted for local Git.
- Internal agent balances are not the owner's real-money allocation. Preserve
  that distinction in calculations and reporting.
- Use supported, documented read APIs for diagnostics. A successful GET does
  not authorize subsequent writes.
