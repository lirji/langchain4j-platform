# 财税发票风险审查 QA 报告

## 结论

Gate B 工程验证通过：AC-01～AC-14 均有已执行证据，当前无产品失败或阻塞项。未执行生产部署、真实企业数据、真实政策库内容质量和真实模型专业性验证，这些项目不在本次批准范围内，未被标记为通过。

## 环境与版本

- 日期：2026-08-31。
- 宿主：macOS，Java 21.0.11，Maven，Docker Desktop，Helm。
- 应用：Spring Boot 3.3.5，仓库工作区未提交版本。
- 数据：虚构发票、税号和政策片段；无真实凭据或生产访问。
- 本机集成端口：Tax 18094、Edge 18080、Eval 18089；完成后进程已停止。

## 验收条件映射

| AC | 用例与预期 | 实际证据 | 状态 |
| --- | --- | --- | --- |
| AC-01 | 一致发票为 `CLEAR`，金额精确 | 规则测试、1 分容差测试、GoldenCase clear 通过 | PASS |
| AC-02 | 重复/合计/税额输出稳定代码和等级 | 规则测试；HTTP GoldenCase 覆盖重复与金额双异常，均通过 | PASS |
| AC-03 | 跨期为 `MEDIUM`，总体取最高 | 规则测试与跨期 GoldenCase 通过；混合风险总体 `HIGH` | PASS |
| AC-04 | 空批次、101 张、非法期间、空字段、负金额、越界税率拒绝 | 校验测试覆盖全部边界；控制器返回稳定 400 `code` | PASS |
| AC-05 | 无 scope 403，可信租户回显并用于知识查询 | Controller/客户端测试；实际无 key 401、chat-only 403、tax scope 200，响应 tenantA | PASS |
| AC-06 | 证据编号且 AI 不得伪造引用 | 客户端映射/限长测试；AI 测试拒绝 E99、要求有效引用或无证据披露 | PASS |
| AC-07 | LiteLLM/RAG 失败确定性降级 | 模型异常和知识异常测试均返回 `FALLBACK`/空证据，规则结果不变 | PASS |
| AC-08 | 审计不含敏感业务字段 | allowlist 单测；实际审计仅含 reviewId、计数、代码、风险、证据数和模式 | PASS |
| AC-09 | Edge/Compose/Helm 可达且配置有效 | 实际 Edge 200；Compose config、Helm lint、Shell 语法均通过 | PASS |
| AC-10 | Eval 可加载和运行财税套件 | loader 测试通过；实际 HTTP 4/4、`passRate=1.0`、HTTP 202 | PASS |
| AC-11 | 代码图定位财税链路和测试 | 1,079/1,079、0 failed；5 个核心符号及相关测试 found | PASS |
| AC-12 | Docker 强隔离运行 Maven | network none、只读、UID 65532；21/21，exit 0 | PASS |
| AC-13 | CI 覆盖测试、GoldenCase、代码图、配置 | Workflow YAML 可解析；固定 SHA、最小权限；所有底层命令本地通过 | PASS |
| AC-14 | API、边界、监控、回滚和演示文档同步 | README、API/能力/架构/运维/部署/供应链、财税指南及 QA 文档已同步 | PASS |

## 回归结果

最终命令：

```bash
mvn -pl tax-service,edge-gateway,auth-service,eval-service,database-migrations -am test
```

结果：11 个 Reactor 项全部 `SUCCESS`，共 349 个测试，0 failure、0 error。`edge-gateway` 中 6 个既有 Casdoor 外部集成测试因未提供外部环境而条件跳过，其余 343 个执行通过。

| 模块 | Tests | Failure | Error | Skipped |
| --- | ---: | ---: | ---: | ---: |
| platform-security | 62 | 0 | 0 | 0 |
| platform-observability | 7 | 0 | 0 | 0 |
| platform-gateway-client | 38 | 0 | 0 | 0 |
| database-migrations | 5 | 0 | 0 | 0 |
| auth-service | 95 | 0 | 0 | 0 |
| eval-service | 65 | 0 | 0 | 0 |
| tax-service | 21 | 0 | 0 | 0 |
| edge-gateway | 56 | 0 | 0 | 6 |

## 配置与供应链门禁

以下检查通过：

```bash
docker compose -f deploy/docker-compose.yml config --quiet
bash -n deploy/start-all.sh deploy/start-dev.sh deploy/start-local.sh
helm lint deploy/helm/platform
bash deploy/test-supply-chain-config.sh
ruby -e 'require "yaml"; Dir[".github/workflows/*.yml"].each { |f| YAML.parse_file(f) }'
git diff --check
```

首次 Workflow YAML 检查使用了 Ruby 2.6 不支持的 `YAML.load_file(..., aliases: true)` 参数而失败；改用同版本支持的 `YAML.parse_file` 后通过。该项判定为测试工具兼容问题，不是产品缺陷。

## 本机 HTTP 证据

- 无认证请求：401。
- `dev-key-globex`（仅 chat）：403。
- `dev-key-tax-review`：200，tenantA，`HIGH`，2 条稳定 finding，`FALLBACK`，含免责声明。
- Eval 套件：202，4/4，`passRate=1.0`。
- 审计：输出 `tax.invoice_reviewed` 及白名单字段，不含税号、票号、金额和证据正文。

精简集成环境未启动 Redis，Actuator 聚合健康因此为 503；业务 HTTP、权限、Eval 和审计均成功。完整 Compose 已声明 Redis，演示脚本可通过关闭无关 Redis health indicator 保持精简环境健康检查为 UP。

## 发布风险与建议

- 先关闭 AI/RAG 发布确定性核心，再按测试租户灰度开启。
- 政策证据质量、法规时效和真实模型输出必须由财税负责人验收。
- 监控 AI 降级率、RAG 无命中率、400/403、风险分布和延迟，但不得采集发票明文。
- 任何生产发布、scope 批量授权或真实数据回放都需要新的明确授权。
