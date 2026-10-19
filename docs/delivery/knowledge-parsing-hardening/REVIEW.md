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

- **首批交付时的 P1 / KP-09 风险（KDP-04 已另行修复，见下节）**：`knowledge-service/src/main/java/com/lrj/platform/knowledge/ingest/job/IngestionJobWorker.java:83` 重试重新准备，后续只跑未完成 sink；配置、模型或降级变化可能造成结果不一致。升级本轮切分规则前排空/隔离旧部分成功任务；后续 KDP-04 固定派生结果与处理版本。当前不在未验证的情况下重构持久化协议。
- **P1 / KP-07/08，部分缓解**：正文上限只限制收集文本，不能终止卡死解析、OCR 或限制所有中间对象。KDP-05 仍需进程隔离、硬 deadline 和资源上限验证。
- **兼容性**：新的默认 2,000,000 字符预算会拒绝先前可能接受的超大正文；支持显式配置。Markdown 分块和非标题 section metadata 有预期变化，历史索引不会自动修复；详见[可靠入库接入](../../对话与检索/可靠入库接入.md)。
- **质量边界**：测试涵盖主要格式的小样本抽取，不证明表格结构、多栏阅读顺序、旧 Office、OCR 或检索质量。源文件复制减少有代码证据，没有吞吐/内存压测数字。
- **远程供应链**：基线 main 的依赖 SBOM 扫描已失败，当前没有全绿远程发布门禁证据；保留该事实，不将本地测试 PASS 等同于生产可发布。

## KDP-04 差异自审（2026-10-19）

以 `a63a167` 为基线检查实际代码、Mapper、V9、S3 前缀和部署策略。由主 Agent 自审，不称独立评审。

- 绑定顺序为准备、写对象、读回校验、租约/revision CAS、sink。失租与提交响应丢失测试证明旧执行者不开始副作用；未知写入结果保留对象，避免错误清理已提交引用。
- 快照 DTO 不依赖框架序列化；数值/UUID metadata 保留类型，向量有限且同维，身份及原文 hash 校验，读写字节预算均生效；临时读取错误不触发重新生成。
- 任务状态机、withExecution、withRevision、JDBC 和内存存储保留引用；已绑定引用不可替换。默认 H2 与真实 MySQL/S3 均验证了重建 store/worker 后的恢复。
- 排查发现 split worker 原只有 S3 GetObject，已补准备前缀 PutObject，原文不新增写权限；ingest-api 禁止写/删准备前缀。Compose 配置静态校验通过，未向真实 IAM 发布此策略。
- 旧任务 NULL 准备标记采用保守接管，禁止新旧 worker 混跑；这是一项显式升级边界，不能声称任意旧 worker 滚动兼容。
- 保留限制：派生 JSON 序列化/读取有预算但仍会在内存驻留；解析硬隔离和阶段限时在 KDP-05。快照固定文本/向量，不承诺图谱模型内部推理结果确定性；物理清理与对象保留策略未自动化。

## KDP-06-A 自审

原生格式由 PDFBox/POI 生成，文本/错误期望独立于 parser 输出；不把失败数据标为通过。100 行 CSV 回归证明真实分支缺类，版本对齐只作用于 knowledge-service，完整模块及上游测试覆盖该新类路径。表头、定位和资源数据只作为实测基线，不声称结构正确或生产 SLA。没有真实模型、OCR、生产语料或上线操作。


## KDP-05-A 差异自审

从 `f5c64e1` 核对实际差异及调用链，主 Agent 自审，非独立审查。未发现阻塞本项交付的新缺陷。

