#!/usr/bin/env bash
# A2A 对外暴露冒烟：
#   1) 免鉴权拉 agent-card（A2A 发现惯例 /.well-known/agent-card.json）
#   2) message/send（chat skill，同步）→ 拿到 agent 回复
#   3) message/send（deep-research skill，异步）→ 拿到 A2A Task → tasks/get 轮询到终态
#
# 前置：docker compose 栈已起（edge-gateway + interop-service + agent-service + LiteLLM）。
# 用法：BASE_URL=http://localhost:8080 API_KEY=dev-key-acme bash deploy/smoke-a2a.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-dev-key-acme}"
QUESTION="${QUESTION:-用一句话说明什么是向量检索}"
RESEARCH="${RESEARCH:-分析一下检索增强生成的优缺点}"

say() { printf '\n=== %s ===\n' "$1"; }
jqpy() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

# 1) 发现：agent-card 免鉴权（不带 X-Api-Key）
say "1) GET /.well-known/agent-card.json（免鉴权）"
CARD="$(curl -fsS "$BASE_URL/.well-known/agent-card.json")"
echo "$CARD" | jqpy "'agent: '+d['name']+' v'+d['version']+' | skills: '+','.join(s['id'] for s in d.get('skills',[]))"

# 2) message/send —— chat skill（默认，同步返回回复）
say "2) POST /interop/a2a message/send (chat)"
CHAT_RESP="$(curl -fsS -X POST "$BASE_URL/interop/a2a" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json,sys;print(json.dumps({'jsonrpc':'2.0','id':1,'method':'message/send','params':{'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}]}}}))" "$QUESTION")")"
echo "$CHAT_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
if 'error' in d: print('FAIL chat error:',d['error']); sys.exit(2)
parts=d['result'].get('parts',[])
text=''.join(p.get('text','') for p in parts if p.get('kind')=='text')
assert text.strip(), 'empty chat reply'
print('chat reply:', text[:120])
"

# 3) message/send —— deep-research skill（异步 → A2A Task），tasks/get 轮询
say "3) POST /interop/a2a message/send (deep-research) → tasks/get 轮询"
TASK_RESP="$(curl -fsS -X POST "$BASE_URL/interop/a2a" \
  -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d "$(python3 -c "import json,sys;print(json.dumps({'jsonrpc':'2.0','id':2,'method':'message/send','params':{'message':{'role':'user','parts':[{'kind':'text','text':sys.argv[1]}],'metadata':{'skill':'deep-research'}}}}))" "$RESEARCH")")"
TASK_ID="$(echo "$TASK_RESP" | jqpy "d['result']['id']")"
echo "task submitted: $TASK_ID"

for i in $(seq 1 30); do
  GET_RESP="$(curl -fsS -X POST "$BASE_URL/interop/a2a" \
    -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "$(python3 -c "import json,sys;print(json.dumps({'jsonrpc':'2.0','id':3,'method':'tasks/get','params':{'id':sys.argv[1]}}))" "$TASK_ID")")"
  STATE="$(echo "$GET_RESP" | jqpy "d['result']['status']['state']")"
  echo "  [$i] task state: $STATE"
  case "$STATE" in
    completed|failed|canceled) FINAL="$GET_RESP"; break ;;
  esac
  sleep 2
done

echo "${FINAL:-$GET_RESP}" | python3 -c "
import sys,json
d=json.load(sys.stdin); state=d['result']['status']['state']
print('final task state:', state)
assert state=='completed', 'task did not complete: '+state
"

say "A2A 冒烟通过 ✅"
