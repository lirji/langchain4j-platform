#!/usr/bin/env bash
# 导入 deploy/sample-docs/ 下的示例知识库,走 edge-gateway 上传。
# 服务端会自动:抽取正文 → chunk 切片 → embedding → 写入向量库/登记表/(可选)图谱。
# 上传后打印每篇的 segmentCount(= chunk 数),再跑几条检索验证跨文档命中。
#
# 用法：
#   bash deploy/seed-kb.sh            # 导入到租户 acme + 检索验证
#   bash deploy/seed-kb.sh --purge    # 先删除这些同名文档再重新导入(干净演示)
#   bash deploy/seed-kb.sh --public   # 导入到公共/共享库(全租户可读),需 public-ingest 的 api-key
#   BASE_URL=http://127.0.0.1:8080 bash deploy/seed-kb.sh
#
# 注意：--public 灌入后，各租户要能查到还需 knowledge-service 开启 RAG_PUBLIC_ENABLED=true。
# 依赖：curl + python3。说明见 docs/对话与检索/rag-api-demo.md、docs/平台工程/rbac-and-public-kb.md。
set -euo pipefail

PUBLIC=0
[[ "${1:-}" == "--public" ]] && PUBLIC=1
# 公共库写入需 public-ingest scope；dev-key-acme 已具备（dev-key-acme-ingest 仅 ingest，不能写公共库）。
DEFAULT_KEY="dev-key-acme-ingest"
[[ "$PUBLIC" == "1" ]] && DEFAULT_KEY="dev-key-acme"

BASE_URL="${BASE_URL:-http://127.0.0.1:18080}"
API_KEY="${API_KEY:-$DEFAULT_KEY}"
DOC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/sample-docs" && pwd)"
PURGE=0
[[ "${1:-}" == "--purge" ]] && PURGE=1

hr() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

if [[ "$PURGE" == "1" ]]; then
  hr "先清理同名文档(--purge)"
  listing="$(curl -fsS "$BASE_URL/rag/documents" -H "X-Api-Key: $API_KEY")"
  LISTING="$listing" DOC_DIR="$DOC_DIR" BASE_URL="$BASE_URL" API_KEY="$API_KEY" python3 - <<'PY'
import glob, json, os, subprocess
docs = json.loads(os.environ["LISTING"])
titles = {os.path.basename(p) for p in glob.glob(os.path.join(os.environ["DOC_DIR"], "*.md"))}
by_name = {d["displayName"]: d["docId"] for d in docs}
for t in sorted(titles):
    did = by_name.get(t)
    if did:
        subprocess.run(["curl", "-fsS", "-X", "DELETE",
                        f"{os.environ['BASE_URL']}/rag/documents/{did}",
                        "-H", f"X-Api-Key: {os.environ['API_KEY']}"],
                       stdout=subprocess.DEVNULL)
        print(f"  - 删除旧文档 {t} ({did})")
print("  (清理完成)")
PY
fi

VIS_ARGS=()
[[ "$PUBLIC" == "1" ]] && VIS_ARGS=(-F "visibility=public")
hr "导入示例知识库(multipart 上传，服务端自动 chunk + embed)$([[ "$PUBLIC" == "1" ]] && echo ' [公共/共享库]')"
for f in "$DOC_DIR"/*.md; do
  resp="$(curl -fsS -X POST "$BASE_URL/rag/documents" \
    -H "X-Api-Key: $API_KEY" \
    -F "file=@$f;type=text/markdown" \
    -F "category=客服" \
    "${VIS_ARGS[@]}")"
  RESP="$resp" python3 - <<'PY'
import json, os
d = json.loads(os.environ["RESP"])
print(f"  ✓ {d['displayName']:<24} docId={d['docId']}  chunk数={d['segmentCount']}  ({d['sizeBytes']}B, v{d['version']})")
PY
done

hr "检索验证(每条 query 跨文档召回 top3,看命中的 文档#chunk)"
QUERIES=(
  "退款到账一般要几天"
  "顺丰能不能隔天送到"
  "积分怎么抵现金"
  "支持哪些支付方式，能开专票吗"
  "手机坏了保修多久"
)
for q in "${QUERIES[@]}"; do
  body="$(printf '{"query":"%s","topK":3}' "$q")"
  resp="$(curl -fsS -X POST "$BASE_URL/rag/query" \
    -H "X-Api-Key: $API_KEY" -H "Content-Type: application/json" \
    --data "$body")"
  Q="$q" RESP="$resp" python3 - <<'PY'
import json, os
r = json.loads(os.environ["RESP"])
hits = r.get("hits") or []
print(f"\n· 「{os.environ['Q']}」")
if not hits:
    print("    (无命中 —— 若刚重启且关键词镜像清空，向量仍应命中；见 docs 排障)")
for h in hits[:3]:
    text = (h.get("text") or "").replace("\n", " ")
    print(f"    [{(h.get('score') or 0):.2f} {h.get('source')}] {h.get('displayName')}#{h.get('index')}: {text[:48]}…")
PY
done

hr "完成 ✅"
echo "知识库已就绪 网关=$BASE_URL 租户=acme。用 Postman 或 curl 调 /rag/query、/chat、/agent/run 均可命中。"
