# Project working rules

- The Java program is owner-operated: normal `run` trades, `plan` does not.
  Agent development and diagnostics remain read-only externally. Do not invoke
  the live trading command, fund portfolios, move money, change stops or install
  a live schedule. Test execution using fixtures and temporary state only.
- Read `PLANNING.md` and `README.md` before extending this project. Distinguish
  agreed rules, proposals, and unverified observations.
- Never print, copy into prompts, commit, or log the contents of `secrets/`.
  Read eToro keys locally and send them only to the official eToro API host.
  Bravos credentials may be used only for the intended Bravos login.
- Keep credentials and raw account/API captures ignored. The owner requested
  recovery state in local Git: allowlist `state/runtime/ledger.json` and numeric
  `state/runtime/history/*.json`, in addition to minimal Bravos projections.
  Use `gr checkpointState` under the application lock; never push automatically.
  Lock files, staged writes, KILL and rejected-source captures remain ignored.
- Internal agent balances are not the owner's real-money allocation. Preserve
  that distinction in calculations and reporting.
- Use supported, documented read APIs for diagnostics. A successful GET does
  not authorize subsequent writes.
- Dot-source `env.ps1` to select the project vfox pins and define `gr`.
  All operations use Gradle tasks. Never invoke `gr run` during agent work.
  Run appropriate
  tests, coverage and PIT for financial behavior changes; do not lower gates.
- Current docs are indexed by README. Archived skills/Python drafts are historical,
  not active instructions. Keep policy provenance and broker uncertainty explicit.
- Do not populate broker capability evidence or instrument profiles with guesses.
  Test success is not evidence of a live copy guarantee.
