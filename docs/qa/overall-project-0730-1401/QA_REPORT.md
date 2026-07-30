# 全项目集中测试报告

## 结论

执行日期：2026-07-30（Asia/Taipei）

结论：**本地修复验收通过；生产切流仍为 NO-GO。**

- 代码、契约、嵌入式集成、前端和交付制品未发现测试失败。
- 最终共执行 2,009 项测试（Java 1,164、Python 290、前端 555），0 failures、0 errors。
- Java 默认套件有 5 项外部环境集成测试被显式跳过。
- Docker 恢复后，以真实 Casdoor 登录和付费模型覆盖了全部 9 个前端模块；核心 Chat、
  RAG、AgentScope、任务、分析、工作流、视觉、MCP/A2A 和渠道发现链路均通过。
- Knowledge S3 split 与 Eval 目录问题均已修复并完成真实容器、登录态 Chrome 复验；详见
  [CHROME_REPORT.md](./CHROME_REPORT.md)。生产仍需 IAM、故障注入、压测、soak 与 canary。

## 环境

| 项 | 版本/状态 |
|---|---|
| 工作区入口 | `langchain4j-platform`，commit `f4b8f14`，含大量既有未提交改动 |
| Java | OpenJDK 21.0.11 |
| Node/npm | Node v24.12.0 / npm 11.6.2（CI 指定 Node 22，本机实际版本更高） |
| uv | 0.11.3 |
| Helm | v4.1.4 |
| Docker client | 29.5.2 |
| Docker daemon | 可用：29.5.2；标准本地栈已重建并运行 |
| 浏览器身份 | Chrome；Casdoor `alice / acme / Bearer` |
| 真实模型 | 已获用户授权并实际调用百炼文本、embedding、rerank、视觉模型 |

## 执行结果

| 编号 | 层级 | 命令/范围 | 结果 |
|---|---|---|---|
| QA-01 | Java 默认回归 | `mvn -B test`，23 个 Reactor 模块 | 通过；1,164 项，1,159 通过、5 跳过 |
| QA-02 | Java 跨仓契约 | `mvn -B -Pcontract test` | 通过；在默认套件上新增 5 项契约 |
| QA-03 | Flowable 集成 | `mvn -B -pl workflow-service -am -Pflowable-it test` | 通过；9 项专用集成测试 |
| QA-04 | Kafka 集成 | `mvn -B -pl platform-eventbus -am -Pkafka-it test` | 通过；2 项 exactly-once 测试 |
| QA-05 | Java 打包 | `mvn -B -DskipTests package` | 通过；23/23 模块成功 |
| QA-06 | 前端测试 | `npm ci` + `npm test -- --run` | 通过；66 文件、555 项 |
| QA-07 | 前端类型/构建 | `npm run type-check` + `npm run build` | 通过 |
| QA-08 | Python 静态检查 | `uv sync --dev`、ruff、mypy | 通过；mypy 检查 60 个源文件 |
| QA-09 | Python 契约 | `scripts/export_contracts.py --check` | 通过；导出内容与仓库一致 |
| QA-10 | Python 回归 | `uv run pytest` | 通过；290 项 |
| QA-11 | Compose | 基础、RAG、ES、Knowledge split、failover、oracle 六组配置 | 全部通过 |
| QA-12 | Helm | lint；默认、Knowledge split、legacy+eval 三组 template | 全部通过 |
| QA-13 | 脚本 | `deploy/*.sh` 语法 + `test-bailian-env.sh` | 全部通过 |
| QA-14 | localhost 探活 | edge `:18080`、前端 `:8093`、AgentScope `:18085` | 通过 |
| QA-15 | Chrome 真实整栈 | 9 模块、OIDC、HTTP/SSE、付费模型 | 通过；修复后 Eval/RAG 登录态复验通过 |
| QA-16 | S3-compatible Knowledge split | MinIO、query、ingest-api、worker | 通过；job READY、5 sinks 成功、query 命中原文 |

Java 测试数采用默认套件去重，并只把 5 项 contract、9 项 Flowable 和 2 项 Kafka 专用测试
计入一次。`-Pcontract` 会重跑默认套件，因此没有按命令输出重复累计。

## 跳过与阻塞

