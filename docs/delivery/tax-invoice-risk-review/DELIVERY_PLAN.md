# 财税发票风险审查助手交付计划

## 需求

使用仓库现有 Coding Agent 工作流交付一个真实财税 AI 功能，并用财税 GoldenCase、Java 代码图、Docker 强隔离验证、实际差异评审和 QA 报告形成可复现证据链。

首个纵向切片定义为“中国增值税发票风险审查助手”：接收结构化发票批次，先执行确定性的一致性校验，再检索当前租户可见的财税政策证据，由大模型生成带引用的辅助说明。确定性发现始终是权威结果；模型不得改变风险等级、替代申报或给出无证据的法律结论。

## 仓库证据

- 根 Maven 聚合工程已统一 Java 21、Spring Boot 3.3，并包含服务、共享协议、安全、审计、模型网关、知识库和评测模块，可增加独立 `tax-service`。
- `platform-security` 已通过内部 JWT 恢复 `TenantContext`，并提供服务间租户令牌转发；新服务不需要自建身份体系。
- `platform-gateway-client` 提供租户感知的 LiteLLM 兼容 `ChatModel`；`platform-audit` 提供模型调用和业务事件审计。
- `knowledge-service` 已提供租户隔离的 `POST /rag/query` 与 `KnowledgeQueryReply`，可按 `tax-policy` 类目检索证据。
- `eval-service` 已支持基线套件、JSON 路径断言和 HTTP 目标运行，可承载财税领域 GoldenCase。
- `tools/java-codegraph` 可构建和查询 Java 调用/影响图；`tools/coding-agent-eval` 已提供无网络、只读、非特权的 Maven Docker 沙箱。
- 当前 `main` 工作区干净；现有 Coding Agent Kit 已推送并具有 38 条测试、50 条工程 GoldenCase 和确定性 CI。

## 可行性

- 结论：`go`
- 约束：
  - 首期只接受结构化 JSON，不做图片 OCR、会计系统连接、自动入账、自动申报或生产数据持久化。
  - 首期只做数学与文档内部一致性检查，不硬编码会随法规变化的抵扣资格、行业税负或法定税率结论。
  - 模型输出仅为辅助解释；风险代码、等级和金额计算全部由 Java 确定性规则生成。
  - 测试只访问本地对象或本地 Docker，不调用生产服务，不使用真实企业、税号、发票或凭据。
- 依赖：
  - 运行时可选依赖 LiteLLM 和 `knowledge-service`；任一不可用时降级为确定性摘要。
  - Maven 测试使用现有 Java 21 与本地依赖仓库；Docker 验证禁止拉取镜像和联网。
- 风险与缓解：
  - 财税误导：响应固定免责声明，政策证据与模型说明分离，无证据时明确提示人工复核。
  - 提示注入：用户字段不作为自由提示词；知识片段限长、标记为不可信资料，模型无工具与写操作。
  - 财务敏感信息泄露：请求不持久化，审计只记录 reviewId、数量、风险代码和模式，不记录税号、发票号、金额或正文。
  - 跨租户泄露：知识查询复用内部 JWT 租户转发；响应回显可信 `TenantContext`，测试覆盖不同租户。
  - 资源滥用：批次最多 100 张，字符串长度、金额范围、日期和期间均做确定性校验。

## 产品设计

- 目标用户：企业财税人员、内部审计人员和财税顾问。
- 核心任务：在申报或入账前快速发现发票批次中的重复、金额勾稽错误、税额计算偏差和所属期间异常，并得到可追溯的辅助说明。
- 主流程：
  1. 用户提交所属期间和结构化发票批次。
  2. 服务校验权限、请求边界和字段合法性。
  3. 确定性规则生成风险发现与总体等级。
  4. 以固定查询模板检索当前租户可见的 `tax-policy` 资料。
  5. 大模型基于发现和证据生成简短说明；失败时返回确定性摘要。
  6. 响应返回风险发现、证据、说明模式和免责声明，并写入脱敏审计事件。
