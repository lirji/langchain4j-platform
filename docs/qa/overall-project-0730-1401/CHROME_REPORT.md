# Chrome 真实整栈验证报告

## 结论

执行日期：2026-07-30（Asia/Taipei）

通过 Casdoor OIDC 的真实 `alice / acme / Bearer` 会话，从
`http://127.0.0.1:8093` 覆盖了目录中的全部 9 个模块，并经 edge 调用真实 Java、
AgentScope、LiteLLM、百炼、Qdrant、Elasticsearch、MySQL、Redis、Flowable 和渠道发现链路。

结果为：**本地整体验收通过；生产切流仍为 NO-GO**。最初发现的 2 个 P1、2 个 P2
均已修复并复验：

1. P1：Knowledge split 的 `ingest-api` / `ingest-worker` 因 AWS S3 SDK 运行时缺类而无法启动。
2. P1：Eval 被前端目录标成 12 项全部就绪，但标准 Compose 没有启动 `eval-service`，真实请求无法连接。
3. P2：视觉 provider 的 400 输入错误被 `vision-service` 翻译成 HTTP 500。
4. P2：`rag.query` 深链仍标成“就绪·降级”，与页面实时发现到的百炼
   `text-embedding-v4` + Qdrant + ES/GraphRAG/RRF 形态不一致。

## 浏览器环境

| 项 | 结果 |
|---|---|
| 浏览器 | 用户已登录的 Chrome |
| 身份 | `alice` / `acme` / Bearer |
| 入口 | `http://127.0.0.1:8093` |
| 目录 | v1，9 模块，82 能力 |
| 状态摘要 | 63 就绪、0 就绪·降级、10 需授权、7 未启用、2 已锁定 |
| 付费模型 | 用户已明确授权；实际调用文本、embedding、rerank 和视觉模型 |

## 用例结果

| 模块 | 场景 | 结果 | 证据 |
|---|---|---|---|
| Chat | 同步问答 | PASS | `2+2等于4。`；4672 ms；trace `c58b7380…` |
| Chat | SSE 流式问答 | PASS | 收到连续文本并正常结束；4138 ms；trace `73a512fa…` |
| RAG | 退款政策检索 | PASS | HTTP 200，5 个命中；3233 ms；trace `57395291…` |
| RAG | JSON 文档入库 | PASS | HTTP 200；docId `d407726e83481648`；tenant `acme`；1 segment；trace `06d0459b…` |
| RAG | 新文档回查 | PASS | 校验码 `QA-S3-20260730-RED` 命中 1 条；HTTP 200；2785 ms；trace `e21a4620…` |
| AgentScope | 同步 ReAct | PASS | `17×23=391`，`DONE`，tenant `acme`；1986 ms；trace `b21a589f…` |
| AgentScope | 自动规划 DAG | PASS | 自动生成 3 个任务，结果 156/210、差值 54，`acceptedByThreshold=true`；5594 ms；trace `45d4859e…` |
| Async Task | 创建租户任务 | PASS | HTTP 202；`qa-browser-20260730-1419`；状态 `PENDING`；trace `5a3a75a4…` |
| Analytics | Schema 浏览 | PASS | 读取 `orders`、`customers`、`refunds` |
| Analytics | NL2SQL | PASS | 生成只读 SQL，自动带 `tenant_id = 'acme'` 和 `LIMIT 1000`，执行成功 |
| Workflow | 发起退款 | PASS | HTTP 200；实例 `27501`；`COMPLETED`；3078 ms；trace `d027fca2…` |
| Multimodal | Base64 图像描述 | PASS | 32×32 红色 PNG；HTTP 200；`图像的主要颜色是红色。`；836 ms；trace `c4ba9dda…` |
| Interop | MCP 发现与调用 | PASS | 发现 5 个 AgentScope 工具；`platform.ping` 返回 `pong=ok` |
| Interop | A2A Agent Card | PASS | HTTP 200；能力和 endpoints 完整；28 ms；trace `4d19fdb9…` |
| Eval | 默认离线状态 | PASS | 6 个 Interop 能力就绪、6 个 Eval 能力未启用；运行按钮禁用并显示离线执行说明 |
| Channel | 渠道发现 | PASS | 发现 `feishu`、`voice`、`webhook` |
| Channel | 外部出站安全闸 | PASS | `/channel/messages` 保持“已锁定”，未触发真实外部投递 |

## S3-compatible Knowledge split

按批准方案真实启动：

```bash
docker compose \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.knowledge-split.yml \
  up -d minio minio-init knowledge-query knowledge-ingest-api knowledge-ingest-worker
```

结果：

- MinIO 正常启动，`minio-init` 成功创建 bucket。
- `knowledge-query` 在补齐与当前主栈一致的 RAG provider 环境后可启动，health 为 200。
- 默认 `KNOWLEDGE_INGEST_API_HOST_PORT=18085` 与 AgentScope 宿主端口冲突；改为 18095 后继续验证。
- `knowledge-ingest-api` 与 `knowledge-ingest-worker` 最终均启动失败，根因一致：
  `NoClassDefFoundError: org/apache/hc/client5/http/ssl/TlsSocketStrategy`。
- 因 ingest 两角色不可用，未能完成“S3 原文写入 → durable job → worker 读取 →
  多 sink 提交”的真实闭环。普通 `/rag/documents` 成功只证明 combined façade 链路，
  不能替代 S3 split 验收。

## 额外观察

- 首次用 1×1 PNG 调视觉模型时，上游明确返回“宽高必须大于 10”的 400 语义，
  `vision-service` 最终对外返回 HTTP 500。换用 32×32 PNG 后正常返回 200。
- Chrome 扩展未开启文件 URL 权限，multipart 文件选择被浏览器阻止；因此本轮浏览器内
  Voice ASR 文件上传未重复执行。7 月 29 日已有经 edge 的 Voice/图片向量真实 200 证据，
  但不把它冒充为本轮 Chrome 上传结果。
- 工作流危险删除、任务取消、知识删除和真实渠道出站均未执行；安全锁定状态已核验。
- 创建的测试数据为本地 QA 数据：工作流实例 `27501`、异步任务
  `qa-browser-20260730-1419`、RAG 文档 `d407726e83481648`。

## Go / No-Go

本地修复验收为 **GO**；生产切流仍为 **NO-GO**，剩余门禁为：

1. 目标环境 IAM、bucket policy 与生命周期验证；
2. 跨 sink 故障注入、并发、soak 和容量门禁；
3. AgentScope shadow/canary、任务排空与回滚演练；
4. 满足上述门禁后再考虑切换生产 `AGENT_URI` 或 Knowledge split 流量。

## 修复后状态

2026-07-30 已完成上述 1–3 的代码、自动化和真实容器复验；RAG 静态状态也已修正。
Knowledge split 已实际完成 `S3 source -> durable job -> worker 全 sink -> query` READY
闭环，Vision 1×1 PNG 已稳定返回 400。

最终 Chrome 页面复验已完成：

- Interop & Eval 模块显示 6 个在线能力就绪、6 个 Eval 能力未启用；运行按钮禁用，
  页面明确提示通过 evaluation profile 的独立 harness 或 CLI/CI Job 执行。
- RAG 深链显示“就绪”，Workspace 展示 OpenAI `text-embedding-v4`、Qdrant、
  ES/关键词/GraphRAG/多模态与 RRF 实时形态。
- 通过 UI 查询“退款政策”成功返回 5 条租户/共享库结果。
