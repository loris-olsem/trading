# State contract — version 1

Use this contract when `$bravos-review` reads or writes memory. All paths below
are relative to the project root. Keep credentials out of every record.

## Files and ownership

- `state/bravos/ledger.json`: authoritative structured memory; one JSON document.
- `state/bravos/history/<generation>-<runId>.json`: immutable prior generations.
- `state/bravos/runs/<runId>.md`: readable report, including incomplete attempts.
- `state/bravos/run.lock`: exclusive claim identifying the writing run.

These files are ignored by Git because they contain account/strategy state.
Local history protects against an interrupted edit, not disk loss; arrange a
private backup before relying on this for long-running operation. Never rebuild
lost permanent decisions by guessing from the latest dashboard or holdings.
No ledger is created merely by installing this skill.

## Ledger contents

Store the following named members. Empty collections are allowed; unknown
values are JSON null, never an invented zero, empty success or timestamp.

| Member | Required contents |
| --- | --- |
| `schemaVersion` | Integer 1; reject unsupported versions rather than auto-migrating |
| `generation` | Integer incremented once per committed run |
| `runId`, `committedAtUtc` | Identity/time of the writing run |
| `mode` | `planning` |
| `activationAtUtc` | null until separately established initial catch-up/activation |
| `policyVersion` | Version/hash of the actual decision rules used; do not silently relabel old evaluations |
| `discovery` | `lastCompleteDiscoveryAtUtc`, `lastHistoricalRevisionAuditAtUtc`, and latest attempt's floor, visited URLs, boundary evidence, status and errors |
| `articles` | Map from stable article key to canonical URL, aliases, post ID, first/last seen UTC, publication value/precision, strategy and immutable revisions |
| `cycles` | Map from opening event key to exact asset, source event links, latest source weight/status, and verified actual position links |
| `evaluations` | Append-only records of conclusions, evidence references and reasons |
| `proposals` | Map from stable intent key to proposal history and current planning/reconciliation status |
| `accountSnapshots` | Timestamped Bravos-only funding, cash, actual positions and order completeness; no unrelated owner holdings |
| `runs` | Run IDs, start/end UTC, base generation, independent discovery/reconciliation/evaluation statuses and report path |

Do not call one Boolean `processed` the state of an alert. Seeing, understanding,
deciding, proposing and observing a fill are separate facts.

## Identity and evidence

An article revision contains `revisionId`, `observedAtUtc`, `bodySha256`, normalized
trade facts, a short supporting excerpt for each material instruction, and the
source URL. Hash UTF-8 text with normalized line endings and collapsed whitespace;
preserve wording and numbers. Store the normalization version. Keep full article
copies only if necessary; focused evidence avoids accumulating unrelated content.

Assign an event key from article key plus a stable local instruction ID, such as
`bravos:post:487942:event:1`. Match an edited instruction by meaning and source
context; do not renumber old events when a paragraph is inserted. Ambiguous edits
require review. Revision ID must not turn an existing instruction into a new trade.

Opening cycle key equals its opening event key. A reduction or addition retains
that cycle key. Two genuinely distinct opening alerts on the same instrument
must never share a cycle merely because their ticker matches. A repost that
explicitly references the same instruction is an alias, not a new opening.

An evaluation has an ID, run ID, article/event/revision/cycle references,
policy version, source status, quote status, price result, sizing result,
readiness result, reason, and relevant account snapshot ID. Keep superseded
evaluations; identify the replacement explicitly. Never infer a permanent skip
from a planning evaluation such as `would_skip_above_entry`.

A proposal intent key is `cycleKey + eventKey + operation`, with operations such
as `open`, `add`, `reduce`, `close`, or `review_reference`. New quotes, reruns,
policy revisions and added cash do not make a new intent. Revised proposals are
versions of that intent; completed/partially completed intents cannot be reissued
without reconciling remaining quantities. If multiple source events are combined,
list all consumed event keys and check that none already belongs to another
unresolved or completed intent for that operation.

Proposal statuses are `draft`, `blocked`, `superseded`, `observed_partial`,
`observed_completed`, or `needs_reconciliation`. Only broker evidence can set an
observed status. Do not estimate fills from balance differences alone. Aggregate
holdings are insufficient to associate an unexplained manual trade with an alert.

## Claim, commit and recovery

1. Atomically create `run.lock` with exclusive-create semantics and a run UUID.
   Do not implement this as “check existence, then overwrite”. Keep the claim
   throughout this run; record task identity and start time for recovery.
2. If another claim exists, do not mutate the ledger or diagnostic snapshots.
   Report a run in progress or interrupted-run recovery needed. Do not steal a
   claim because it is old; establish that its owner has stopped before recovery.
3. Load/parse the ledger; validate its generation and referenced IDs. If absent,
   stage generation 1 with null activation and empty collections. If corrupt,
   stop normal processing and preserve it; do not replace it with empty state.
4. Stage the entire next ledger in a unique temporary file in the same directory.
   Parse it back, validate references and confirm the base generation has not
   changed. Maintain explicit source/broker errors in the candidate generation.
5. Preserve the prior valid ledger in `history/` under its generation/run ID.
   Publish the staged ledger through a same-volume atomic file replacement (or
   atomic rename for the initial file). Never edit the live JSON in place. If
   suitable filesystem primitives are unavailable, save a separate draft/report
   and state that memory was not committed.
6. Read back the published run ID/generation before reporting success. Write the
   run report; its absence after interruption does not undo a committed ledger.
   Remove only a claim whose UUID matches this run.

For recovery, first determine whether the current ledger already contains the
interrupted run. If it does, keep that generation and regenerate a missing report.
Otherwise preserve partial files as diagnostic evidence and re-read the sources;
do not pretend partial staging committed. Reconcile current broker state before
retrying any proposal that could have been executed outside this skill.

Diagnostic scripts currently overwrite their own snapshot files. Copy their
returned Bravos projection and timestamp into this run's staged account snapshot
while holding the claim. Those standalone files never replace ledger history.

## Worked checks for reviewing this procedure

These are paper scenarios, not executed broker tests:

| Situation | Required behavior |
| --- | --- |
| Same alert appears on two archive pages | One article/event; another observation only |
| Newly found article is dated yesterday | New by identity even after yesterday's successful run |
| Known post corrects its opening price | New revision; old price/evaluation retained; review, not a new opening |
| Same ticker opens after an earlier closed cycle | Distinct cycle if the source proves a distinct opening |
| Opening and full close arrive during downtime | Reconstruct closed cycle; no catch-up opening proposal |
| Last successful run was 45 days ago | Extend scan beyond the normal 30-day overlap |
| Page 2 fails after page 1 was read | Preserve partial observations; do not advance complete-discovery time |
| Quote says realtime but exchange is closed | Waiting quote; no permanent entry decision |
| User adds $200 | Record funding delta; no trade proposal from funding alone |
| Broker shows an unexplained position after a crash | Reconcile; do not issue another opening |
| Ledger committed but report write failed | Keep committed generation; regenerate report |
| Another invocation already holds the run claim | No competing ledger/snapshot writes |
| A never-seen alert is backdated beyond the scan floor | Not guaranteed detected; disclose bounded coverage |
