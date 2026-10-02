#!/usr/bin/env python3
"""隔离临时Git库验证pin门禁: 缺源/版本、误摘要、工作树漂移与副本漂移."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('verify-agent-contracts.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PinGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / 'producer'
        self.vendor = Path(self.temp.name) / 'vendor'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.name', 'Contract Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        files = {}
        for name in gate.CONSUMED:
            path = self.repo / 'contracts' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}\n')
            files[name] = gate.digest(path.read_bytes())
        (self.repo / 'contracts/manifest.json').write_text(json.dumps({'schema_version': '1', 'files': files}))
        self.git('add', 'contracts')
        self.git('commit', '-qm', 'immutable producer fixture')
        self.revision = self.git('rev-parse', 'HEAD').strip()
        gate.verify(self.repo, self.vendor, write=True, revision=self.revision)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def test_missing_upstream_and_commit_fail_closed(self):
        with self.assertRaises(ValueError):
            gate.verify(self.repo / 'missing', self.vendor)
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor, write=True, revision='0' * 40)
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor, write=True, revision='main')

    def test_pinned_git_blob_ignores_uncommitted_or_new_head(self):
        target = self.repo / 'contracts' / gate.CONSUMED[0]
        target.write_text('{"changed":true}')
        gate.verify(self.repo, self.vendor)
        self.git('add', 'contracts'); self.git('commit', '-qm', 'unreviewed latest')
        self.assertEqual(gate.verify(self.repo, self.vendor), self.revision)
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor, write=True, revision=self.git('rev-parse', 'HEAD').strip())

    def test_lock_and_vendor_drift_fail(self):
        path = self.vendor / gate.CONSUMED[0]
        path.write_bytes(path.read_bytes() + b'\n')
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor)
        gate.verify(self.repo, self.vendor, write=True, revision=self.revision)
        path = self.vendor / 'manifest.json'
        document = json.loads(path.read_text()); document['upstream']['manifest_digest'] = 'sha256:' + '0' * 64
        path.write_text(json.dumps(document))
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor)

    def test_missing_committed_schema_fails_before_copy(self):
        self.git('rm', '-q', 'contracts/' + gate.CONSUMED[0])
        self.git('commit', '-qm', 'missing producer schema')
        before = (self.vendor / 'manifest.json').read_bytes()
        with self.assertRaises(ValueError):
            gate.verify(self.repo, self.vendor, write=True, revision=self.git('rev-parse', 'HEAD').strip())
        self.assertEqual((self.vendor / 'manifest.json').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
