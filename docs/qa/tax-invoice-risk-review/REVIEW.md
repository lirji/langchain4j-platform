# 财税功能差异评审报告

## 评审范围

依据实际工作区 diff 和可达执行链，检查正确性、安全、租户隔离、AI/RAG 失败模式、授权迁移、部署兼容、测试和供应链。评审期间发现的问题均在同一交付中修复并重新验证。

## 发现与处理

| 严重度 | 场景与证据 | 修复 | 验证 |
| --- | --- | --- | --- |
| High | AI 开/关两种装配都同时存在具体 `DeterministicTaxNarrator` 与 `TaxNarrator` 候选，应用可能因注入歧义无法启动。证据：`tax-service/src/main/java/com/lrj/platform/tax/TaxAiConfig.java:14-36`。 | 两种条件分支的选定 `TaxNarrator` 均标为 `@Primary`。 | `TaxServiceApplicationTest` 与 `TaxAiApplicationContextTest` 分别验证关闭/开启模式启动。 |
| High | 只修改 Seed 不能给已有 JDBC 安装增加 `tax-review`，升级后 admin 可能持续 403。证据：`database-migrations/src/main/resources/db/migration/auth/V3__tax_review_scope.sql:1-34`。 | 增加幂等 V3：创建 `tax-analyst`、写入关系表，并给已存在 admin 增加 scope；不覆盖租户自定义角色。 | `SchemaMigrationRunnerTest` 在 H2 MySQL 模式跑 V1～V3，5/5 通过。 |
| Medium | 模型可返回伪造 `[E99]`、有证据却不引用，或无证据不披露；提示还曾包含金额差异明细。证据：`tax-service/src/main/java/com/lrj/platform/tax/AiTaxNarrator.java:39-68`。 | 程序化校验证据白名单、引用存在性、无证据文案与 300 字上限；模型只接收风险代码/等级，移除金额和票号明细。 | `AiTaxNarratorTest` 4/4，覆盖伪造引用、缺失披露、模型异常与提示分隔。 |
| Medium | 非法 `maxInvoices/topK/minScore/evidenceMaxChars` 配置可能在运行期静默产生错误行为。证据：`tax-service/src/main/java/com/lrj/platform/tax/TaxReviewProperties.java:15-79`。 | 使用配置属性 Bean Validation 在启动时 fail-fast。 | `TaxReviewPropertiesTest` 和双模式上下文测试通过。 |
| Medium | 新增第 18 个镜像后供应链静态门禁仍锁定 17，可能让矩阵覆盖断言失真。证据：`deploy/test-supply-chain-config.sh:56-60`。 | 更新为动态计数并要求 18/18；将 `tax-service` 加入扫描和发布矩阵。 | `bash deploy/test-supply-chain-config.sh` 通过。 |

## 已核对但未形成问题

- Controller 显式要求 `tax-review`，edge 路由不在开放路径；实际权限矩阵为 401/403/200。
- RAG 响应租户不匹配时丢弃全部证据；异常时返回空证据，不改变确定性发现。
- 审计字段采用 allowlist，不含税号、发票号、金额、证据正文；实际日志与单元测试一致。
- 金额采用 `BigDecimal`，偏差只有严格大于 0.01 元才命中。
- AI/RAG 关闭或失败不改变风险代码与总体等级。
- Workflow 使用固定 SHA、最小 `contents: read` 和不持久化 checkout 凭据。

## 结论

所有 High/Medium 发现均已修复并经过聚焦或全链路回归；当前没有未解决的高/中风险评审项。静态代码图不能证明反射/AOP 行为，真实政策内容和模型质量也未在本轮验证，应在测试环境灰度阶段继续观察。
