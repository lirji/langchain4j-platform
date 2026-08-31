# 财税发票风险审查助手交付报告

## 交付结论

首个财税 AI 纵向切片已经达到 Gate B：工程实现、权限、租户隔离、确定性降级、GoldenCase、代码图、Docker 沙箱、差异评审、QA、部署配置和 CI 均已落地并通过本地验证。

核心边界保持不变：Java 规则是风险代码与等级的唯一权威；模型只生成中文辅助说明，不能新增或修改结论；没有模型或政策库时仍返回稳定结果和免责声明。

## 已交付能力

- `POST /tax/invoices/review`：结构化中国增值税发票批次审查。
- 四类稳定规则：重复发票、高风险价税合计错误、中风险税额错误、中风险跨期间。
- `BigDecimal` 金额计算、0.01 元容差、最多 100 张和稳定 400 错误合同。
- `tax-review` 最小权限、可信租户回显、知识查询内部令牌转发与跨租户响应丢弃。
- 政策证据编号、片段限长、模型引用白名单、提示注入分隔和 AI/RAG fail-soft。
- 仅记录计数、风险代码、模式等白名单字段的业务审计。
- Compose、Helm、供应链镜像矩阵和独立 Tax AI CI。

## 证据入口

- 验收矩阵：`docs/qa/tax-invoice-risk-review/QA.md`
- 评审：`docs/qa/tax-invoice-risk-review/REVIEW.md`
- GoldenCase：`docs/qa/tax-invoice-risk-review/GOLDEN_CASES.md`
- 代码图：`docs/qa/tax-invoice-risk-review/CODEGRAPH_REPORT.md`
- Docker：`docs/qa/tax-invoice-risk-review/DOCKER_SANDBOX_REPORT.md`
- 演示：`docs/qa/tax-invoice-risk-review/DEMO_SCRIPT.md`
- 使用与运维：`docs/财税/tax-invoice-risk-review.md`

## 发布建议

1. 先执行授权库 V3 迁移并确认 `admin` / `tax-analyst` scope。
2. 以 `TAX_AI_ENABLED=false`、`TAX_KNOWLEDGE_ENABLED=false` 发布确定性核心。
3. 在测试租户导入经审核的 `tax-policy` 文档，先开启 RAG，再开启 AI。
4. 观察 400/403、风险分布、无证据率、AI 降级率、耗时和错误率后灰度授权。

## 回滚

- 优先关闭 `TAX_AI_ENABLED` 与 `TAX_KNOWLEDGE_ENABLED`，确定性审查仍可用。
- 完整回滚时移除 edge 路由并停止 `tax-service`；无发票数据或消息状态要回滚。
- V3 新增角色/scope 可安全保留，避免破坏已经引用该 scope 的授权配置。

## 未包含

OCR、发票真伪、抵扣资格、电子税局、自动记账/申报、真实生产数据、生产部署及真实模型额度均未包含在本次交付中。
