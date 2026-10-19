# KDP-06-E：其他已注册格式验收

接续 KDP-06-D；按原计划补 DOC/XLS/PPT/PPTX/HTML/RTF/ODT 与文本编码的真实格式样本，不把 MIME 注册当作验收。

- 所有内容自造，含中英正文、关键编号/前导零、表格或幻灯片顺序。DOC 使用仓库自有 TXT 经 macOS textutil 生成的 OLE 二进制（提交夹具并记录生成方式，Linux CI 无该工具依赖）；其余尽量由现有 POI/ZIP/text API 生成。
- 正常文件验证内容、顺序、告警、原文切分覆盖及 fork 与临时目录清理；异常覆盖真实容器截断和文本预算，不能以“没异常”代替内容断言。
- 旧 XLS 若与现有 WorkbookFactory 兼容，复用已验证的行组/表头路径；其他格式仍走受限 Tika，准确标记结构不可用，不伪造 DOC 页码、PPT bbox 或复杂 HTML 去噪。
- 不增加压缩包/音视频等新输入范围，不执行宏/公式/HTML 脚本，不承诺嵌入对象、修订、密码保护、复杂图表或任意编码变体。
- 通过后提升支持矩阵的样本证据等级，记录完整回归与最终交付；不部署、不重建索引、不清业务数据。

## 实现与验证

- 旧 XLS 复用 `WorkbookFactory` 结构路径，100/100 行能同块携带表头，保留工作表和格式化前导零；不执行公式或外链。
- 其余格式继续有界 Tika 正文路径；`source_format` 依据实际 MIME 输出 doc/ppt/pptx/html/rtf/odt，结构不可用告警保留。处理版本升为 `knowledge-structured-v5`，已绑定快照继续恢复原结果。
- 61 项聚焦通过：新增 23 项覆盖 10 个正常格式/编码场景、五类真实容器截断、正文超限整份失败；同时回归结构解析与准备快照。DOC 的作者/编辑者/公司已核查为 Synthetic fixture/Synthetic fixture/Test，OLE 条目只有正文、表与摘要，无宏或附件目录。
- `python3 deploy/test-parsing-additional.py` 对最终 Boot jar 的 10 场景通过，禁网/只读/UID10001/3GiB/2CPU/256pids/512MiB tmpfs，退出无解析临时文件残留。[固定报告](KDP-06-E-formats.json)记录 jar 摘要与实际值；容器整体峰值 420552704 字节，单份 1.076～3.152 秒，含启动开销且与本地回归同时运行，不当作 SLA 或吞吐结论。
- 原失败日志保留：`kdp06e-baseline.log`（XLS 退回平铺、RTF 极简夹具无段落结束），`kdp06e-formats.log`（工作表名应校验 metadata 而非每块正文）。RTF 改用完整字体/段落夹具；不宣称修改了 Tika 的缺段落处理。最终聚焦 `kdp06e-formats-final.log`，完整回归最终结果见 TEST_RESULT。

## 保留边界

已验证样本不覆盖复杂排版、全部编码、修订、备注、图表或嵌入对象；RTF 缺段落结束可能 EMPTY。PPT 没有结构化页码，HTML 没有 DOM 映射/业务正文去噪；max-pages 仍是 PDF 页数限制，不冒充各 Office 的页数预算。未新增依赖、未调用收费模型、未部署业务服务；复杂语义质量与目标环境混合负载仍需后续验收。
