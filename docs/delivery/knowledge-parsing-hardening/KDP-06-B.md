# KDP-06-B：结构化解析与格式切分

基线 `813c699`，沿已批准排期继续。状态以 [PROGRESS_STATE](PROGRESS_STATE.md) 为准。

## 目标与取舍

- 独立解析进程输出有界、版本化结构数据，明确格式、正文块、表格单元格、页/行/段落定位及完整性告警；进程间不传 LangChain4j/POI 对象。
- CSV/XLSX 保留行列、空单元格、前导零、工作表名；每个行组重复对应表头，长行分段带定位，宽表按列组处理；首非空行假定表头须有明确告警，公式只读取缓存不执行外链，合并/隐藏等不能静默忽略。
- Markdown 保留 CommonMark 源片段及行号，代码围栏内伪标题不切章；Word 按正文段落/表格原顺序，保留样式标题层级与段落定位，未处理的修订/图像/嵌入内容可见告警。
- PDF 按页提取并记录页码、旋转与无文本页；只做可证实的页级定位，不编造坐标/复杂多栏阅读顺序或跨页表格结构。OCR 由 06-C 接续。
- 所有格式遵守输入/文字/块数/页数预算，超限整份失败；结构信息在 sync/async 入库、快照、向量/ES metadata 中保持。原文与模型增强前缀分离，引用不把生成内容当作原文。
- 保留旧 `extract` 和 JSON 纯文本调用兼容；当前没有实际目录 SPI 加载调用，不新增目录功能。处理版本提升，新生成使用新版本，已绑定快照不重算。

## 验收

- 原 17 场景内容标记不退化；CSV/XLSX 100 行表头同块率从 16% 提升到 100%，每行可定位、不丢空列/多行单元格/前导零；大/宽/无表头样本有明确策略。
- MD/DOCX 章节不串、代码不误切；PDF 页码与实际页一一对应，混合 PDF 缺文页可见告警。
- chars/tokens 两种尺寸预算、唯一 chunk ID、tenant/doc/version metadata；同步与异步调用链测试，fork JSON 大小/版本/校验保护及 Boot artifact 验证。
- 不把内容覆盖、结构/定位指标当作真实用户检索 Recall 或 OCR 准确率；单元/集成/语料与未覆盖项分别记录。


## 已实现与验证

- `ParsedDocument` schema 1 独立 JSON 协议；CSV 直接按逻辑记录解析，XLSX/Word 使用现有 POI，PDF 使用现有 PDFBox 单遍分页，Markdown 使用 CommonMark 源位置。其余格式继续受限 Tika 并带结构缺失告警，无新增解析库依赖。
- CSV/XLSX 100 行表头同块率均从 16/100 变为 **100/100**；各生成 7 个带行/列来源的分块。17 场景内容标记及错误分类保留，13 个有效场景全部有来源定位；固定报告 [KDP-06-B-structure.json](KDP-06-B-structure.json)。这不是用户检索 Recall。
- 长行先按列收窄，首列在列组中保留；单个长单元格才按 chars/tokens 最终预算分段，记录 `source_row_fragment`，不宣称分段是独立完整 CSV 记录。无法容纳的超长表头明确失败。默认 `max-structure-units=100000`（块/行/单元格总和）、`max-chunks=20000`，不静默截断。
- Word 支持常见 Heading 样式名、自定义样式继承与直接 outlineLvl；正文表格顺序和段落来源保留，未解析的辅助内容/列表编号/修订有告警。复杂嵌套表格/修订裁决、自动编号精确还原不在已验证能力内。PDF 保留页号/旋转，无文本页可见告警，默认阅读顺序标为未经复杂版面核验。
- 同步 multipart 与可靠异步任务均使用结构切分；原 `extract` 和 JSON 纯正文入口保留。无实际目录 SPI 调用，不为旧注释新建入口。parent-child 在不同结构块内的局部 parent 编号增加块前缀，防止引用误去重。
- 最终块保留定位到准备快照/向量/镜像；ES 用 `sourceMetadata` 对象（`enabled=false`）存储原文与来源，公开检索 DTO `KnowledgeHit.sourceMetadata` 只暴露固定来源白名单。tenant/doc/version/visibility 仍来自原可信字段，不由扩展 metadata 覆盖。旧响应缺失此字段时为空；构造器保持兼容。
- Contextual 增强保留 `source_text` 与 `context_prefix`；生成前缀参与原有检索文本，源引用与 API 正文使用原文。原始块位置继续保留，避免模型生成句子被当作源文证据。
- 处理版本缺省改为 `knowledge-structured-v2`；不要求旧快照的处理版本等于当前配置，已绑定结果不重算。历史索引不回填、不重建。
- 973 项跨 knowledge/conversation/agent 及依赖回归通过，16 项外部依赖跳过；后续 ES 权限优化再补 14 项通过；超长代码保留空白修正后 34 项通过。真实 ES 随机索引验证旧 mapping 加法扩展、重复写入、来源回读、融合及租户隔离，已清理测试索引。
- 新 Boot artifact 的四类结构结果传输通过；Linux 非 root/只读/禁网/资源受限四格式通过，任务目录清空。单次冷解析 1.16～1.39 秒，整容器 cgroup 峰值 340484096 字节；样本与测量噪声不足以宣称比上一版本提速或降低生产峰值。
- 差异自审另发现停机主动杀解析进程被归为不可重试崩溃，已改为可恢复 unavailable，真实关闭专项通过。独立进程异常崩溃仍人工处理，避免自动重试放大资源消耗。

## ES 发布兼容

对已有索引只做字段加法扩展；生产可由发布身份预建 `sourceMetadata: {type: object, enabled: false}`，worker 已存在字段时只读检查；未预建则需要 mapping 管理权限。不兼容的既有字段类型明确失败，不能原地强改。原文检索正文仍在 `text`；扩展来源只从 `_source` 回读、不建动态字段，避免 mapping 膨胀。没有执行生产 mapping 或重建。

依据：[Elasticsearch enabled](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/enabled)、[Update mapping API](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/indices-put-mapping.html)。本仓库真实 ES 测试另提供行为证据。
