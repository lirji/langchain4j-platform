# Java 代码图报告

## 命令

```bash
bash tools/java-codegraph/test/run-tests.sh
java tools/java-codegraph/CodeGraphCli.java build --root . --output /tmp/tax-codegraph-final-20260831.json
java tools/java-codegraph/CodeGraphCli.java query --root . --symbol com.lrj.platform.tax.TaxInvoiceReviewService
```

## 最终结果

- Fixture：通过。
- 发现文件：1,079。
- 成功解析：1,079。
- 失败：0。
- 节点：7,405。
- 边：63,611。
- 内容摘要：`sha256:e702e9035d1bf74733cc3ad636799c9d8a420f9b59c2c226ebb80341d0123971`。

## 财税符号查询

| 符号 | 状态 | 出边/入边 | 关联测试 |
| --- | --- | --- | --- |
| `TaxInvoiceReviewController` | found | 11 / 0 | `TaxInvoiceReviewControllerTest` |
| `TaxInvoiceReviewService` | found | 21 / 3 | `TaxInvoiceReviewServiceTest`、`TaxServiceApplicationTest` |
| `TaxInvoiceRuleEngine` | found | 19 / 3 | `TaxInvoiceRuleEngineTest` |
| `AiTaxNarrator` | found | 19 / 0 | `AiTaxNarratorTest` |
| `HttpTaxKnowledgeClient` | found | 16 / 0 | `HttpTaxKnowledgeClientTest` |

代码图可定位 Controller → 编排服务 → 校验/规则/知识/叙述/审计的结构关系，并将核心类型关联到测试。输出文件采用禁止覆盖策略；已存在目标会明确失败，需使用新路径。

## 证据强度限制

代码图是 JDK Compiler Tree API 生成的静态导航证据。`resolved` 边表示解析到的源码声明，`syntactic` 边仅表示语法证据；它不宣称覆盖 Spring AOP、反射、条件 Bean 的运行时分派或网关动态路由。
