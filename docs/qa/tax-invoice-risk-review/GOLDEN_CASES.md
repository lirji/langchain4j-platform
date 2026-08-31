# 财税 GoldenCase 报告

## 数据集

基线文件：`eval-service/src/main/resources/eval/baselines/tax-invoice-risk.json`。全部数据均为虚构、脱敏测试数据，断言稳定 JSON 字段，不断言随机 `reviewId` 或模型措辞。

| Case | 输入特征 | 关键断言 | 结果 |
| --- | --- | --- | --- |
| `tax-clear-consistent-invoice` | 金额、税额、期间一致 | `CLEAR`、0 finding | 通过 |
| `tax-high-amount-mismatch` | 税额和价税合计错误 | `HIGH`、2 findings、稳定代码顺序 | 通过 |
| `tax-medium-outside-period` | 开票日期跨税期 | `MEDIUM`、`OUTSIDE_TAX_PERIOD` | 通过 |
| `tax-high-duplicate-invoice` | 同批次代码和号码重复 | `HIGH`、`DUPLICATE_INVOICE` | 通过 |

## 静态加载验证

执行：

```bash
mvn -pl tax-service,eval-service -am test
```

结果：`eval-service` 65/65、`tax-service` 21/21，0 failure、0 error、0 skipped；加载测试确认 4 条用例和确定性断言。

## 实际 HTTP 运行

环境：Java 21，本地主机临时端口 Tax 18094、Edge 18080、Eval 18089；AI/RAG 关闭，Eval Judge/Embedding 关闭。调用链为 Eval → Edge → Tax，使用最小权限 `tax-review` 本地演示 key。

请求：

```bash
curl -H 'Content-Type: application/json' \
  --data '{}' \
  http://127.0.0.1:18089/eval/suites/tax-invoice-risk/run
```

实际证据：

- HTTP 202。
- `suiteName=tax-invoice-risk`。
- `total=4`、`passed=4`、`passRate=1.0`。
- 四个 Case 的目标 HTTP 状态均为 200，JSON Path oracle 均命中。
- 本次运行标识：`ef094f21-293d-4e53-9ebb-c311015887c6`。

## 边界

GoldenCase 证明规则输出和路由链路的可重复性，不证明发票真伪、抵扣资格、法规时效性或模型专业意见质量。
