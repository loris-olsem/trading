"""Local review-state mechanics. No broker requests or trading decisions.

Python 3.11+, standard library only. See the skill's references/state.md.
The persistent run claim belongs to the supervising task, not this short-lived CLI.
"""
import argparse
import copy
from contextlib import contextmanager
from functools import wraps
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
from datetime import datetime, timezone
from uuid import UUID, uuid4


class StateError(Exception):
    pass


def serialized(method):
    """Serialize short file operations, even if a run ID is accidentally reused."""
    @wraps(method)
    def guarded(self, *args, **kwargs):
        with self.mutex():
            return method(self, *args, **kwargs)
    return guarded


def require(condition, code):
    if not condition:
        raise StateError(code)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def run_uuid(value):
    try:
        require(str(UUID(value)) == value, 'INVALID_RUN_ID')
    except (ValueError, TypeError, AttributeError):
        raise StateError('INVALID_RUN_ID') from None
    return value


def decode(data):
    def reject_constant(_):
        raise StateError('NONFINITE_NUMBER')

    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=unique_pairs, parse_constant=reject_constant)
    except (ValueError, UnicodeError):
        raise StateError('INVALID_JSON') from None


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')


def body_hash(text):
    # Caller supplies only the authored body. Removing chrome is source extraction,
    # not something a generic whitespace normalizer can determine.
    normalized = re.sub(r'\s+', ' ', text).strip()
    return hashlib.sha256(normalized.encode('utf-8')).hexdigest()


def timestamp(value):
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(parsed.utcoffset() is not None and parsed.utcoffset().total_seconds() == 0,
                'UTC_TIMESTAMP_REQUIRED')
        return parsed
    except (ValueError, TypeError, AttributeError):
        raise StateError('INVALID_TIMESTAMP') from None


def validate(ledger):
    require(isinstance(ledger, dict), 'LEDGER_NOT_OBJECT')
    require(ledger.get('schemaVersion') == 2, 'UNSUPPORTED_SCHEMA')
    require(type(ledger.get('generation')) is int and ledger['generation'] >= 1, 'INVALID_GENERATION')
    run_uuid(ledger.get('runId'))
    require(ledger.get('mode') == 'planning', 'INVALID_MODE')
    require(isinstance(ledger.get('policyVersion'), str) and ledger['policyVersion'], 'MISSING_POLICY')
    for key in ('committedAtUtc', 'activationAtUtc'):
        require(key in ledger, 'MISSING_TIMESTAMP_FIELD')
        if ledger[key] is not None:
            timestamp(ledger[key])
    for key in ('articles', 'events', 'cycles', 'proposals', 'discovery'):
        require(isinstance(ledger.get(key), dict), 'MISSING_MAP_' + key)
    for key in ('evaluations', 'accountSnapshots', 'runs', 'userActions'):
        require(isinstance(ledger.get(key), list), 'MISSING_LIST_' + key)
    for key in ('lastCompleteDiscoveryAtUtc', 'lastHistoricalRevisionAuditAtUtc'):
        require(key in ledger['discovery'], 'MISSING_CHECKPOINT')
        if ledger['discovery'][key] is not None:
            timestamp(ledger['discovery'][key])
    require(isinstance(ledger['discovery'].get('attempt'), dict), 'MISSING_ATTEMPT')

    revisions = set()
    for article_key, article in ledger['articles'].items():
        require(isinstance(article, dict) and isinstance(article.get('revisions'), list), 'INVALID_ARTICLE')
        for revision in article['revisions']:
            require(isinstance(revision, dict) and isinstance(revision.get('revisionId'), str), 'INVALID_REVISION')
            key = (article_key, revision['revisionId'])
            require(key not in revisions, 'DUPLICATE_REVISION')
            require(re.fullmatch('[0-9a-f]{64}', revision.get('bodySha256', '')) is not None, 'INVALID_BODY_HASH')
            revisions.add(key)
    for event in ledger['events'].values():
        require(isinstance(event, dict), 'INVALID_EVENT')
        require((event.get('articleKey'), event.get('revisionId')) in revisions, 'BROKEN_EVENT_SOURCE')
    for cycle_key, cycle in ledger['cycles'].items():
        require(isinstance(cycle, dict) and cycle_key in ledger['events'], 'BROKEN_CYCLE_OPENING')
        require(ledger['events'][cycle_key].get('kind') == 'open', 'CYCLE_NOT_OPENING')
        require(isinstance(cycle.get('eventKeys'), list) and cycle_key in cycle['eventKeys'], 'INVALID_CYCLE_EVENTS')
        require(all(key in ledger['events'] for key in cycle['eventKeys']), 'BROKEN_CYCLE_EVENT')
    seen_intents = set()
    for key, proposal in ledger['proposals'].items():
        require(isinstance(proposal, dict), 'INVALID_PROPOSAL')
        cycle, event, operation = (proposal.get(x) for x in ('cycleKey', 'eventKey', 'operation'))
        require(cycle in ledger['cycles'] and event in ledger['events'], 'BROKEN_PROPOSAL_REFERENCE')
        require(event in ledger['cycles'][cycle]['eventKeys'], 'PROPOSAL_EVENT_OUTSIDE_CYCLE')
        require(operation in ('open', 'add', 'reduce', 'close', 'set_stop', 'review_reference'), 'INVALID_OPERATION')
        require(key == f'{cycle}|{event}|{operation}', 'INVALID_INTENT_KEY')
        require(proposal.get('status') in ('draft', 'blocked', 'superseded', 'observed_partial',
                                         'observed_completed', 'needs_reconciliation'), 'INVALID_PROPOSAL_STATUS')
        consumed_keys = proposal.get('consumedEventKeys', [event])
        require(isinstance(consumed_keys, list) and event in consumed_keys, 'INVALID_CONSUMED_EVENTS')
        for consumed in consumed_keys:
            require(consumed in ledger['events'], 'BROKEN_CONSUMED_EVENT')
            require(consumed in ledger['cycles'][cycle]['eventKeys'], 'CONSUMED_EVENT_OUTSIDE_CYCLE')
            claim = (cycle, consumed, operation)
            require(claim not in seen_intents, 'DUPLICATE_INTENT')
            seen_intents.add(claim)
    for name in ('evaluations', 'accountSnapshots', 'runs', 'userActions'):
        ids = set()
        for record in ledger[name]:
            require(isinstance(record, dict) and isinstance(record.get('id'), str), 'INVALID_RECORD_' + name)
            require(record['id'] not in ids, 'DUPLICATE_ID_' + name)
            ids.add(record['id'])
            for field, collection in (('articleKey', 'articles'), ('eventKey', 'events'), ('cycleKey', 'cycles')):
                if record.get(field) is not None:
                    require(record[field] in ledger[collection], 'BROKEN_RECORD_REFERENCE')
    snapshot_ids = {x['id'] for x in ledger['accountSnapshots']}
    for record in ledger['evaluations'] + list(ledger['proposals'].values()):
        if record.get('accountSnapshotId') is not None:
            require(record['accountSnapshotId'] in snapshot_ids, 'BROKEN_SNAPSHOT_REFERENCE')

    def numbers(value, key=''):
        if isinstance(value, dict):
            for k, v in value.items():
                numbers(v, k)
        elif isinstance(value, list):
            for v in value:
                numbers(v, key)
        elif isinstance(value, float):
            require(math.isfinite(value), 'NONFINITE_NUMBER')
        if key in ('units', 'amount', 'price', 'stopLossRate', 'sourceWeight') and value is not None:
            require(type(value) in (int, float) and value >= 0, 'INVALID_NONNEGATIVE_NUMBER')
    numbers(ledger)
    return ledger


