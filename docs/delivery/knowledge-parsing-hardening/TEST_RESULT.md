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

## 格式范围核对与规划文档补充

2026-10-19，基于 `d135d6c`，本轮只更新格式能力及下一阶段计划，没有修改 Java、测试、依赖、配置或运行环境；沿用上面的源码回归证据。

使用 `DocumentFormatExtractionTest` 的 Surefire XML 中 `java.class.path` 启动临时 Java 探针，调用 `new AutoDetectParser().getSupportedTypes(new ParseContext())`。以下 12 个 MIME 均返回已注册：

```text
application/pdf
application/msword
application/vnd.openxmlformats-officedocument.wordprocessingml.document
application/vnd.ms-excel
application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
application/vnd.ms-powerpoint
application/vnd.openxmlformats-officedocument.presentationml.presentation
text/plain
text/csv
text/html
application/rtf
application/vnd.oasis.opendocument.text
```

Markdown 另以已有文本提取和语法分块测试作为证据，不把 MIME 注册探针当作真实格式解析测试。按格式的能力和未验证项集中在[文档支持范围](../../对话与检索/知识库文档支持范围.md)。下一阶段的优先级和验收是规划，尚未执行这些专项。

本次文档校验：7 个 Markdown 文件，102 个本地链接及锚点有效，`git diff --check` 通过；24 个源码/配置/测试文件的指纹仍与交付指纹一致，未重复运行 Maven。

## KDP-04 持久化准备结果（2026-10-19）

基线 `a63a167`。先复现再修复；在原项目任务分支实施。没有执行线上迁移、真实模型或生产部署。

| 验证 | 结果 | 日志/证据 |
| --- | --- | --- |
| 旧 worker 失败回归 | 4 项、1 项预期失败；VECTOR generation-1 / ES generation-2 | `kdp04/baseline.log` |
| 聚焦入库回归 | 44 项、0 失败/错误/跳过 | `kdp04/focused.log` |
| 真实 MySQL 8.4 + MinIO | 5 项通过；重建 JDBC/S3 adapter 与 worker、处理版本变化、引用不可替换、租户隔离 | `python3 deploy/test-ingestion-prepared.py`；`kdp04/mysql-s3.log` |
| 最终模块及上游完整回归 | 613 项、0 失败/错误、9 跳过，604 项实际执行；knowledge-service 433 项、5 跳过 | `mvn -B -o -pl knowledge-service -am test`；`kdp04/full-regression.log` |
| Compose/差异静态校验 | PASS；未发布 IAM 策略 | `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.knowledge-split.yml config -q`；`git diff --check` |

首次真实集成运行因前一 Mapper 用例残留任务使 reconcile 计数为 2 而失败；修正随机测试 schema 的夹具清理后重跑通过，原日志保留为 `kdp04/mysql-s3-fixture-failure.log`。只创建/清理本轮随机 schema、账号和已知测试对象，未改业务数据。

新增验收映射：`ObjectStorageIngestionPreparedStoreTest` 覆盖精确类型、字节预算、hash/上下文/schema 校验；`PreparedIngestionRecoveryTest` 覆盖旧任务、绑定对象不可用、写对象后失租、CAS 响应丢失；`PreparedIngestionPersistenceTest` 以 H2/内存及真实 MySQL/S3 验证部分成功后的恢复；`IngestionPreparedConfigTest` 覆盖角色装配与配置绑定；VECTOR 测试确认不使用变更后的当前模型维度。

源码证据清单为 `kdp04/validated-files.json`，33 个文件，SHA-256 `ee2b8b68fc0086dc14718c670f98a73ac33b466f637d98f96e4f94e9e7926382`。完整回归后仅补文档，没有修改可执行代码。外部 Redis/IAM 9 项仍跳过；没有以适配器重建冒充真实服务器断电恢复、没有真实 ES/向量写入与资源压力验证，后续依赖 KDP-05/06。

## KDP-06-A 格式基线与 CSV 兼容修复

17 个场景及度量口径详见 [KDP-06-A](KDP-06-A.md) 与 [固定报告](KDP-06-A-baseline.json)。100 行 CSV 首次复现 `NoClassDefFoundError: UnsynchronizedBufferedReader`；仅 knowledge-service 直接依赖 Commons IO 2.20.0 后通过。完整回归 630 项，0 失败/错误，9 跳过；621 项实际执行。日志 `corpus-dependency-failure.log` 和 `corpus-regression.log`。KDP-04 MySQL/S3 适配器协议未变，没有重复清空/迁移共享环境。
