# 财税 AI 功能演示脚本

## 目标

在约 8 分钟内证明“确定性财税规则 + 最小权限 + Eval GoldenCase + 审计 + 工程证据”闭环。以下为本地主机演示，不连接生产。

## 1. 构建

```bash
mvn -pl tax-service,edge-gateway,eval-service -am -DskipTests package
```

## 2. 启动三段链路

终端一：

```bash
SERVER_PORT=18094 \
TAX_AI_ENABLED=false \
TAX_KNOWLEDGE_ENABLED=false \
MANAGEMENT_HEALTH_REDIS_ENABLED=false \
java -jar tax-service/target/tax-service-0.1.0-SNAPSHOT.jar
```

终端二：

```bash
SERVER_PORT=18080 \
TAX_URI=http://127.0.0.1:18094 \
EDGE_CASDOOR_ENABLED=false \
APP_RATE_LIMIT_ENABLED=false \
MANAGEMENT_HEALTH_REDIS_ENABLED=false \
java -jar edge-gateway/target/edge-gateway-0.1.0-SNAPSHOT.jar
```

终端三：

```bash
SERVER_PORT=18089 \
INTERNAL_AUTH_REQUIRED=false \
EVAL_TARGET_BASE_URL=http://127.0.0.1:18080 \
EVAL_API_KEY=dev-key-tax-review \
EVAL_JUDGE_ENABLED=false \
EVAL_EMBEDDING_ENABLED=false \
MANAGEMENT_HEALTH_REDIS_ENABLED=false \
java -jar eval-service/target/eval-service-0.1.0-SNAPSHOT.jar
```

`INTERNAL_AUTH_REQUIRED=false` 只用于直接访问本机 Eval 演示入口；业务目标仍必须经过 Edge 权限校验。

## 3. 展示权限矩阵

准备一个金额不一致的请求：

```bash
curl -i -H 'Content-Type: application/json' \
  --data '{"jurisdiction":"CN","taxPeriod":"2026-08","invoices":[{"invoiceCode":"044001900111","invoiceNumber":"00000002","issueDate":"2026-08-16","sellerTaxId":"91440000111111111A","buyerTaxId":"91440000222222222B","netAmount":100.00,"taxRate":0.13,"taxAmount":10.00,"totalAmount":111.00}]}' \
  http://127.0.0.1:18080/tax/invoices/review
```

不带 key 应为 401；增加 `X-API-Key: dev-key-globex` 应为 403；改为 `X-API-Key: dev-key-tax-review` 应为 200，并看到：

- `tenantId=tenantA`。
- `overallRisk=HIGH`。
- `TOTAL_AMOUNT_MISMATCH` 与 `TAX_AMOUNT_MISMATCH`。
- `narrativeMode=FALLBACK` 和固定免责声明。

## 4. 运行 GoldenCase

```bash
curl -i -H 'Content-Type: application/json' \
  --data '{}' \
  http://127.0.0.1:18089/eval/suites/tax-invoice-risk/run
```

预期 HTTP 202，`total=4`、`passed=4`、`passRate=1.0`。

## 5. 展示工程证据

```bash
bash tools/java-codegraph/test/run-tests.sh
bash tools/coding-agent-eval/test/sandbox-smoke.sh
mvn -pl tax-service,edge-gateway,auth-service,eval-service,database-migrations -am test
```

随后打开本目录的 `CODEGRAPH_REPORT.md`、`DOCKER_SANDBOX_REPORT.md`、`REVIEW.md` 和 `QA.md`。

## 6. 清理

在三个服务终端分别按 `Ctrl-C`。本演示不写业务数据库，不需要清理发票数据。
