import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from bravos_state import Store, StateError, body_hash, decode, validate


class StateTests(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix='bravos-state-test-')
        self.addCleanup(self.workspace.cleanup)
        self.store = Store(self.workspace.name)

    def draft(self):
        run_id = str(uuid4())
        result = self.store.begin(run_id, 'isolated-test', 'test-policy')
        draft = decode(Path(result['draft']).read_bytes())
        draft['runs'][-1]['status'] = 'complete'
        return run_id, draft

    def test_initial_claim_does_not_publish_or_activate(self):
        _, draft = self.draft()
        self.assertFalse(self.store.ledger_path.exists())
        self.assertIsNone(draft['activationAtUtc'])
        self.assertIsNone(draft['discovery']['lastCompleteDiscoveryAtUtc'])
        validate(draft)

    def test_corrupt_ledger_is_preserved_without_an_empty_replacement(self):
        original = b'{unfinished'
        self.store.ledger_path.write_bytes(original)
        with self.assertRaisesRegex(StateError, 'INVALID_JSON'):
            self.store.begin(str(uuid4()), 'test', 'test')
        self.assertEqual(self.store.ledger_path.read_bytes(), original)
        self.assertFalse(self.store.lock_path.exists())

    def test_other_process_cannot_claim_or_release_current_run(self):
        run_id, _ = self.draft()
        helper = Path(__file__).resolve().parents[1] / 'scripts/bravos_state.py'
        result = subprocess.run([sys.executable, str(helper), '--directory', self.workspace.name,
                                 'begin', '--run-id', str(uuid4()), '--owner', 'other', '--policy', 'test'],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn('RUN_ALREADY_CLAIMED', result.stderr)
        with self.assertRaisesRegex(StateError, 'CLAIM_OWNER_MISMATCH'):
            self.store.release(str(uuid4()))
        self.assertEqual(self.store.claim(run_id)['runId'], run_id)

    def test_os_mutex_blocks_parallel_file_operations(self):
        with self.store.mutex():
            with self.assertRaisesRegex(StateError, 'STATE_OPERATION_BUSY'):
                self.store.begin(str(uuid4()), 'test', 'test')

    def test_commit_readback_and_prior_generation(self):
        run_id, first = self.draft()
        self.store.commit(run_id, first)
        original = self.store.ledger_path.read_bytes()
        self.store.release(run_id)
        next_id, second = self.draft()
        self.store.commit(next_id, second)
        self.assertEqual(self.store.read()[0]['generation'], 2)
        self.assertEqual((self.store.directory / 'history' / f'1-{run_id}.json').read_bytes(), original)

    def test_repeated_commit_does_not_publish_again(self):
        run_id, draft = self.draft()
        self.store.commit(run_id, draft)
        with self.assertRaisesRegex(StateError, 'ALREADY_COMMITTED'):
            self.store.commit(run_id, draft)
        self.assertEqual(self.store.read()[0]['generation'], 1)

    def test_failed_replace_preserves_live_generation_and_allows_retry(self):
        run_id, draft = self.draft()
        self.store.commit(run_id, draft)
        self.store.release(run_id)
        original = self.store.ledger_path.read_bytes()
        next_id, next_draft = self.draft()
        with patch('bravos_state.os.replace', side_effect=OSError('simulated interruption')):
            with self.assertRaises(OSError):
                self.store.commit(next_id, next_draft)
        self.assertEqual(self.store.ledger_path.read_bytes(), original)
        self.store.commit(next_id, next_draft)
        self.assertEqual(self.store.read()[0]['generation'], 2)

    def test_external_base_change_is_not_overwritten(self):
        run_id, draft = self.draft()
        self.store.commit(run_id, draft)
        self.store.release(run_id)
        next_id, next_draft = self.draft()
        self.store.ledger_path.write_bytes(self.store.ledger_path.read_bytes() + b' ')
        with self.assertRaisesRegex(StateError, 'BASE_CHANGED'):
            self.store.commit(next_id, next_draft)

    def test_partial_discovery_cannot_advance_checkpoint(self):
        run_id, draft = self.draft()
        draft['discovery'].update(lastCompleteDiscoveryAtUtc='2026-09-19T00:00:00Z',
                                  attempt={'status': 'partial'})
        with self.assertRaisesRegex(StateError, 'INCOMPLETE_DISCOVERY_ADVANCE'):
            self.store.commit(run_id, draft)
        self.assertFalse(self.store.ledger_path.exists())

    def test_committed_evaluation_cannot_be_erased(self):
        run_id, draft = self.draft()
        draft['evaluations'] = [{'id': 'evaluation-1', 'result': 'watching_price'}]
        self.store.commit(run_id, draft)
        self.store.release(run_id)
        next_id, next_draft = self.draft()
        next_draft['evaluations'] = []
        with self.assertRaisesRegex(StateError, 'HISTORY_CHANGED'):
            self.store.commit(next_id, next_draft)

    def test_references_and_duplicate_consumed_intents(self):
        _, draft = self.draft()
        draft['articles']['article-a'] = {'revisions': [{'revisionId': 'r1', 'bodySha256': 'a'*64}]}
        draft['events']['opening'] = {'articleKey': 'article-a', 'revisionId': 'r1', 'kind': 'open'}
        draft['events']['addition'] = {'articleKey': 'article-a', 'revisionId': 'r1', 'kind': 'add'}
        draft['cycles']['opening'] = {'eventKeys': ['opening', 'addition']}
        draft['proposals']['opening|opening|open'] = dict(cycleKey='opening', eventKey='opening',
                                                        operation='open', status='draft')
        validate(draft)
        broken = copy.deepcopy(draft)
        broken['events']['opening']['revisionId'] = 'missing'
        with self.assertRaisesRegex(StateError, 'BROKEN_EVENT_SOURCE'):
            validate(broken)
        draft['proposals']['opening|addition|open'] = dict(cycleKey='opening', eventKey='addition',
            operation='open', status='draft', consumedEventKeys=['opening', 'addition'])
        with self.assertRaisesRegex(StateError, 'DUPLICATE_INTENT'):
            validate(draft)

    def test_ambiguous_json_and_invalid_quantities_rejected(self):
        with self.assertRaisesRegex(StateError, 'DUPLICATE_JSON_KEY'):
            decode('{"generation":1,"generation":2}')
        with self.assertRaisesRegex(StateError, 'NONFINITE'):
            decode('{"units":NaN}')
        _, draft = self.draft()
        draft['accountSnapshots'] = [{'id': 'snapshot', 'units': -1}]
        with self.assertRaisesRegex(StateError, 'INVALID_NONNEGATIVE'):
            validate(draft)

    def test_hash_ignores_whitespace_but_detects_changed_stop(self):
        self.assertEqual(body_hash('Stop\r\n  100'), body_hash('Stop 100'))
        self.assertNotEqual(body_hash('Stop 100'), body_hash('Stop 101'))


if __name__ == '__main__':
    unittest.main()
