# State contract — version 2

Use this contract when `$bravos-review` reads or writes memory. All paths below
are relative to the project root. Keep credentials out of every record.

## Files and ownership

- `state/bravos/ledger.json`: authoritative structured memory; one JSON document.
- `state/bravos/history/<generation>-<runId>.json`: immutable prior generations.
- `state/bravos/runs/<runId>.md`: readable report, including incomplete attempts.
- `state/bravos/run.lock`: exclusive claim identifying the writing run.

The minimal ledger, history JSON and run Markdown are allowlisted for local Git.
Claims, drafts, `.mutex` and private broker snapshots remain ignored. Exclude
owner identity and broad account dumps from tracked evidence. Use local aliases
for broker IDs with an ignored private mapping, reconciled before use. Local
Git is not an off-device backup. Installation creates no ledger or baseline.

## Deterministic helper

Requires Python 3.11+ standard library. From the project root, use a fresh run
UUID and the supervising task's identity; keep the same UUID for that run:

```text
python scripts/bravos_state.py begin --run-id <uuid> --owner <task> --policy 2026-09-19.3
python scripts/bravos_state.py validate state/bravos/draft-<uuid>.json
python scripts/bravos_state.py commit --run-id <uuid> --candidate state/bravos/draft-<uuid>.json
python scripts/bravos_state.py release --run-id <uuid>
```

`begin` claims exclusively and writes an ignored draft, not a published ledger.
Edit the draft and set the new run's outcome before commit. `commit` sets the UTC
timestamp, validates structure/history, checks the base hash, preserves the prior
generation, atomically replaces the ledger and reads it back. Release after clean
commit/abort. A failed commit leaves the claim for inspection. The supervising
task owns the persistent claim; the short-lived CLI process does not.

`python scripts/bravos_state.py hash <authored-body-file>` normalizes whitespace
and hashes UTF-8 text. The caller must exclude page chrome/comments first.

Never auto-steal a claim. Confirm its task stopped, inspect the committed run ID,
then release that exact claim. An already committed run needs no second commit;
regenerate a missing report instead. Preserve a malformed interrupted claim as
ignored recovery evidence before manual repair. Age alone is not stopped-task
evidence. The stable `.mutex` file must never be unlinked: its OS lock serializes
short operations, including accidental reuse of a run UUID, and releases on exit.

## Ledger contents

Store the following named members. Empty collections are allowed; unknown
values are JSON null, never an invented zero, empty success or timestamp.

| Member | Required contents |
| --- | --- |
| `schemaVersion` | Integer 2; reject unsupported versions; no live v1 ledger existed at this change |
| `generation` | Integer incremented once per committed run |
| `runId`, `committedAtUtc` | Identity/time of the writing run |
| `mode` | `planning` |
| `activationAtUtc` | null until separately established initial catch-up/activation |
| `policyVersion` | Version/hash of the actual decision rules used; do not silently relabel old evaluations |
| `discovery` | `lastCompleteDiscoveryAtUtc`, `lastHistoricalRevisionAuditAtUtc`, and `attempt` with floor, visited URLs, boundary evidence, status and errors |
| `articles` | Map from stable article key to canonical URL, aliases, post ID, first/last seen UTC, publication value/precision, strategy and immutable revisions |
| `events` | Map from stable event key to `articleKey`, current `revisionId`, `kind` and instruction facts; old facts remain in revisions |
| `cycles` | Map from opening event key to exact asset, source event links, latest source weight/status, latest published stop price/currency and its source revision, and verified actual position links |
| `evaluations` | Append-only records of conclusions, evidence references and reasons |
| `proposals` | Map from stable intent key to proposal history and current planning/reconciliation status |
| `accountSnapshots` | Timestamped Bravos-only funding, cash, actual positions including stop price/enabled/trailing status and order completeness; no unrelated owner holdings |
| `runs` | Run IDs, start/end UTC, base generation, independent discovery/reconciliation/evaluation statuses and report path |
| `userActions` | Append-only user early-exit requests and later outcome records with `id`, `cycleKey`, requested operation/quantity, status and prior-action link |

Every record in `evaluations`, `accountSnapshots`, `runs` and `userActions` has
a unique string `id`. Each revision has `revisionId` unique within its article
and a lowercase 64-character `bodySha256`. A cycle's `eventKeys` includes its
opening event. A proposal has `cycleKey`, `eventKey`, `operation`, `status` and
optional `consumedEventKeys` for combined events. `accountSnapshotId`, where
present, references an existing snapshot. Validation checks these relationships,
not the financial truth of extracted facts or broker execution.

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
evaluations; identify replacements explicitly. `watching_price` remains eligible
while Bravos holds. Our confirmed stop/full early exit is terminal for that cycle.
A session's expired attempt is not an expired opening. Do not import the old
indicative `would_skip_above_entry` as a permanent rejection under current policy.

A proposal intent key is `cycleKey|eventKey|operation`, with operations such
as `open`, `add`, `reduce`, `close`, `set_stop`, or `review_reference`. New quotes, reruns,
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

Diagnostic scripts overwrite their ignored snapshot files. Copy only a minimal
Bravos projection and timestamp into the staged snapshot while holding the claim,
omitting owner identity and using private mappings for broker IDs. Standalone
diagnostics never replace ledger history. State tests do not establish end-to-end
source discovery, trade interpretation or recovery of actual broker execution.

## Worked checks for reviewing this procedure

These are paper scenarios, not executed broker tests:

| Situation | Required behavior |
| --- | --- |
| Same alert appears on two archive pages | One article/event; another observation only |
| Newly found article is dated yesterday | New by identity even after yesterday's successful run |
| Known post corrects its opening price | New revision; old price/evaluation retained; review, not a new opening |
| Same ticker opens after an earlier closed cycle | Distinct cycle if the source proves a distinct opening |
| Opening and full close arrive during downtime | Reconstruct closed cycle; no catch-up opening proposal |
| Last successful scan was this morning | Resume from that checkpoint's calendar date; deduplicate previously seen IDs |
| Last successful run was 45 days ago | Cover the full interval since that checkpoint; no 30-day cap |
| No complete-discovery checkpoint exists | Report initialization needed; never default to a routine 30-day scan |
| Page 2 fails after page 1 was read | Preserve partial observations; do not advance complete-discovery time |
| Quote says realtime but exchange is closed | Waiting quote; no permanent entry decision |
| User adds $200 | Record funding delta; no trade proposal from funding alone |
| Missed opening drops within entry +2% while Bravos holds | Reassess the same intent with current source weight/stop and broker checks |
| Bravos adds before we entered | Keep original opening +2% ceiling, not the addition price |
| User requests early exit | Record request; only broker evidence establishes completion |
| Bravos reduces CF and raises its stop in the same article | Record both instructions; compare actual stop with the new published price |
| New position proposal has no verified published stop | Block stop readiness; never invent or omit a protective setting silently |
| Broker stop differs from the latest Bravos stop | Maintain a stop-update proposal; require broker read-back before recording it as applied |
| Broker reports the position exited by its stop | Reconcile the exit; do not automatically reopen from the old opening alert |
| Broker shows an unexplained position after a crash | Reconcile; do not issue another opening |
| Ledger committed but report write failed | Keep committed generation; regenerate report |
| Another invocation already holds the run claim | No competing ledger/snapshot writes |
| A never-seen alert is backdated beyond the scan floor | Not guaranteed detected; disclose bounded coverage |
