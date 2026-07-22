# 全能力体检修复报告

执行时间：2026-07-22 12:00–12:30（Asia/Taipei）

输入基线：[QA_REPORT.md](QA_REPORT.md)

范围：按建议顺序修复 6 项 P1、P2 输入/合同问题、A2A 鉴权声明和 edge Docker DNS 恢复问题。

## 结论

本轮列出的业务代码与部署缺陷均已修复，并通过自动化或运行态聚焦复验。整栈当前 25/25 容器运行，
edge、前端及开放发现端点均为 200；13 个下游业务端口的匿名访问均为 401。

这不是一次完整的 82 项目录能力重跑，因此不覆盖原报告的历史统计。图片向量和 Voice 在外部 provider
未配置时改为诚实关闭；真实 UI 浏览器验收因当前运行时仍无浏览器实例而继续 `BLOCKED`。因此可以认定
“本轮问题已收口”，但仍不应仅凭本报告宣称“所有外部依赖能力均已启用”。

## 修复结果

| 编号 | 修复 | 验证 |
|---|---|---|
| SEC-01 | 下游业务路径默认强制内部 JWT；API key fallback 默认关，仅 health/info 开放 | conversation/workflow/analytics/knowledge/agent/async/channel/interop/eval/vision/voice/auth/order 直连均 401；有效内部 JWT 查订单为 200 |
| WF-01 | Compose 注入 `http://conversation-service:8081` 并补依赖关系 | 真实退款流程 `COMPLETED`；无 localhost、重试耗尽或降级信号；QA 实例已精确 purge |
| EVAL-01 | 移除 Compose legacy key；增加带 `token_use=service_callback` 的服务身份；edge 专门验签；仅向可信 origin 发送 | Eval 直连发起 `/orders/204` case，`total=1, passed=1`；普通内部 JWT 冒充服务令牌的单测被拒绝 |
| MM-01 | 图片向量 yml/Compose/catalog 默认关；base URL 默认空；显式开启但 URL 空时 fail-fast | 容器 `RAG_MULTIMODAL_ENABLED=false`、URL 空；前端两项为 `flag-off/default=false`；配置测试通过 |
| VOICE-01 | Voice yml/Compose/catalog 统一默认关 | 容器 `VOICE_ENABLED=false`；三项目录均 `flag-off/default=false`；带内部身份调用端点为 404 |
| GRAPH-01 | 删除文档时同时按 `displayName#` 与 Obsidian `docId` 前缀清理关系 | 新增删除 Obsidian wikilink 的回归测试并通过 |
| ANA-01 | 空/纯空白问题在模型调用前返回 400 | 运行态空问题为 400；控制器测试通过 |
| ANA-02 | schema 读取限定当前 catalog/schema，并按列名去重 | 新增元数据冲突/去重测试并通过 |
| VOTE-01 | 候选数限制为 1..`maxCandidates`，非法参数在任何模型调用前返回 400 | 运行态 `n=0` 为 400；测试断言模型调用数为 0 |
| VOICE-02 | stream 与同步端点共用音频校验，空音频在 SSE 建立前返回 400 | 新增 MockMvc 回归测试并通过；默认部署保持 Voice 关闭 |
| A2A-01 | Agent Card 随 `only/dual/legacy` 输出 Bearer/API-key 声明 | `/.well-known/agent-card.json` 为 200，默认声明 `http/bearer` 与 `bearerAuth` |
| OPS-01 | Gateway DNS 正/负缓存 TTL、查询超时和连接池生命周期收紧 | 临时占住 auth 旧 IP 后启动新容器，确认 IP 已变化；第 5 次轮询恢复 200，edge 容器 ID 不变 |

## 安全边界

- 服务回调令牌与普通内部 JWT 有不同用途声明，普通内部令牌不能拿到外部 edge 的 Casdoor-only 旁路。
- 服务回调头只对 `INTERNAL_SERVICE_TOKEN_ALLOWED_ORIGINS` 中的 HTTP(S) origin 注入，避免 Eval 自定义
  oracle/candidate URL 接收到栈内凭据。
- 下游宿主端口为本地诊断继续保留发布，但业务访问已 fail-closed；生产仍建议在网络层只暴露 edge。
- Eval retrieval 的 401/网络异常转换为显式 502，不再产生看似合法的全 0 报告。

## 自动化证据

- `mvn test`：1059 tests，0 failures，0 errors，5 skipped（条件集成测试）。
- `mvn -DskipTests package`：23 个 Maven reactor 模块打包成功，并重新执行 Spring Boot repackage。
- 前端 `npm test`：552/552；`npm run type-check` 通过；`npm run build` 通过。
- `docker compose config --quiet` 与 `git diff --check` 通过。
- Compose 从重新打包后的 fat JAR 重建；25/25 容器为 running，edge/frontend 为 200。

## 仍保留的验收边界

- 当前浏览器控制运行时返回空实例列表，无法执行真实点击/视觉验收；保留 `BLOCKED`，未用源码检查冒充 UI 黑盒。
- 图片向量与 Voice 需要真实外部 provider/key 才能从“诚实关闭”转为能力 PASS；启用时应单独执行 provider 合同测试。
- 本轮没有重跑完整 82 项目录矩阵；若要更新 65/15/2 历史统计，应基于本报告后的镜像重新执行
  [QA_PLAN.md](QA_PLAN.md)。