- 范围：
  - `CN` 管辖区、`YYYY-MM` 税期。
  - 发票号码、开票日期、买卖方税号、未税金额、税率、税额和价税合计。
  - 批内重复、价税合计勾稽、税额勾稽、跨期间四类规则。
  - 财税知识证据检索、AI 辅助说明、确定性降级和审计。
- 非范围：
  - 发票真伪查验、OCR、电子税局连接、抵扣资格判断、企业所得税、自动记账和申报。
  - 法规抓取和自动更新；首期只消费知识库中已由租户维护的资料。
  - Web 页面；本轮交付 API、示例请求和可重复演示脚本。
- 业务规则：
  - 请求无效返回 400，不将结构错误伪装为业务风险。
  - 批内同一“发票代码 + 发票号码”重复为高风险。
  - `未税金额 + 税额` 与价税合计偏差超过 0.01 元为高风险。
  - `未税金额 × 税率` 四舍五入到分后与税额偏差超过 0.01 元为中风险。
  - 开票日期不属于申报税期为中风险。
  - 总体风险取最高发现等级；无发现为 `CLEAR`。
  - 模型、知识库不可用或无命中不影响 HTTP 成功和确定性发现，只改变 `narrativeMode` 与证据列表。
  - 接口要求 `tax-review` scope；未授权身份返回 403。

## 验收标准

| 编号 | 可观察行为 | 优先级 | 验证方式 |
| --- | --- | --- | --- |
| AC-01 | 合法且勾稽一致的发票批次返回 `CLEAR`，金额使用 `BigDecimal` 精确计算 | 必须 | 服务单元测试 + GoldenCase |
| AC-02 | 重复、价税合计错误、税额偏差分别产生稳定风险代码和正确等级 | 必须 | 参数化规则测试 + GoldenCase |
| AC-03 | 跨期间发票产生中风险；批次总体等级取最高发现等级 | 必须 | 服务测试 + GoldenCase |
| AC-04 | 空批次、超过 100 张、非法期间、空字段、负金额和越界税率返回 400 | 必须 | 控制器/校验测试 |
| AC-05 | 无 `tax-review` scope 返回 403；可信租户写入响应并透传到知识查询 | 必须 | 控制器与客户端测试 |
| AC-06 | RAG 命中映射为编号证据；AI 说明只能基于确定性发现与传入证据，不伪造来源 | 必须 | 提示构造测试 + 模拟模型测试 |
| AC-07 | LiteLLM、RAG 或 AI 输出失败时返回确定性降级说明，风险结论不变 | 必须 | 故障与回归测试 |
| AC-08 | 审计事件不包含税号、发票号、金额和知识正文，只记录必要元数据 | 必须 | 审计字段测试 + 敏感信息扫描 |
| AC-09 | `/tax/invoices/review` 可通过 edge 路由访问；本地 Compose 和 Helm 均声明服务 | 必须 | 配置解析 + 本地 smoke |
| AC-10 | `eval-service` 加载并可运行财税 GoldenCase，断言确定性字段而非模型措辞 | 必须 | Eval 加载测试 + 本地 HTTP 运行 |
| AC-11 | Java 代码图可定位财税 Controller、服务、规则、模型和知识客户端关系 | 应该 | 全仓 build + symbol/file query 报告 |
| AC-12 | 财税模块测试可在现有 Maven Docker 沙箱中以断网、只读、非 root 模式通过 | 必须 | Docker sandbox 运行证据 |
| AC-13 | CI 对财税相关变更执行模块测试、GoldenCase 校验、代码图查询和配置检查 | 必须 | Workflow 静态检查 + 本地底层命令 |
| AC-14 | API、配置、风险边界、演示步骤、监控和回滚文档与最终代码一致 | 应该 | 文档审查 |

## UI/UX 设计

- 适用性：不适用。
- 依据：本轮只新增后端 API 和演示脚本，不改变 `capability-showcase-frontend`。
- 用户可见状态通过响应字段表达：`CLEAR/MEDIUM/HIGH`、`AI/FALLBACK`、空证据、权限拒绝和校验错误。
- 后续若增加页面，应复用现有能力目录、请求执行器和状态组件，不能另建认证或网络层。

