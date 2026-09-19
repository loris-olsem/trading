# Bravos trading-helper investigation

Current stage: **planning and read-only testing**. There is no trading executor,
scheduled job, or automated Bravos scraper in this project.

Latest owner snapshot (2026-09-19 07:57 UTC): **$4,610 allocated, all available
cash, zero positions**. The $500 results below are historical observations.

Latest planning snapshot: [30-day Bravos review, 19 September 2026](BRAVOS-REVIEW-2026-09-19.md).
That report uses old closed-market quotes and the former zero-tolerance rule.
Current policy allows opening entry +2% and watches missed openings while Bravos
holds. No orders or initial baseline were created.

## Repeatable review procedure

The repo-local [`bravos-trading` skill](.agents/skills/bravos-trading/SKILL.md)
defines the fixed English workflow. Invoke `$bravos-trading` for a planning review,
or ask Codex to read that file directly if it has not appeared in the skill picker.
The entrypoint is a short ordered checklist. Trading decisions are isolated in
[decision tables](.agents/skills/bravos-trading/references/decisions.md); source
discovery and broker reconciliation are in
[evidence collection](.agents/skills/bravos-trading/references/evidence.md).
It uses a structured ledger under `state/bravos/`; the
[state contract](.agents/skills/bravos-trading/references/state.md) defines IDs,
revisions, proposals, reconciliation and interrupted-run recovery.

Routine discovery resumes from the last completed scan; the 30-day window is
only for initial catch-up. This is a draft procedure with a revision-audit policy, not a
scheduled program. Installing it does not create a baseline, initialize a live
portfolio, or run the review. Initial catch-up stays separate. Accepted policy
and answer provenance are in [PLANNING.md](PLANNING.md). The user launches reviews
in a dedicated chat. Tested helpers enforce local state mechanics; neither the
Markdown nor those tests prove exactly-once broker execution. See
[ENGINEERING.md](ENGINEERING.md) for repeatable checks and remaining work.

## eToro connection diagnostic

Requires PowerShell 7+ and outbound HTTPS access to `public-api.etoro.com`.
From this directory, run:

```powershell
.\scripts\inspect-etoro.ps1
```

The script reads the two existing files under `secrets/etoro-bravos-agent/`:
`bravos-public-key.txt` supplies `x-api-key`, and `bravos-private-key.txt` supplies
`x-user-key`. Do not paste their values into documentation, chat, or logs.

Only these fixed GET requests are made, with redirects disabled:

- `/api/v1/me`: authenticated account identity and token scopes.
- `/api/v1/agent-portfolios`: portfolios belonging to the authenticated account.
- `/api/v1/trading/info/real/pnl`: that account's balances and current activity.

The projected report is printed and written to `state/etoro-readonly.json`.
Secrets and private diagnostics under `state/` are ignored; minimal Bravos
ledgers/history/reports alone are allowlisted for local Git. The report contains
private account identifiers but no key values, request headers, or raw responses.
It is a point-in-time diagnostic, not a transaction ledger. HTTP errors remain
visible in each check; a completed script is not proof every check succeeded.

The user-reported allocation defaults to $500 and is explicitly marked
**unverified**. If the reported allocation changes, run with
`-ReportedAllocationUsd <amount>`. This parameter cannot fund or change anything.

## First verified result — 2026-09-19

- Identity and portfolio requests returned HTTP 200.
- The authenticated username begins with `Bravos-`; the full identity and IDs
  are in the ignored local report.
- The account reported internal credit of $10,000, no open positions, no pending
  orders, and no mirrors.
- Listing Agent Portfolios returned HTTP 403 / `Forbidden`. This is consistent
  with an agent-specific token; it does not indicate the agent is missing.
- The provided token advertises real trading read and write permissions. The
  diagnostic uses read operations only.
- These agent credentials do not expose the owner's real allocation. It was
  subsequently verified with the separate owner diagnostic described below.

eToro's creation documentation explains that real money is allocated to copy
an internal virtual portfolio. At an initial $500 / $10,000 ratio, a $300
internal position would represent approximately $15 of the owner's allocation,
subject to actual copying rules, eligibility, costs, and minimums. The internal
$10,000 is not additional user money and this is not a paper-trading account.

The documented UI entry point is **Agent Portfolios (Beta)** in the desktop
platform's side menu. The exact parent-side display still needs inspection in a
signed-in eToro session; opening `/portfolio` currently redirects to login.
The user declined a main-account browser sign-in for this investigation and
subsequently supplied a main-account read-only API key instead.
The user subsequently located the Bravos agent account in their own eToro UI.
Future funding must stay available for new investments without resizing existing
holdings. The user supplied an eToro help-bot response stating this is the default
for Agent Portfolios and reporting $500 net funding. Treat this as attributed
guidance, not an independently observed top-up test (see `PLANNING.md`).

Validation completed: three live GET checks, PowerShell syntax validation, and a
local scan confirming no credential values appear in generated project files.

## Owner funding diagnostic — verified 2026-09-19

Run `./scripts/inspect-etoro-funding.ps1` to refresh the actual Bravos allocation.
It uses the existing public application key together with
`secrets/etoro-main-readonly/private-key.txt` for owner reads. It also uses the
agent key for an identity check. Four GET requests verify both identities, list
the owner's agents and read the owner's portfolio/PnL. No write requests exist.

The script matches by GCID and mirror ID, cross-checks the copied customer ID,
and saves only the Bravos projection to ignored `state/etoro-owner-funding.json`.
It rejects an owner token that advertises anything other than read scopes.
Missing required funding fields fail verification; missing optional order lists
are reported as null, not zero. Failures write an unverified report and exit 1.

Live result: **$500 net contributions, $500 available mirror cash, zero open
positions, active and unpaused**. All four GETs returned HTTP 200. Top-up
behavior with existing holdings and subsequent trade scaling remain untested.
This is a point-in-time snapshot, not an automatic funding monitor or a ledger.

## Sources and next work

- [eToro authentication](https://api-portal.etoro.com/core/getting-started/authentication)
- [Agent Portfolio setup and menu location](https://www.etoro.com/news-and-analysis/etoro-updates/agent-portfolios-let-your-ai-agent-trade-for-you/)
- [Proportional copying and creation schema](https://api-portal.etoro.com/api-reference/agent-portfolios/create-agent-portfolio-v2)
- [Account identity](https://api-portal.etoro.com/api-reference/identity/get-authenticated-user-profile)
- [List Agent Portfolios](https://api-portal.etoro.com/api-reference/agent-portfolios/get-agent-portfolios)

See [PLANNING.md](PLANNING.md) for agreed entry rules and unresolved decisions.
See [ETORO-FUNDING.md](ETORO-FUNDING.md) for the documented owner read-only API
route, exact funding fields, alternatives, and additional agent-key checks.
