# 知识库解析修复验证

日期：2026-10-19。范围：KDP-01/02/03，基线 `348c13e`。结果：本轮本地验证 PASS；不表示 OCR、复杂版面质量或线上性能通过。

## 可复现命令与结果

```bash
mvn -B -o -pl knowledge-service -am test
git diff --check
```

首次构建若本机未缓存新增 CommonMark 0.25.1，去掉 `-o` 获取依赖后再运行。最终完整回归于 2026-10-19 09:40:58 +08:00 成功，耗时 28.545 秒；这是测试耗时，不是解析吞吐基准。

| 验证 | 结果 | 本地日志 |
| --- | --- | --- |
| 修复前聚焦复现 | 29 项、10 个预期失败、无错误；证明 3 个编号、5 个 Markdown、2 个英文边界问题 | `baseline.log` |
| 分块聚焦 | 当时版本 47 项通过 | `chunk-fixes.log` |
| 解析聚焦 | 当时版本 28 项通过 | `parsing-fixes.log` |
| 最终 knowledge-service 及所需上游回归 | 600 项、0 失败、0 错误、9 跳过；591 项实际执行 | `final-regression.log` |
| 最终 knowledge-service 子集 | 420 项、0 失败、0 错误、5 跳过 | 同上 |
| 差异格式检查 | PASS | `git diff --check` |

日志根目录：`knowledge-service/target/knowledge-parsing-hardening/`，未提交冗长日志。中间完整回归 `full-regression.log` 为 598 项，最终增加配置绑定测试后的 600 项才是交付依据。

## 验收映射

| 验收 | 证据 |
| --- | --- |
| 多章节、多个父块、多个语义组递归兜底后 ID 唯一 | `ChunkIdentityTest`：最终 index 连续，实际 `EsSegmentDocument.id()` 不重复，租户/版本/父块内容保留 |
| Markdown 真实边界与原文 | `MarkdownHeaderSplitterTest`：反引号/波浪号围栏、未闭合围栏、缩进代码、HTML 注释、混合 H1/H2、Setext、格式化标题、CRLF、行末空白、同父级合并 |
| 英文切句 | `SemanticChunkingSplitterTest`：英文句号、网址、版本、小数、缩写、姓名首字母、结束引号/括号；既有中文场景回归 |
| 真实格式可抽取 | `DocumentFormatExtractionTest`：真实 PDF、含中文段落与表格 DOCX、带表头/前导零字符串/数值 XLSX、带引号中文 CSV，断言关键内容 |
| 坏输入与安全提示 | 上述格式测试及 `DocumentTextExtractorTest`：加密 PDF、损坏 PDF、空输入、正文超限、临时 I/O 分类，安全消息不带文件名/内部路径 |
| 预算实际绑定 | `DocumentParsingConfigTest`：Spring 属性绑定生效，无效非正预算拒绝启动 |
| 失败恢复与资源 | `IngestionRecoveryTest`：五类错误对应人工处置/有界重试且不执行 sink；`DocumentControllerTest`：成功与错误都关闭流，临时读取失败 503 和稳定错误码 |
| 少复制仍隔离调用方修改 | `IngestionSubmissionServiceTest`：构造与导出数组修改不改变私有快照，既有幂等、用户权限及版本回归通过 |

## 未覆盖项

- 9 项跳过：上游 `RedisBudgetLedgerIntegrationTest` 4 项；本模块 `RegistryGcRedisIntegrationTest` 1 项、`KnowledgeAuthzIntegrationTest` 3 项、`DevInfraRedisIntegrationTest` 1 项。缺外部集成环境，未宣称通过。
- 未连接真实 ES/向量库或调用计费模型；ES ID 是实际生成算法的离线验证，非真实 bulk 写入测试。
- PDF 为小型原生文本样本；Word/Excel 为 DOCX/XLSX，不含旧 DOC/XLS、扫描件、复杂多栏/跨页表、公式/外部链接、OCR 或页级覆盖率验证。
- 正文上限验证不代表压缩炸弹、硬超时、进程隔离、峰值内存或恶意文档安全验证；这些属于 KDP-05。
- 重试一致性、结构化定位、检索准确度与吞吐基准属于 KDP-04/06，未以测试数量替代专项质量结论。

## 版本对应

`validated-files.json` 列出 24 个文件，按路径排序对 `path + NUL + bytes + NUL` 累计 SHA-256。

- 最终回归指纹：`f0f3e2f5e30d625f2693b30133368895a3548b8fe3112f4e3d55e752e8851fcf`。
- 交付指纹：`adf6c1105582a44a23316405f2433a80c41abf2005558ed02390aa9ab710aa4f`。
- 唯一源文件差异是 `DocumentTextExtractor` 两行 Javadoc，准确说明文件名日志清洗及已验证的 400/503；反向替换后指纹与回归输入一致。没有可执行代码变化，无需重复同一回归。