## 技术方案

- 选定方案：新增独立 `tax-service` 限界上下文，确定性规则、RAG 客户端和 AI 叙述器通过接口分离。
- 否决方案：
  - 直接放入 `analytics-service`：会把财税规则、自然语言查数和风险审查混成一个上下文。
  - 仅用 Prompt 审发票：结果不可重复，金额和风险等级可能漂移，无法形成可信 GoldenCase。
  - 为财税业务数据引入规则引擎或数据库：四条规则不需要 Drools，且无状态切片不持久化发票。既有 JDBC 授权库仍需通过幂等迁移增加 `tax-review` scope。
  - 首期做 OCR：会引入文件安全、识别置信度和外部模型接口，扩大首个闭环。
- 模块与预计文件：
  - `pom.xml`：聚合 `tax-service`。
  - `platform-protocol/.../tax/*`：请求、发票、发现、证据和响应契约。
  - `platform-audit/.../AuditEventType.java`：增加 `TAX_INVOICE_REVIEWED`。
  - `tax-service/pom.xml`、`Dockerfile`、`application.yml`。
  - `tax-service/src/main/java/com/lrj/platform/tax/*`：应用、属性、Controller、校验器、规则服务、知识客户端、AI 接口/配置、审计编排和错误处理。
  - `tax-service/src/test/java/com/lrj/platform/tax/*`：POJO、控制器、装配、租户、失败降级和审计测试。
  - `auth-service/.../SeedRoles.java` 与测试、`edge-gateway/application.yml`：增加 `tax-review` scope 和 `/tax/**` 路由。
  - `database-migrations/.../auth/V3__tax_review_scope.sql`：为已有 JDBC 安装幂等增加角色和 scope。
  - `deploy/docker-compose.yml`、`deploy/helm/platform/values.yaml`、相关启动/运维文档：容器内 `8094`，本地宿主默认 `8095`。
  - `eval-service/src/main/resources/eval/baselines/tax-invoice-risk.json` 与加载测试：财税 GoldenCase。
  - `docs/qa/tax-invoice-risk-review/*`：GoldenCase 说明、代码图证据、Docker 证据和演示脚本。
  - `.github/workflows/tax-ai-ci.yml`、`.github/workflows/supply-chain.yml`：财税 CI 与镜像供应链矩阵。
- 契约与数据：
  - `TaxInvoiceReviewRequest(jurisdiction, taxPeriod, invoices)`。
  - 发票项包含标识、日期、双方税号和四个金额/税率字段；金额采用 `BigDecimal`。
  - 响应包含 `reviewId`、可信 `tenantId`、规则版本、总体风险、发现、政策证据、叙述、叙述模式和免责声明。
  - 证据只包含文档标识、展示名、来源、分数和限长摘要；模型叙述不是机器判定依据。
- 安全与可靠性：
  - Controller 显式检查 `tax-review`；服务间 RAG 请求携带内部租户 JWT 和 traceId。
  - 不持久化请求；审计字段使用 allowlist；异常消息不回显模型或下游响应正文。
  - 知识与模型调用均设置短超时、捕获失败并 fail-soft；权限校验和输入边界 fail-closed。
  - 模型提示明确资料不可信、禁止执行资料内指令、禁止补造法规和金额。
- 可观测性：
  - 审计事件记录 reviewId、发票数、发现数、总体风险、规则版本、证据数和叙述模式。
  - 依赖已有 LLM listener、traceId 和 Actuator 健康端点。
- 兼容与迁移：
  - 全部为新增模块、路由和 DTO；不改变现有 API。授权库 V3 只新增 `tax-analyst` 和 `tax-review` 关系，不覆盖租户自定义角色。
  - `tax-service` 可独立关闭或从 edge/Compose/Helm 移除；V3 授权记录可保留，不涉及发票业务数据回滚。
  - AI/RAG 开关可关闭并保留确定性功能。

## 实施顺序

