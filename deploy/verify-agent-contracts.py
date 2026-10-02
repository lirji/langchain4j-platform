#!/usr/bin/env python3
"""校验一个不可变producer Git提交, 不读取其工作树, 缺版本/源/文件必须失败."""

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / 'platform-protocol/src/main/resources/contracts/agentscope'
CONSUMED = [
    'boundaries/analytics-sql-plan.schema.json',
    'boundaries/conversation-generation.schema.json',
    'boundaries/conversation-stream-event.schema.json',
    'boundaries/workflow-ai-draft.schema.json',
    'boundaries/budget-reservation-request.schema.json',
    'boundaries/budget-reservation-reply.schema.json',
    'boundaries/budget-settlement-request.schema.json',
    'boundaries/refund-receipt-request.schema.json',
    'boundaries/readonly-task-claim-request.schema.json',
    'boundaries/readonly-task-claim-reply.schema.json',
    'boundaries/async-task-worker-token-claims.schema.json',
    'capabilities/agent-capabilities.v1.json',
    *['legacy/' + name + '.schema.json' for name in (
        'agent-async-task', 'agent-dag-run-reply', 'agent-dag-run-request',
        'agent-dag-task', 'agent-run-reply', 'agent-run-request', 'agent-step',
        'chain-run-reply', 'chain-run-request', 'reflexion-reply',
        'reflexion-request', 'vote-reply', 'vote-request')],
]


def digest(data):
    return 'sha256:' + hashlib.sha256(data).hexdigest()


def git_blob(repo, revision, path):
    result = subprocess.run(['git', '-C', str(repo), 'show', revision + ':' + path], capture_output=True)
    if result.returncode:
        raise ValueError('pinned producer commit/file unavailable: ' + revision + ':' + path)
    return result.stdout


def verify(repo, vendor=VENDOR, write=False, revision=None):
    lock_path = vendor / 'manifest.json'
    lock = {} if write else json.loads(lock_path.read_text())
    if write and not revision:
        raise ValueError('--write requires an explicit full --revision')
    revision = revision if write else lock['upstream']['revision']
    if not isinstance(revision, str) or not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('producer revision must be immutable full Git SHA')
    if not repo.is_dir():
        raise ValueError('producer repository is required; no skipped-success mode')
    raw_manifest = git_blob(repo, revision, 'contracts/manifest.json')
    producer = json.loads(raw_manifest)
    if producer['schema_version'] != '1':
        raise ValueError('unsupported producer manifest version')
    for name, pinned in producer['files'].items():
        # manifest是跨仓输入, 限制路径避免写到vendor之外.
        if Path(name).is_absolute() or '..' in Path(name).parts or not name.endswith('.json'):
            raise ValueError('unsafe contract path')
        if digest(git_blob(repo, revision, 'contracts/' + name)) != pinned:
            raise ValueError('producer manifest digest mismatch: ' + name)
    blobs = {name: git_blob(repo, revision, 'contracts/' + name) for name in CONSUMED}
    expected = {
        'upstream': {
            'repository': 'agentscope-platform',
            'git_repository': 'https://github.com/lirji/platform-agentscope.git',
            'manifest': 'contracts/manifest.json', 'schema_version': '1',
            'revision': revision, 'manifest_digest': digest(raw_manifest),
        },
        'files': {name: producer['files'][name] for name in sorted(CONSUMED)},
    }
    if write:
        # 所有blob/digest验证通过后才回写, 不会拿未提交schema冒充指定版本.
        for name, data in blobs.items():
            path = vendor / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        lock_path.write_text(json.dumps(expected, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
    else:
        if lock != expected:
            raise ValueError('vendored lock does not match immutable producer bundle')
        for name, data in blobs.items():
            if (vendor / name).read_bytes() != data:
                raise ValueError('vendored contract differs from pinned producer: ' + name)
        on_disk = {p.relative_to(vendor).as_posix() for p in vendor.rglob('*.json') if p != lock_path}
        if on_disk != set(CONSUMED):
            raise ValueError('vendored file inventory mismatch')
    return revision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--revision')
    args = parser.parse_args()
    try:
        revision = verify(args.upstream, write=args.write, revision=args.revision)
    except (ValueError, OSError, KeyError, TypeError) as failure:
        parser.exit(1, 'contract gate failed: ' + str(failure) + '\n')
    print(f'PASS: {len(CONSUMED)} contracts at producer {revision}')


if __name__ == '__main__':
    main()
