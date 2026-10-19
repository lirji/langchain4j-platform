# KDP-06-A：格式质量与资源基线

2026-10-19，基线代码 `6138ee4`。用户选择按通常企业资料场景实施；这些是无敏感信息的合成真实格式，不代表业务语料验收或线上性能。

## 可复现入口

```bash
mvn -B -o -pl knowledge-service -am -Dtest=DocumentParsingCorpusTest -Dsurefire.failIfNoSpecifiedTests=false test
```

语料生成及独立期望在 `knowledge-service/src/test/java/com/lrj/platform/knowledge/lifecycle/DocumentParsingCorpus.java`，版本 `kdp-corpus-v1`；输出的真实文件和逐项测量保存在 `knowledge-service/target/knowledge-parsing-hardening/corpus/`。生成的 PDF/Office 容器时间戳可能变化，比较应固定生成器/依赖版本和预期逻辑内容，不仅比较二进制 hash。[首次成功报告](KDP-06-A-baseline.json) 已入库供下一阶段对照。

| 主格式 | 样本范围 | 独立验收与当前限制 |
| --- | --- | --- |
| TXT/技术文字 | 中英、版本号/URL、前导零；1500 段长文 | 正文标记保留；尚无行定位 |
| Markdown/技术手册 | CRLF、标题、围栏代码中的假标题、列表、Setext、20 个长章节 | 保留代码与章节标记；当前默认递归切分不提供章节定位 |
| CSV | BOM、引号逗号、单元格换行、100 行表格 | 行标记全部保留；100 行中仅 16 行与表头同块 |
| Excel XLSX | 两张工作表、字符串编号、数字；100 行 | 关键值/行标记全部保留；100 行中仅 16 行与表头同块；sheet/cell 坐标尚无 |
| Word DOCX | 标题样式、双语正文、表格；200 段长文 | 正文/表格标记保留；尚无段落/单元格定位 |
| PDF | 2 页、30 页原生英文、文字+图片混合页、加密、损坏 | 原生页标记保留；混合图片页无法自动证明完整，尚无页定位或覆盖告警；未声称中文扫描 OCR 通过 |
| 通用异常 | 空输入、超过 2,000,000 字符 | 返回稳定错误码，不返回截断正文 |

合计 17 个场景。内容标记召回仅度量预设正文片段是否存在；**不是检索 Recall、答案正确率或完整文本比对**。表头同块率是关系保留的粗略指标，不证明每个值与其列完全正确。定位/章节 metadata 为 0 的结果明确记录为待改进，不把它们设为“通过”的虚假门禁。

## 发现并修复的依赖缺陷

100 行 CSV 触发 Tika `TextAndCSVParser` 后缺少 `UnsynchronizedBufferedReader`。实际运行类路径为 Commons CSV 1.14.1 + Commons IO 2.16.1；旧的小型 CSV 样本走了其他文本检测分支，未覆盖该路径。仅在 knowledge-service 显式选择 Commons IO 2.20.0，与本地 Tika 3.2.3 POM 和 [CSV 1.14.1 官方发布说明](https://commons.apache.org/proper/commons-csv/changes.html) 的依赖要求对齐；Apache-2.0 许可证，未扩大为全仓依赖升级。[官方 IO 安全报告](https://commons.apache.org/proper/commons-io/security.html) 列出的 CVE-2024-47554 影响 2.14.0 之前版本；此核对不代替全仓供应链扫描。

原失败日志为 `corpus-dependency-failure.log`。修复后 17 场景全部通过，完整 `mvn -B -o -pl knowledge-service -am test` 为 630 项、0 失败/错误、9 跳过；日志 `corpus-regression.log`。

## 测量与下一阶段门禁

- 固定实验策略 `recursive/chars/300/50`，无模型、增强或检索调用。记录输入字节、正文字符、块数、实际错误、标记缺失、同块表头、定位和章节 metadata。
- 纳秒耗时包含本 JVM 当时的首次初始化/缓存状态；heapBefore/heapAfter 是堆快照，不是峰值 RSS，也不能用差值承诺内存预算。每场景仅一次，不计算伪 P95/P99。硬资源隔离与混合负载在 KDP-05 单独验证。
- KDP-06-B 的最小改进要求：CSV/XLSX 100 行无丢行、每个行组带表头，坐标能回查；Markdown/Word 章节与代码边界可追溯；PDF 至少提供页号及图片页缺少文本的告警。保持现有正文与异常门禁。
- 真实查询评测复用 [检索质量闭环](../../对话与检索/retrieval-quality-loop.md) 的文档级 Recall/Precision/MRR/Hit，使用实际标注与授权上下文；本基线不创建伪造的线上评测结果。OCR 后续采用生成的中英、旋转/混合页样本验证，再按业务语料校准。
