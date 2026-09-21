# Architecture

No runtime LLM is used. Supported Bravos instructions become typed facts;
ambiguous inputs stop processing. The CLI coordinates source, policy, state
and broker components with injectable boundaries for offline tests.

`ReportFormatter` is a presentation adapter over the durable diagnostic report.
It groups messages by instrument into console paragraphs without making trading
decisions or changing the saved event/attempt records. Unknown diagnostics remain
visible rather than being interpreted as success. Workflow records quote-wait
details using the same observation time as the decision and explicitly reports
unchanged holdings after a complete evaluation.

## One run

1. `Main` validates arguments and obtains the exclusive `StateStore` file lock.
2. `Workflow.reconcileOnly` resolves existing attempts through broker reads before
   source login. An unknown submission never becomes an automatic second request.
3. `BravosSource` logs in, traverses the discovery interval, rereads active or
   unresolved articles, and audits other known URLs every seven days. It rechecks
   the first archive page and retries once on change. Authentication/layout errors
   mean incomplete discovery, not an empty feed.
4. `AlertParser` extracts supported instructions. `SourceBook` retains revisions,
   identifies cycles by their opening, applies chronology and compares open weights
   with the Tactical dashboard. Material edits and broken chains block processing.
   Incomplete scans never mutate accepted source state. Complete scans are staged
   on a copy and checked before acceptance; rejected observations are saved under
   private `source-review/` evidence. Existing trade participation stays intact.
5. `Workflow` reconciles linked agent/copied holdings, then handles exits and
   reductions, exact stops, and finally openings/adds by publication date and ID.
6. `Policy` calculates decimal intents. `EtoroClient.prepare` refreshes account,
   quote, eligibility and costs immediately before each buy.
7. `Executor` saves an attempt and UUID **before** HTTP submission. A receipt means
   submitted; order execution and both account read-backs establish confirmation.
8. `AuditExport` writes minimal projections for Git; private runtime state remains
   authoritative.

| Component | Owns |
| --- | --- |
| `source/BravosSource` | Login, same-origin navigation, archive/dashboard DOM |
| `source/AlertParser` | Supported language patterns, numbers, authored-body hash |
| `domain/SourceBook` | Deduplication, revisions, cycles, source exposure |
| `domain/Policy` | Prices, equity sizing, proportional units, stops |
| `app/Workflow` | Ordering, enrollment, event consumption, batches, reconciliation |
| `broker/Executor` | Durable submission boundary and attempt transitions |
| `broker/EtoroClient` | Official API contract, identity link, preflight, execution proofs |
| `broker/OrderPayloads` | Order, stop and close payloads |
| `state/StateStore` | Lock, validation, history, atomic replacement |
| `app/Main` | CLI and connection assembly |

## Persistence and interruption

`state/runtime/ledger.json` contains revisions, cycles, completed event keys,
original multi-lot reduction batches, attempts, holding baselines and early exits.
`history/<generation>.json` retains prior generations. Saves stage, force and
atomically replace the journal, then read back its generation. Missing required
fields, bad references and duplicate lot links fail. This is structural validation,
not protection against deliberate edits.

Attempt states: `SUBMITTING`, `SUBMITTED`, `PARTIAL`, `CONFIRMED`, `REJECTED`,
`UNKNOWN`. `PARTIAL` means execution/copying/protection is unresolved. A terminal
partial fill with verified exact stops is `CONFIRMED`, stores filled dollars and
shortfall, consumes the event and is never automatically topped up.

Opening responses lost after submission are looked up by the saved UUID. Lost
close responses remain unknown because a reliable reverse lookup has not been
established. Pending/unknown attempts block new exposure. Known PARTIAL fills can
receive a narrowly verified agent-stop repair before normal processing; copy-only
failures are explicitly reported for owner intervention. No owner writes are used.
This is conservative
recovery, not an exactly-once claim about eToro.

Reduction batches preserve original quantities across restarts. Holding baselines
detect unexplained unit changes. Funding creates no policy action. A full exit
ends participation; a price improvement cannot reopen that cycle.
An attempt cancelled by the local kill switch before submission can receive a new
UUID without changing its durable batch. Confirmed batch members are reused, not
resubmitted; unknown outcomes never qualify for this retry.

## Discovery versus enrollment

Default initial enrollment: last 30 calendar days. Acquisition starts earlier and
expands in bounded 90-day steps to reconstruct the dashboard. Research does not
enroll older openings; an explicit `gr initialize -Psince=YYYY-MM-DD` chooses another floor.
Routine discovery starts at the last complete checkpoint date, including that
whole day, without a 30-day cap. Incomplete scans do not advance it. Revision
audits/dashboard checks cannot prove discovery of every unseen backdated article.
