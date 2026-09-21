# Independent whole-project robustness review

Revision: 30e43f472a00895c7f2e617fb5e1f5b0abedb452, 21 September 2026.
Checkout was clean before creating this request. No other agent is changing this
checkout; the primary agent will keep production code stable during this review.
This packet contains selected conversation excerpts, not the complete history.

## Access boundaries

Read-only review of source, tests, build and documentation. DO NOT read, glob or
grep secrets/, state/runtime/, state/capture/, other private state directories,
or any credential file. Do not run commands, call external services, or execute
the application. Scope searches to src/, config/, scripts/, docs/, decisions/,
and named root documents. These contain enough evidence. No secrets are included
in this packet. Archived drafts are historical, not current instructions.

## Verbatim user excerpts

> use [$fable-review](C:\Users\loris\\.codex\skills\fable-review\SKILL.md) on the hole project, to see if there are many huge blunters or pit falls, i do not really care about the polishing of the code only as far as it serves clarity and robustness

> use Java and gradle, recent, make sure the program is robust and fullfills the mission. first plan then implement. then review against the contraints we defined to gether, do commits as you go. properly test use coverage and PIT mutation tests.

> it is a fucking trading skill, so it should trade, execpt if i tellit not too. this was planning for the inital thing so that is why i wanted to see the backlog. going forward it will do the trades.

> i would not want my position to be adapted since i potentially buy shit price, i would only want it to be used for future invests.

> Allow up to 2% above Bravos’s entry

> Keep the original opening’s ceiling

> Deterministic parser; hold ambiguous alerts (Recommended)

> Expire an unexecuted add after a later reduction (Recommended)

> Keep the partial fill; report the shortfall (Recommended)

> i do not manually change things, the only thing i might do is early exits, nothing else and i will also do them through you / this agent so my action can be recorded

## Primary agent interpretation and current milestone

Owner-operated standalone Java/Gradle CLI replaced the skill. Normal run executes
eligible actions; plan reads external data and persists local evidence but never
submits broker writes. Initial catch-up/enrollment is separate from routine
checkpoint discovery; broad research does not silently activate an allocation.
No live execution or initial enrollment has occurred. No schedule is installed.

Read README.md, PLANNING.md, AGENTS.md and docs/trading-rules.md for effective
requirements. Precise source prices/stops must be followed; never invent stops
or target sale fractions. Never leverage or substitute instruments. Owner total
equity sizes target exposure; internal agent capital is not owner money. Funding
alone must not resize existing holdings. Partial final fills are kept without
automatic top-up. Normal source updates, early exits, and retries must preserve
durable cycle identity and avoid duplicate orders.

Current config/trading.json intentionally contains no asset profiles and blank
copy-capability evidence. Broker questions remain real release blockers: whether
the owner's copied fill inherits the agent limit IOC cap, exact copied stops,
copy sizing after funding and precision. Do not treat a config string or mocks as
proof. docs/broker-contract.md documents these separately from code defects.

## Evidence and review targets

- src/main/java/com/loris/bravos/app/{Main,Workflow,Configuration,Secrets,Capture}.java
- src/main/java/com/loris/bravos/domain/{Policy,Model,SourceBook}.java
- src/main/java/com/loris/bravos/broker/{Executor,EtoroClient,OrderPayloads,HttpTransport}.java
- src/main/java/com/loris/bravos/source/{AlertParser,BravosSource}.java
- src/main/java/com/loris/bravos/state/{StateStore,TradingState,AuditExport}.java
- src/test/java/: boundary, fake transport, workflow/restart and CLI tests
- build.gradle.kts, scripts/build.ps1, scripts/trading.ps1, .vfox.toml, .gitignore
- docs/architecture.md, docs/operations.md, docs/constraint-review.md
- docs/REHEARSAL-2026-09-21.md and ENGINEERING.md for dated results/limits
- docs/IMPLEMENTATION-PLAN.md and decisions/ for provenance if needed

Latest completed checks: 89 tests, 90.70% instruction / 78.09% branch coverage,
PIT 724/824 killed, 84 survived, 16 uncovered; gates passed. Not proof of safety.
Monday live read-only rehearsal reconstructed 218 articles/81 cycles/15 current
holdings matching dashboard; zero order attempts, no enrollment. It found and
fixed live cost value versus schema amount mismatch and older source grammar.
First scan failed closed, corrected scan succeeded. Exact current code matters
more than summaries. Do not spend the review merely repeating known API blockers.

Please inspect the whole active project for at most three most material concerns,
especially lost/duplicate exposure, missed risk-reducing actions, source ambiguity,
dry-run state effects, crashes/restarts, broker response assumptions, and tests
that encode the same wrong premise as production. Give concrete code references
and triggering scenarios. Distinguish confirmed defects from uncertainty. No
cosmetic polish or speculative architecture rewrite. Return your independent
overall recommendation and original opinion; the primary agent will validate it.
