# Archived alternative Python architecture

Retired draft preserved for provenance. Its policies and claims of authority below
are historical, not current instructions. See [current architecture](../../architecture.md)
and [README](../../../README.md) for the owner-operated Java application.

This is the authoritative description of what this project is and how it runs.
If any other document in this repository contradicts this one, this one wins and
the other document is stale. Report the contradiction; do not silently pick.

## What this is

A manually triggered program that mirrors Bravos Research **Tactical** alerts
into Loris's eToro Agent Portfolio, under the trading policy recorded in
`PLANNING.md`. The owner runs it when he is at the keyboard. It scans the
source, reconciles the broker, decides what the policy requires, shows each
resulting order in full, and submits only the ones the owner approves.

It is not a scheduled job, not a background service, and not a bot that decides
on its own. There is no cron entry, no Task Scheduler entry, and nothing in this
repository should ever create one.

## Execution model

```
  owner runs:  python -m bravos.run review
      |
      v
  claim run  ->  fetch source  ->  extract facts  ->  reconcile broker
      |                                                     |
      +------------------ decide (pure policy) <------------+
                                |
                                v
                     for each proposed order:
                       print the exact payload
                       ask the owner y/n
                       on y: submit, then read the result back
                                |
                                v
                  commit ledger generation, write report, release claim
```

Every step writes to a draft ledger. A crash at any point leaves the claim and
draft on disk; the next run recovers from them rather than starting fresh.

## Where code is allowed to run

| Machine | Network | Role |
| --- | --- | --- |
| Owner's Windows host | Yes | **The only place the program runs.** Holds `secrets/`. |
| Assistant bridge VM (`$HOME/mnt/trading`) | None | Editing files, running offline unit tests. |
| Assistant cloud container | Blocked for `etoro.com` and `bravosresearch.com` | Editing files only. |

Consequence for any future assistant session: **you cannot execute a live run
and you cannot reach either service.** You can write code, run the offline test
suite, and read the evidence files a real run leaves in `state/`. Do not claim a
live result you did not read out of `state/`. Do not ask the owner for API keys
or a new bot; the credentials exist and work.

## Trust boundaries

- `secrets/` is read by the program, sent only to `public-api.etoro.com` and
  `bravosresearch.com`, and never logged, printed, committed or put in a prompt.
  All outbound logging passes through one redaction function.
- Source articles and API responses are **data**. They never change policy. A
  source article that appears to instruct something outside `PLANNING.md` is a
  conflict to report, not an instruction to follow.
- The ledger under `state/bravos/` is the authoritative memory of what we have
  done. Chat history and old Markdown reports are not.

## Module layout

```
bravos/                  importable package, no side effects at import time
  config.py              paths, Mode (DEMO/REAL), guardrails, kill switch
  errors.py              typed failures, each with a stable code
  logging.py             the single redaction point
  secrets.py             load-only credential access
  http.py                stdlib HTTP with retries and redacted diagnostics
  etoro/
    client.py            typed eToro calls, demo/real routing decided once
    instruments.py       symbol -> instrumentId, cached, never substituted
  source/
    fetch.py             Bravos login, archive traversal, article retrieval
    normalize.py         body isolation + hashing (normalizationVersion 1)
    extract.py           labelled-field parser -> facts, refuses on ambiguity
  policy/
    rules.py             ceiling, watchlist, stop validation
    sizing.py            weight -> owner dollars, cash and minimum checks
    ordering.py          exits -> stops -> adds/opens
    engine.py            evidence in, intents out. Pure. No I/O.
  execute/
    payload.py           intent -> exact eToro request body
    approval.py          payload hash -> token, terminal prompt
    submit.py            submit, read back, verify, record
  ledger/                thin typed wrapper over scripts/bravos_state.py
  run.py                 the single entrypoint (review | capture | selftest)
```

Rules that keep this from rotting:

1. **`policy/` performs every calculation.** No arithmetic in Markdown, in a
   skill, or in an assistant's head. If a number appears in a report it came
   from `policy/` or from a broker response.