def validate_transition(previous, candidate):
    if previous is None:
        return
    for name in ('evaluations', 'accountSnapshots', 'runs', 'userActions'):
        require(candidate[name][:len(previous[name])] == previous[name], 'HISTORY_CHANGED_' + name)
    for key, article in previous['articles'].items():
        require(key in candidate['articles'], 'ARTICLE_REMOVED')
        old = article['revisions']
        require(candidate['articles'][key]['revisions'][:len(old)] == old, 'REVISION_HISTORY_CHANGED')
    for name in ('events', 'cycles', 'proposals'):
        require(previous[name].keys() <= candidate[name].keys(), 'IDENTITY_REMOVED_' + name)
    old = previous['discovery']['lastCompleteDiscoveryAtUtc']
    new = candidate['discovery']['lastCompleteDiscoveryAtUtc']
    if new != old:
        require(new is not None and (old is None or timestamp(new) >= timestamp(old)), 'CHECKPOINT_REGRESSED')
        require(candidate['discovery']['attempt'].get('status') == 'complete', 'INCOMPLETE_DISCOVERY_ADVANCE')


class Store:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.ledger_path = self.directory / 'ledger.json'
        self.lock_path = self.directory / 'run.lock'

    @contextmanager
    def mutex(self):
        # Stable inode/file: never unlink this mutex. OS releases it on process death.
        with (self.directory / '.mutex').open('a+b') as handle:
            if handle.tell() == 0:
                handle.write(b'0'); handle.flush()
            handle.seek(0)
            try:
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise StateError('STATE_OPERATION_BUSY') from None
            try:
                yield
            finally:
                handle.seek(0)
                if os.name == 'nt':
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def read(self):
        if not self.ledger_path.exists():
            return None, None
        raw = self.ledger_path.read_bytes()
        return validate(decode(raw)), hashlib.sha256(raw).hexdigest()

    @serialized
    def begin(self, run_id, owner, policy):
        run_uuid(run_id)
        require(bool(owner.strip()) and bool(policy.strip()), 'MISSING_OWNER_OR_POLICY')
        previous, digest = self.read()
        try:
            handle = self.lock_path.open('xb')
        except FileExistsError:
            raise StateError('RUN_ALREADY_CLAIMED') from None
        # A crash during claim creation deliberately leaves recovery evidence.
        with handle:
            base = previous['generation'] if previous else 0
            claim = dict(runId=run_id, owner=owner, startedAtUtc=utc_now(),
                         baseGeneration=base, baseSha256=digest)
            handle.write(encode(claim)); handle.flush(); os.fsync(handle.fileno())
        draft = copy.deepcopy(previous) if previous else dict(
            schemaVersion=2, mode='planning', activationAtUtc=None,
            discovery=dict(lastCompleteDiscoveryAtUtc=None,
                           lastHistoricalRevisionAuditAtUtc=None, attempt={}),
            articles={}, events={}, cycles={}, evaluations=[], proposals={},
            accountSnapshots=[], runs=[], userActions=[])
        draft.update(generation=base+1, runId=run_id, committedAtUtc=None, policyVersion=policy)
        draft['runs'].append(dict(id=run_id, startedAtUtc=claim['startedAtUtc'], baseGeneration=base,
                                  status='in_progress'))
        draft_path = self.directory / f'draft-{run_id}.json'
        with draft_path.open('xb') as handle:
            handle.write(encode(draft))
        return dict(runId=run_id, baseGeneration=base, draft=str(draft_path))

    def claim(self, run_id):
        run_uuid(run_id)
        require(self.lock_path.exists(), 'NO_RUN_CLAIM')
        claim = decode(self.lock_path.read_bytes())
        require(claim.get('runId') == run_id, 'CLAIM_OWNER_MISMATCH')
        return claim

    @serialized
    def commit(self, run_id, candidate):
        claim = self.claim(run_id)
        previous, digest = self.read()
        require(digest == claim['baseSha256'], 'BASE_CHANGED_OR_ALREADY_COMMITTED')
        candidate = copy.deepcopy(candidate)
        require(candidate.get('runId') == run_id, 'CANDIDATE_RUN_MISMATCH')
        require(candidate.get('generation') == claim['baseGeneration'] + 1, 'CANDIDATE_GENERATION_MISMATCH')
        candidate['committedAtUtc'] = utc_now()
        validate(candidate)
        validate_transition(previous, candidate)
        if previous is None and candidate['discovery']['lastCompleteDiscoveryAtUtc'] is not None:
            require(candidate['discovery']['attempt'].get('status') == 'complete', 'INCOMPLETE_DISCOVERY_ADVANCE')
        history = self.directory / 'history'
        history.mkdir(exist_ok=True)
        if previous:
            backup = history / f"{previous['generation']}-{previous['runId']}.json"
            raw = self.ledger_path.read_bytes()
            if backup.exists():
                require(backup.read_bytes() == raw, 'HISTORY_COLLISION')
            else:
                with backup.open('xb') as handle:
                    handle.write(raw); handle.flush(); os.fsync(handle.fileno())
        stage = self.directory / f'.stage-{uuid4()}.json'
        with stage.open('xb') as handle:
            handle.write(encode(candidate)); handle.flush(); os.fsync(handle.fileno())
        validate(decode(stage.read_bytes()))
        require(self.read()[1] == digest, 'BASE_CHANGED_BEFORE_REPLACE')
        os.replace(stage, self.ledger_path)
        result, _ = self.read()
        require(result['runId'] == run_id and result['generation'] == candidate['generation'], 'READBACK_MISMATCH')
        return dict(runId=run_id, generation=result['generation'], committedAtUtc=result['committedAtUtc'])

    @serialized
    def release(self, run_id):
        self.claim(run_id)
        self.lock_path.unlink()
        return dict(released=run_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', default=str(Path(__file__).resolve().parents[1] / 'state/bravos'))
    sub = parser.add_subparsers(dest='command', required=True)
    begin = sub.add_parser('begin')
    begin.add_argument('--run-id', required=True); begin.add_argument('--owner', required=True)
    begin.add_argument('--policy', required=True)
    commit = sub.add_parser('commit')
    commit.add_argument('--run-id', required=True); commit.add_argument('--candidate', required=True)
    release = sub.add_parser('release'); release.add_argument('--run-id', required=True)
    check = sub.add_parser('validate'); check.add_argument('path')
    fingerprint = sub.add_parser('hash'); fingerprint.add_argument('path')
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            validate(decode(Path(args.path).read_bytes())); result = {'valid': True}
        elif args.command == 'hash':
            result = {'normalizationVersion': 1, 'bodySha256': body_hash(Path(args.path).read_text(encoding='utf-8'))}
        else:
            store = Store(args.directory)
            if args.command == 'begin':
                result = store.begin(args.run_id, args.owner, args.policy)
            elif args.command == 'commit':
                result = store.commit(args.run_id, decode(Path(args.candidate).read_bytes()))
            else:
                result = store.release(args.run_id)
        print(json.dumps(result))
    except (StateError, OSError, ValueError, TypeError, KeyError) as error:
        # Payloads and arbitrary exception text must not leak from state validation.
        code = str(error) if isinstance(error, StateError) else 'STATE_IO_OR_STRUCTURE_ERROR'
        print(json.dumps({'ok': False, 'code': code}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