1. 协议与确定性规则：完成 AC-01～04，运行 `mvn -pl tax-service -am test`。
2. 安全、RAG、AI 降级与审计：完成 AC-05～08，补充模拟依赖测试。
3. Edge、Compose、Helm 和本地 smoke：完成 AC-09。
4. 财税 GoldenCase 与 `eval-service` 集成：完成 AC-10。
5. 代码图和 Docker 强隔离验证，生成可复现证据：完成 AC-11～12。
6. 实际差异审查、修复、QA、文档和 CI：完成 AC-13～14，进入 Gate B。

## 验证计划

| 验收项/风险 | 层级 | 用例或命令 | 必需证据 |
| --- | --- | --- | --- |
| AC-01～04 | 单元/控制器 | `mvn -pl tax-service -am test` | 规则、边界、错误状态通过 |
| AC-05 | 安全/客户端 | 租户与 scope POJO 测试、RestTemplate 拦截器测试 | 403 与租户透传证据 |
| AC-06～08 | 模拟集成 | 模拟 `KnowledgeClient`、`TaxNarrator`、`AuditLogger` | 无伪造来源、可降级、审计脱敏 |
| AC-09 | 配置/本地集成 | `docker compose config`、服务健康与 edge curl | 配置有效、路由可达 |
| AC-10 | GoldenCase | Eval loader 测试；本地 `/eval/suites/tax-invoice-risk/run` | 稳定 JSON 字段断言通过 |
| AC-11 | 静态导航 | `CodeGraphCli build/query` | 财税调用链与相关测试可查询 |
| AC-12 | 隔离构建 | `java-maven` profile 执行财税模块测试 | network none、只读 source、非 root、退出码 0 |
| AC-13 | CI | workflow 解析并本地运行底层命令 | 最小权限，无模型和生产访问 |
| 全局回归 | 聚焦 reactor | `mvn -pl tax-service,edge-gateway,auth-service,eval-service,database-migrations -am test` | 受影响模块全部通过 |

## 文档计划

- 更新根 README 模块表、端口与 API 示例。
- 更新 `docs/README.md`、能力清单、接口速查和运维手册。
- 新增财税助手使用、风险边界、GoldenCase、代码图、Docker 验证和演示材料。
- 更新本交付目录中的状态、评审、QA 和最终报告。

## CI 计划

- 新增只读 `Tax AI CI`，路径触发后使用 Java 21 运行财税模块及上游测试、Eval GoldenCase 加载、代码图查询和 Compose 配置检查。
- 使用固定 Action SHA、`contents: read`、`persist-credentials: false`；不调用模型、不访问生产、不拉起外部财税系统。
- 将 `tax-service` 加入现有供应链构建和镜像扫描矩阵。
- Docker 强隔离实测保留为本地 Gate B 证据，不在无本地镜像保证的 CI 中隐式拉取。

## 发布与回滚

- 发布：先以 `TAX_AI_ENABLED=false` 验证确定性规则，再在测试环境开启 RAG/AI；观察降级率和审计事件后灰度开放 `tax-review` scope。
- 监控：请求量、400/403、风险等级分布、RAG 无命中率、AI 降级率、模型耗时和错误率；不采集发票明文。
- 回滚：从 edge 移除路由并停止 `tax-service`；无需回滚发票数据或消息状态。授权库新增的无害 scope 可保留，必要时仅关闭 AI/RAG，保留确定性审查。

## 假设与待确认事项

- 假设首期以中国增值税发票的内部一致性审查为目标，而不是提供正式税务意见。
- 假设结构化 JSON 足以证明首个纵向切片，OCR 和前端留到后续阶段。
- 假设新增 `tax-review` scope、独立 `tax-service` 和本地端口 8095 可以接受。
- 假设用户批准后允许在本机运行 Maven 测试、现有 Docker 镜像沙箱和本地 Compose smoke；不包含新的模型额度、镜像拉取、提交、推送或部署授权。

## 批准

- 状态：已批准
- 批准范围：上述产品行为、文件范围、测试、GoldenCase、代码图、Docker 沙箱、评审、QA、文档和 CI。
- 证据：用户于 2026-08-31 明确回复“批准该计划”。