- `ForkedDocumentParser` 固定 Java executable/main/参数列表，不经 shell；环境清空，不把 token、业务环境或 JAVA_TOOL_OPTIONS 传入解析子进程。目录每任务独有，结果无跟随符号链接读取且有大小/字符上限。
- `ParsingProcessMain` 不启动 Spring，父进程失活时自行退出；进程崩溃、无输出、非法编码/错误码 fail closed。超时后强制终止且确认退出再回收；无法确认退出不释放额度。
- `DocumentTextExtractor` 禁止隐式 OCR 与递归附件；生产 worker/combined 禁用 in-process 配置。已绑定准备结果仍直接读取，解析升级不导致重试重算。
- 审查发现“不可自动重试”误等同于“HTTP 输入错误”，已将进程故障的 503 分类与重试策略分开，补测试通过。
- 保留范围：目录 SPI 尚未接入统一提取器；fork 不构成文件/网络安全沙箱，容器资源配置须在目标部署真正启用；输入读取非硬可终止。临时文件监测有短暂超调，容器 tmpfs 提供硬边界。模型/批次预算和租户公平性由 05-B 承接。
- 远程 `f5c64e1` AgentScope CI 通过，Java 供应链仍在 `Scan aggregate dependency SBOM` 失败；没有逐项比对漏洞归因，不将相同失败阶段当作同一原因，未绕过生产发布门禁。


## KDP-06-B 差异自审

主 Agent 根据实际 diff 与 sync/async/ES/检索/API 可达链路自审，非独立审查。

- 固定结构 schema、结果字节/字符/单位/块数双侧校验，源文件不携带租户 identity；元数据拷贝保持原租户、doc、版本，公共 DTO 只传来源白名单。
- 真实 CSV 保留引号内换行/空列/前导零，宽长行先按列分组再做单元格片段；同一行可通过 source_row/columns 回查。首非空行作表头是显式保守假设，不伪装成模型判定。
- Markdown 源 span 保留代码围栏；Word 用正文顺序与真实段落/表格编号；PDF 单遍提取并定位页，空文本页留质量告警；复杂阅读顺序和未处理辅助结构不宣称准确还原。
- 修正三条实际断点：同步/异步展平丢来源、ES 丢 metadata、Contextual 前缀被当原文引用。追加 sourceMetadata 协议字段保持旧构造/旧响应兼容，融合与租户过滤回归通过；不允许扩展来源覆盖身份。
- parent-child 在每个结构块局部编号，已增加块命名空间并测试，避免不同页 parent 0 错误去重。顺便修复主动停机解析误进永久人工处置，关闭测试验证可恢复失败。
- ES 只有来源扩展字段加法映射，预建后不再要求 worker 修改 mapping；不兼容字段 fail closed，不自动迁移业务索引。真实随机 ES 索引测试通过并清理。
- 当前仍有原文/结构/全量向量驻留、多租户调度与模型预算问题，由 KDP-05-B 接续。未解决扫描 OCR 或任意复杂多栏/跨页表格；字段存在不能代替真实语料正确率评估。

- 最终检查补齐超长代码路径：跳过通用 splitter 的 trim/空白规整，专用片段在 chars/tokens 下拼接可逐字符还原；34 项后补验证通过。

## KDP-05-B1 流式与批次自审

基线 `1b5b3b7`，主 Agent 检查实际 diff 和调用路径。multipart 不再构造大 byte[]；hash/幂等指纹/owner/版本规则保留，S3 从可重开私有文件发送，异常与重放退出清理本地文件，不删除未知数据库结果可能引用的来源对象。

整文 token/字节预检查发生在首次模型调用前；数量/维度/有限浮点与向量总预算核验后才能固化准备结果。索引批次使用全文 index 生成稳定 ID；变化批次大小不会改变主键。图谱可靠路径绕过异步排队并等待真实写入，失租回调不会被可选增强容错吞掉。失租只停止后续调用，不能撤销在途网络效果。

保留限制：实例暂存额度不覆盖 multipart 接收前的请求数量；没有恒定内存或真实吞吐证明；旧同步替换仍不具备版本原子可见性；跨副本额度和公平调度由 B2 补齐。完整快照重放与无模型本地重试已回归，不将此项等同于整份 KDP-05-B 完成。1b5b3b7 的 AgentScope CI 成功，供应链仍在 SBOM 扫描失败，后续发布作业跳过。