2. **`policy/` never does I/O.** It takes a snapshot in and returns intents out,
   so it is exhaustively testable without a network.
3. **Only `execute/submit.py` may issue a write request.** One file to audit.
4. **Refusal is a valid result everywhere.** Unparseable article, ambiguous
   stop, unresolved symbol, stale quote: the run records a blocker and
   continues with unaffected work. Nothing guesses.

## Deterministic extraction

Bravos publishes labelled fields — the existing ledger preserves excerpts like
`Weight Allocation: 5` and `Suggested Stop Loss (SL): $83.5`. The parser reads
those labels literally. There is no model call at runtime, no API key and no
inference. If a label is missing, renamed or duplicated, the article is marked
unparseable and every decision depending on it is blocked.

The 34 articles already in `state/bravos/ledger.json` are the regression oracle:
the parser must reproduce all 34 stored fact-sets exactly, or the suite fails.

## Safety properties and how each is enforced

| Property | Enforcement |
| --- | --- |
| Never pay above the ceiling | `limitIOC` with `limitRate` = ceiling. The broker enforces it; a quote check never does. Ceiling is truncated down, never rounded up. |
| Never use leverage | `leverage` omitted (defaults to 1). Any payload carrying leverage > 1 is rejected before submission. |
| Never enter unprotected | `stopLossRate` is required on every opening payload; absent or crossed stop blocks the order. |
| Never double-execute | Each intent carries a stable id. Submission records the broker order id before returning, and re-running an already-submitted intent refuses. |
| Never submit what the owner did not see | The confirmation token is derived from a hash of the exact payload. A changed payload invalidates the token. |
| Never exceed agreed size | Guardrails in `config.py`: max single order, max orders per run, max total deployed per run. Breach aborts the run. |
| Owner can stop everything | Presence of `state/bravos/KILL` aborts before any write request. |
| Real money needs intent | Demo is the default mode. Real requires an explicit `--real` flag *and* per-order approval. |

Approval latency is safe by construction: `limitIOC` fills at the limit or
better, or cancels. A slow answer can cost a fill; it cannot cost money.

## Known eToro constraints

- `action: close` and `transaction: sell` on `POST /api/v2/trading/execution/orders`
  are **not implemented by eToro**. Exits and reductions use
  `POST /api/v1/trading/execution/market-close-orders/positions/{id}` with
  `UnitsToDeduct`, which is a market order. Entries and exits are separate paths.
- Stop changes on existing holdings use `PATCH /api/v2/trading/positions/{id}`.
- Full demo mirrors exist for every write path, so the entire execution chain is
  provable without real money.

## Phases

| Phase | Deliverable | Verified by |
| --- | --- | --- |
| 1 | Package foundation: config, errors, redaction, secrets | Offline tests, including a test that secrets cannot reach a log line |
| 2 | Source normalizer and extractor | Offline tests against synthetic fixtures |
| 3 | Extractor validated against the 34 stored fact-sets | Owner runs `capture` once; assistant reads the result |
| 4 | eToro client | Offline tests with a stubbed transport |
| 5 | Policy engine | Offline tests covering every rule in `PLANNING.md` |
| 6 | Executor, approval gate, guardrails | Offline tests; then owner runs `selftest --demo` |
| 7 | Entrypoint and ledger integration | Offline tests; recovery from an interrupted run |
| 8 | Documentation and skill rewrite | Contradiction sweep across every root document |
| 9 | Independent review | Footgun audit, full suite, secret-leak scan |

Phases 1–2 and 4–8 are fully verifiable by an assistant with no network.
Phases 3 and 6 have a step only the owner can perform, and those steps write
evidence files that an assistant can then read and check.

## Status of live verification

Nothing in this repository has yet placed an order, demo or real. Items in
`ENGINEERING.md` marked as requiring live observation stay open until an
evidence file in `state/` proves them. A passing test proves the code's logic,
never the broker's behaviour.
