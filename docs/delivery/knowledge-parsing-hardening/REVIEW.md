# 知识库解析修复差异审查

2026-10-19；基线 `348c13e`，范围 KDP-01/02/03。依据仓库 `platform-diff-review`，由实现者审查实际 diff 与可达调用链；这是自审，不是独立审查。

结论：未发现阻塞本轮的新缺陷。本地验证及适用范围见 [TEST_RESULT](TEST_RESULT.md)。以下发布约束和未完成的生产能力仍保留，不将其标成已经修复。

## 排除的重要疑点

| 疑点 | 结论与依据 |
| --- | --- |
| 重编号覆盖租户、版本或父块关系 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/ChunkIndexing.java:18` 复制 metadata，仅覆盖最终 index；`ChunkIdentityTest` 验证字段与唯一 ES ID；同步和异步入口均兜底重编号 |
| CommonMark 识别后丢失原始代码/表格文本 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/MarkdownHeaderSplitter.java:75` 只取顶层 Heading 的 source span，按原串截取，不重新渲染；覆盖代码、CRLF、缩进和尾随空白；超长段仍沿用原递归兜底 |
| 损坏 PDF 被当作临时 I/O 无限重试 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentTextExtractor.java:75` 只将原始流 IOException 标记为 SOURCE_READ；真实损坏/加密 PDF 已验证；worker 继续使用有限重试 |
| 所有 IllegalArgumentException 都导致人工处置，读取故障也无法恢复 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionRetryPolicy.java:17` 优先读取明确解析类别，再兼容旧异常规则；五类失败路径均有回归 |
| 新上限静默截断并写入索引 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/lifecycle/DocumentTextExtractor.java:40` 为每次解析创建独立有界 handler；WriteLimitReachedException 转明确失败；不会返回截断字符串 |
| 省复制破坏输入不可变性或越权重放 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionSubmissionService.java:54` 只访问已防御性复制的私有快照，构造器/外部 accessor 保持克隆，owner/版本/幂等指纹校验未移除 |
| 解析异常进入响应头或上传流泄漏 | `knowledge-service/src/main/java/com/lrj/platform/knowledge/controller/DocumentController.java:72` try-with-resources；解析错误使用固定安全消息及稳定码，400/503 均有对应测试 |

## 仍需处理的生产事项

- **P1 / KP-09，既有风险**：`knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionJobWorker.java:83` 重试重新准备，后续只跑未完成 sink；配置、模型或降级变化可能造成结果不一致。升级本轮切分规则前排空/隔离旧部分成功任务；后续 KDP-04 固定派生结果与处理版本。当前不在未验证的情况下重构持久化协议。
- **P1 / KP-07/08，部分缓解**：正文上限只限制收集文本，不能终止卡死解析、OCR 或限制所有中间对象。KDP-05 仍需进程隔离、硬 deadline 和资源上限验证。
- **兼容性**：新的默认 2,000,000 字符预算会拒绝先前可能接受的超大正文；支持显式配置。Markdown 分块和非标题 section metadata 有预期变化，历史索引不会自动修复；详见[可靠入库接入](../../对话与检索/可靠入库接入.md)。
- **质量边界**：测试涵盖主要格式的小样本抽取，不证明表格结构、多栏阅读顺序、旧 Office、OCR 或检索质量。源文件复制减少有代码证据，没有吞吐/内存压测数字。
- **远程供应链**：基线 main 的依赖 SBOM 扫描已失败，当前没有全绿远程发布门禁证据；保留该事实，不将本地测试 PASS 等同于生产可发布。
