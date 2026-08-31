#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FIXTURE_DIR="$SCRIPT_DIR/fixtures"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "$TEST_TMP"' EXIT

java "$TOOL_DIR/CodeGraphCli.java" build --root "$FIXTURE_DIR" --output "$TEST_TMP/graph-one.json" > "$TEST_TMP/build-one.json"
java "$TOOL_DIR/CodeGraphCli.java" build --root "$FIXTURE_DIR" --output "$TEST_TMP/graph-two.json" > "$TEST_TMP/build-two.json"
java "$TOOL_DIR/CodeGraphCli.java" query --root "$FIXTURE_DIR" --symbol com.example.OrderController > "$TEST_TMP/query-symbol.json"
java "$TOOL_DIR/CodeGraphCli.java" query --root "$FIXTURE_DIR" --file order-service/src/main/java/com/example/OrderController.java > "$TEST_TMP/query-file.json"

if java "$TOOL_DIR/CodeGraphCli.java" query --root "$FIXTURE_DIR" --symbol OrderService > "$TEST_TMP/query-ambiguous.json"; then
    echo "ambiguous query unexpectedly succeeded" >&2
    exit 1
else
    test "$?" -eq 3
fi

node - "$TEST_TMP" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const graphOne = JSON.parse(fs.readFileSync(path.join(root, 'graph-one.json'), 'utf8'));
const graphTwo = JSON.parse(fs.readFileSync(path.join(root, 'graph-two.json'), 'utf8'));
const symbol = JSON.parse(fs.readFileSync(path.join(root, 'query-symbol.json'), 'utf8'));
const file = JSON.parse(fs.readFileSync(path.join(root, 'query-file.json'), 'utf8'));
const ambiguous = JSON.parse(fs.readFileSync(path.join(root, 'query-ambiguous.json'), 'utf8'));
if (graphOne.schemaVersion !== 'coding-agent-codegraph/v1') throw new Error('wrong graph schema');
if (graphOne.stats.discoveredFiles !== 4 || graphOne.stats.parsedFiles !== 4) throw new Error('fixture files not fully parsed');
if (!graphOne.nodes.some((node) => node.kind === 'endpoint' && node.attributes.httpMethod === 'GET')) throw new Error('GET endpoint missing');
if (!graphOne.edges.some((edge) => edge.kind === 'field_dependency')) throw new Error('field dependency missing');
if (!graphOne.edges.some((edge) => edge.kind === 'calls')) throw new Error('call edge missing');
const overloaded = graphOne.nodes.filter((node) => node.kind === 'method' && node.name === 'findOrder');
if (overloaded.length !== 2 || new Set(overloaded.map((node) => node.id)).size !== 2) throw new Error('overloaded method IDs collide');
if (graphOne.digest !== graphTwo.digest) throw new Error('graph digest is not deterministic');
if (symbol.status !== 'found' || !symbol.relatedFiles.includes('order-service/src/main/java/com/example/OrderController.java')) throw new Error('symbol query failed');
if (file.status !== 'found' || file.nodes.length === 0) throw new Error('file query failed');
if (ambiguous.status !== 'ambiguous' || ambiguous.candidates.length !== 2) throw new Error('ambiguity was not reported');
NODE

echo "java codegraph tests: pass"
