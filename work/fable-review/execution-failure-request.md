# User-requested Fable consultation: make this trading app work

Repository: C:/Users/loris/repos/trading. Revision 54dde35 (state checkpoint);
implementation ae3ccaa. No other agent is editing the checkout. During this
consultation the primary agent will not change trading code. Uncommitted source
projections in state/bravos are owner-run outputs. This packet and a Gradle review
task are new. These are selected conversation excerpts, not the full conversation.

## User words (verbatim excerpts)

> investigate then fix the app needs to work is the goal

> i will not ask them , what can we fix or change on our side

> fix it, ask [$fable-review](C:\Users\loris\\.codex\skills\fable-review\SKILL.md) to how to unfuck the app

> no i really meant what i said ask [$fable-review](C:\Users\loris\\.codex\skills\fable-review\SKILL.md) to fix the fucking app once and forall since you are to incompetent

Earlier constraints/corrections:

> strict maxium

> they provide the stop loss they are using you cunt

> Allow up to 2% above Bravos’s entry

> Keep the original opening’s ceiling

> Keep the partial fill; report the shortfall (Recommended)

> use Java and gradle, recent, make sure the program is robust and fullfills the mission. first plan then implement. then review against the contraints we defined to gether, do commits as you go. properly test use coverage and PIT mutation tests.

## Actual latest owner run

Zero filled purchases; state generation 101. v3 submission, real agent credentials.

- BRK.B internal $800, owner $368.80, ceiling 516.63, stop 480. Order 1591655498:
  `Rejected; broker code 1065: OrderValidation - Order attempt has failed due to a connectivity or technical issue on an HBC only path. Order will be rejected back to the client`
- ARGT internal $500, owner $230.50, ceiling 98.36, stop 89. Order 1591647468:
  `Rejected; broker code 2039: Error creating limit (IOC) order - settlement type must be real (was CFD)`
- EOG internal $500, owner $230.50, ceiling 153.01, stop 138. Order 1591647469: same 1065.
- ADI internal $500, owner $230.50, ceiling 390.02, stop 347.5. Order 1592630689: same 1065.
- MAGS no broker listing. IBIT/ETHA purchases disallowed both accounts. SMH above ceiling.

Previous v2 orders also returned 1065 for BRK.B/EOG/ADI. Moving v2 to v3 was an
owner-authorized experiment, not a verified remedy, and failed. Prior read-only
checks: active linked accounts, owner $4610 cash and internal $10000 cash, no
positions/pending orders. Eligibility allows real long X1 on the three stocks,
fractional/amount sizing/stops. Twelve hypothetical costs requests (exact IOC vs
market on both accounts for each stock) all HTTP 200, proving no actual execution.

## Scope and interpretation (primary agent; challenge these)

The intended product is a deterministic owner-operated program following Bravos
Tactical alerts and actually trading eligible opportunities. Test success or
better error messages alone do not fulfill the goal. The owner rejects making
support contact the sole path forward. We need your independent diagnosis and a
concrete, justified remedy if available, not another speculative endpoint switch.
Read-only reviewer role means primary agent will implement your recommendations.

The effective policy is strict AGENT execution ceiling, with owner consent to
copy-side slippage detected by read-back (see decisions/2026-09-21-copy-price-risk.json).
No leverage, including embedded leverage. Exact Bravos stops required. Changing to
market orders would change the price guarantee and is not presently authorized.
Neither funding nor buying old holdings merely to rebalance is authorized.

One known missed compatibility check: ARGT is configured CFD yet all openings use
limitIOC; eToro now explicitly says this combination cannot work. Another possible
experiment discussed, not implemented: units rather than amount for real IOC buys.
Would this actually have a basis, and how would it affect agreed sizing/copy
verification? Assess wider causes and alternative architecture, not just these.

## Decisive sources to inspect

- README.md, PLANNING.md, docs/trading-rules.md, config/trading.json
- docs/broker-contract.md, docs/ETORO-1065-INVESTIGATION.md
- src/main/java/com/loris/bravos/broker/{OrderPayloads,EtoroClient,Executor,HttpTransport}.java
- src/main/java/com/loris/bravos/domain/{Policy,Model}.java
- src/main/java/com/loris/bravos/app/{Workflow,OrderCompatibility,Configuration}.java
- src/test/java/com/loris/bravos/broker and app, ENGINEERING.md
- state/etoro-openapi.json: official captured specification, version 1.379.0.
  Compare actual unified request, eligibility, order lookup and alternatives.
- Latest published v2 docs were checked: limitIOC specifies limitRate; v3 uses
  same payload with mandatory settlement for non-MIT, asynchronous HTTP 202 and
  shared v2 lookup. Nothing we found documented error 1065 or an agent IOC guarantee.
- Latest local checks: 212 tests, coverage gates pass, PIT983/1088 killed. Their
  external execution coverage is synthetic and has not proven a single live fill.

## Workspace constraints for this consultation

Do not read secrets/, credentials, raw account snapshots, browser profiles or
unrelated files. Do not run trades or any commands, contact third parties, move
money or change stops. Only inspect relevant source, docs, schema and tests.
Do not invent broker capability evidence. User operates live run; primary agent
development diagnostics are read-only externally. All implementation operations
use Gradle via env.ps1/vfox. Preserve recovery ledger, local Git history and owner
state; do not reset or initialize it. Do not override price/stop constraints.

Return your independent verdict, acceptance criteria and up to three material
concerns with concrete evidence. Address the strongest challenge to this approach:
can the required price-capped trading actually work on this agent API, and what
specific app change is justified by evidence? If missing decisive evidence prevents
a reliable fix, identify exactly what it is and any owner-operable experiment
that can distinguish causes without weakening the policy. Do not call an unfilled
order or a green test suite a working trading application.