| 范围 | 数量/状态 | 原因 |
|---|---|---|
| Knowledge AuthZ 外部集成 | 3 skipped | 默认套件缺少真实授权服务环境 |
| Casdoor 多租户登录/JWKS | 6 skipped | 默认套件缺少 Casdoor 集成环境 |
| S3-compatible 原文存储真实链路 | PASS | MinIO 原文、durable job、worker 全 sink、split query 闭环通过 |
| Qdrant/ES/Redis/MySQL 跨进程主链路 | PASS | RAG 入库回查、异步任务、工作流和 NL2SQL 均经真实容器通过 |
| edge HTTP/SSE 与浏览器 UI | PASS | Chrome 真实 OIDC 会话覆盖 9 模块 |
| Eval | PASS | 默认目录诚实显示 6 项离线能力未启用；运行按钮禁用且未发请求 |
| Voice 文件上传 | BLOCKED | Chrome 扩展未开启文件 URL 权限；本轮未重复浏览器 ASR |

## 非阻断警告

1. Maven 从 Aliyun 镜像读取 `slf4j-api` metadata 时提示无 checksum；依赖解析和构建仍成功。
2. macOS 缺少 Netty native DNS resolver，edge 测试回退系统 DNS。建议补齐对应 native
   dependency，避免本机 DNS 行为与 Linux CI 不一致。
3. JUnit classpath 发现两个 `junit-platform.properties`；当前结果通过，但配置优先级可能掩盖
   其中一个文件。
4. Mockito/ByteBuddy 动态加载 Java agent 的方式将在未来 JDK 默认禁用。
5. 前端安装提示 `glob@10.5.0` 已弃用且存在公开漏洞；应升级传递依赖并复核 lockfile。
6. Vite 提示 OIDC 模块同时被静态和动态导入，无法拆成独立 chunk；不影响正确性。
7. Python 唯一 warning 来自 FastAPI/Starlette TestClient：当前 `httpx` 接入方式已弃用，
   上游建议迁移至 `httpx2`。

## Bug 单

1. **P1 — Knowledge split 不可启动**：S3 client 初始化缺少
   `org.apache.hc.client5.http.ssl.TlsSocketStrategy`；同时默认 ingest API 宿主端口
   18085 与 AgentScope 冲突。
2. **P1 — Eval 假就绪**：前端显示 12/12 就绪，标准 Compose 没有运行 eval-service，
   检索评测真实请求失败。
3. **P2 — Vision 错误映射**：provider 对非法图像尺寸返回 400 语义，服务对外变成 500。
4. **P2 — RAG 状态漂移**：静态 capability 仍显示降级，live discovery 已确认百炼
   embedding、Qdrant、ES/GraphRAG 和 RRF 均开启。

详细复现、trace 和 Go/No-Go 见 [CHROME_REPORT.md](./CHROME_REPORT.md)。

## 修复复验（2026-07-30）

原 Bug 单 4 项均已完成代码和容器级修复：

1. Knowledge 改用 AWS URLConnection client，消除 Spring BOM 与 AWS Apache5 client 的
   二进制冲突；split 专属模型变量默认 hash，默认端口改为 18095。
2. Eval 保持离线控制面边界，默认目录改为 `flag-off`，UI 在请求前禁用并说明应从
   evaluation harness / CLI / CI Job 执行。
3. Vision provider `InvalidRequestException` 映射为稳定 400，且不向客户端透传上游细节。
4. `rag.query` 静态状态改为中性 ready，真实形态继续由 live discovery 展示。

真实 split 复验同时发现并修复两项隐藏问题：Qdrant point ID 改为确定性 UUID；query
角色对白名单结构化 POST 检索放行。最终证据：

- 三个 split 角色同时运行；ingest-api 宿主端口为 18095。
- MinIO multipart 提交返回 202，job `efb9907d-a02b-4408-b1f1-ef38ecdebb52`
  达到 READY，Vector/ES/Graph/AuthZ/Registry 五个 sink 全部 SUCCEEDED。
- MinIO 存在 48B 不可变 hash 对象；split `/rag/query` 返回 200，首条命中该原文。
- 同一 1×1 PNG 真实 provider 拒绝用例返回 HTTP 400 + 稳定 `bad_request`。
- Java 全栈回归通过；后续 Knowledge 280 tests（3 skipped）、Vision 7 tests 通过。
- Python ruff/mypy/290 tests、前端 type-check/build/555 tests 均通过。

Chrome 最终页面复验已通过：`alice / acme / Bearer` 登录态下，Interop/Eval 为 6 项就绪、
6 项未启用，Eval 按钮禁用并显示离线执行说明；RAG 显示 ready 与实时运行形态，UI 查询
“退款政策”返回 5 条。生产切流仍因 IAM、故障注入、并发、soak 和 canary 门禁保持 NO-GO。
