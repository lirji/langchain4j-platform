# 财税发票风险审查助手

## 能力边界

`tax-service` 提供 `POST /tax/invoices/review`，面向财税人员在入账或申报前检查结构化中国增值税发票批次。Java 确定性规则负责全部风险代码和等级；租户知识库与大模型只生成辅助证据和说明，不得改变判定。

该能力不查验发票真伪，不判断抵扣资格，不连接电子税局，不做 OCR、自动记账或自动申报，也不构成正式税务意见。

## 确定性规则

| 风险代码 | 等级 | 条件 |
| --- | --- | --- |
| `DUPLICATE_INVOICE` | HIGH | 同一批次“发票代码 + 发票号码”重复 |
| `TOTAL_AMOUNT_MISMATCH` | HIGH | `未税金额 + 税额` 与价税合计偏差超过 0.01 元 |
| `TAX_AMOUNT_MISMATCH` | MEDIUM | `未税金额 × 税率` 四舍五入到分后与税额偏差超过 0.01 元 |
| `OUTSIDE_TAX_PERIOD` | MEDIUM | 开票月份不等于请求税期 |

总体风险取最高等级；没有发现时为 `CLEAR`。金额使用 `BigDecimal`，单批最多 100 张。

## 调用示例

本地 Compose 中 tax-service 容器端口为 8094、宿主默认映射 8095；业务入口仍推荐走 edge-gateway。`dev-key-tax-review` 仅用于本地 GoldenCase，生产应由 Casdoor 或 auth-service 角色授予 `tax-review`。

```bash
curl -X POST http://localhost:18080/tax/invoices/review \
  -H 'Content-Type: application/json' \
  -H 'X-Api-Key: dev-key-tax-review' \
  -d '{
    "jurisdiction":"CN",
    "taxPeriod":"2026-08",
    "invoices":[{
      "invoiceCode":"044001900111",
      "invoiceNumber":"00000002",
      "issueDate":"2026-08-16",
      "sellerTaxId":"91440000111111111A",
      "buyerTaxId":"91440000222222222B",
      "netAmount":100.00,
      "taxRate":0.13,
      "taxAmount":10.00,
      "totalAmount":111.00
    }]
  }'
```

上述请求稳定返回 `overallRisk=HIGH`，并产生 `TOTAL_AMOUNT_MISMATCH` 与 `TAX_AMOUNT_MISMATCH`。`reviewId` 每次随机；`narrative` 与政策命中不适合作确定性断言。

## AI、知识与安全

- 知识查询固定检索 `tax-policy` 类目，并复用内部 JWT 和 traceId；响应租户与当前租户不一致时整批证据丢弃。
- 知识片段限长并标为不可信资料。模型没有工具和写能力，不接收税号或发票号码。
- 模型回复只有在长度不超过 300 字、未伪造证据编号，并在有证据时实际引用允许的 `[E数字]` 后才标记为 `AI`；否则降级为 `FALLBACK`。
- 请求不持久化。业务审计只包含 reviewId、数量、风险代码、规则版本、证据数和说明模式，不记录税号、发票号、金额或知识正文。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `TAX_AI_ENABLED` | `true` | 关闭后只返回确定性说明 |
| `TAX_KNOWLEDGE_ENABLED` | `true` | 关闭后不查询 RAG |
| `TAX_MAX_INVOICES` | `100` | 允许范围 1～100 |
| `TAX_RULE_SET_VERSION` | `cn-vat-invoice-consistency-v1` | 响应与审计中的规则版本 |
| `TAX_KNOWLEDGE_CATEGORY` | `tax-policy` | 知识类目 |
| `TAX_KNOWLEDGE_TOP_K` | `5` | 允许范围 1～20 |
| `TAX_KNOWLEDGE_MIN_SCORE` | `0.2` | 允许范围 0～1 |
| `TAX_EVIDENCE_MAX_CHARS` | `600` | 单条证据最大字符数，允许范围 1～5000 |

## 评测、发布与回滚

GoldenCase 位于 `eval-service/src/main/resources/eval/baselines/tax-invoice-risk.json`，只断言规则版本、风险等级、数量和风险代码。运行方式：

```bash
curl -X POST http://localhost:8089/eval/suites/tax-invoice-risk/run \
  -H 'Content-Type: application/json' -d '{}'
```

发布时先令 `TAX_AI_ENABLED=false`、`TAX_KNOWLEDGE_ENABLED=false` 验证确定性路径，再逐步开启知识与 AI。关注 400/403、风险等级分布、RAG 无命中率、AI 降级率、模型耗时与错误率，日志和指标不得采集发票明文。

回滚优先关闭 AI/RAG；完整回滚可移除 edge 的 `/tax/**` 路由并停止 tax-service。服务无数据库和消息状态，不需要数据回滚。auth JDBC 环境由 `V3__tax_review_scope.sql` 幂等增加 `tax-analyst` 与 admin 的 `tax-review` scope。
