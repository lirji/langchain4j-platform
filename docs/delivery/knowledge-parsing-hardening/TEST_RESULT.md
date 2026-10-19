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


## KDP-05-A 独立解析进程（2026-10-19）

- `mvn -B -o -pl knowledge-service -am -DskipTests package` 成功；随后 `mvn -B -o -pl knowledge-service -am -Dparsing.test.artifact=$PWD/knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar test` 成功：638 项，0 失败/错误，9 跳过，实际执行 629 项。知识库 458 项、5 跳过。日志 `kdp05a/package.log`、`kdp05a/full-regression.log`。
- 17 场景真实 fork、崩溃/卡死/堆/输出/磁盘超限、单进程饱和、关闭后拒绝、流读取错误及临时目录清理通过。最初环境允许列表测试未考虑 macOS JVM 自动补充编码键，已只允许观察到的该平台键并重新验证；原始日志 `focused-env-failure.log` 保留。
- Spring Boot fat jar 的嵌套类路径专项通过；默认未提供 artifact 属性时该测试会跳过，本次明确提供刚打包的 artifact，未跳过此项。
- 使用 knowledge-service/Dockerfile 构建本地 `lc4j-parser-check:kdp05a` 后，独立 Linux 容器以 `--network none --read-only --user 10001:10001 --memory 3g --cpus 2 --pids-limit 256 --tmpfs /tmp:size=512m,mode=1777` 运行四类真实文件，结果通过、临时目录清空；日志 `kdp05a/docker-build.log`、`kdp05a/container-smoke.log`。容器已移除，本地镜像留作证据，未部署业务服务。
- 单次四样本 cold fork 约 1.17～1.70 秒；整容器 cgroup `memory.peak=415653888`，上限 3221225472 字节、256 pids、2 CPU。未测试多租户吞吐或尾延迟，不作生产性能承诺。
- 最后分离 HTTP 503 与任务自动重试属性后，执行 `mvn -B -o -pl knowledge-service -am -Dtest=DocumentControllerTest,IngestionRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false test`，10 项通过。该修改未在之前容器镜像中，不影响已验证的子进程实现。测试 helper 在完整回归后单独 javac 编译并在容器执行。
- Compose `config --quiet` 与 `git diff --check` 通过。当前 16 个源码/配置/测试文件指纹 `d829a2130bd96287cf8f6c0e09305dc1e1a968c4d77d24e21194b57652e7c73e`，清单 `kdp05a/validated-files.json`。原始日志均位于 `knowledge-service/target/knowledge-parsing-hardening/`。
- 解析进程并非 RCE/网络安全沙箱；源流单次阻塞受源 SDK 超时约束；目录 SPI 及整条上传/embedding 预算继续处理。真实生产环境与 OCR 不在此项验收内。


## KDP-06-B 结构化解析与检索来源

- 初次专项发现无批注 DOCX 的 POI 返回 null，和宽行盲切打断字段标识；修复后 `kdp06b-focused.log` 30 项、`kdp06b-pipeline.log` 62 项通过。初始失败保留 `kdp06b-initial.log`。容器探针转换结构 API 时的编译失败保留 `kdp06b-es.log`，已修正，不能将其算成功测试。
- `mvn -B -o -pl knowledge-service -am -DskipTests package` 成功（`kdp06b-package.log`）；随后 `KDP_ES_TEST_URL=http://127.0.0.1:49200 mvn -B -o -pl knowledge-service,conversation-service,agent-service -am -Dparsing.test.artifact=$PWD/knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar test` 通过：**973 项，0 失败/错误、16 跳过，957 实际执行**。knowledge-service 490 项/5 跳过，conversation 172/7 跳过，agent 131/0 跳过。真实 Boot artifact 与 ES 专项在此命令明确开启；日志 `kdp06b-full-regression.log`。
- 全量后仅优化 ES 已预建来源字段时的权限要求；`KDP_ES_TEST_URL=http://127.0.0.1:49200 mvn -B -o -pl knowledge-service -am -Dtest=StructuredProvenanceEsTest,ElasticsearchEsGatewayTest,ElasticsearchSegmentIndexerTest -Dsurefire.failIfNoSpecifiedTests=false test` 14 项通过，`kdp06b-es-mapping-guard.log`。随机索引 `kdp06b-test-*` 只由测试创建/删除，退出后核查无残留。未操作业务索引。
- `lc4j-parser-check:kdp06b` 的 Linux smoke：四类结构结果通过，禁网、非 root、只读、3g 内存、2 CPU、256 pids、512m tmpfs；单次约 1.16～1.39 秒，整容器峰值 340484096 字节。日志 `kdp06b/docker-build.log`、`kdp06b/container-smoke.log`。镜像早于仅 ES 权限检查的最后修改，解析实现一致，不宣称该镜像是可直接发布的最终制品。
- 语料对比见 [结构报告](KDP-06-B-structure.json)：100 行 CSV/XLSX 均 100 行表头同块，原基线 16 行；13 个正常样本的所有块有来源 metadata。无在线检索 Recall、计费模型或 OCR 质量结论。
- 当前 40 个源码/配置/测试文件 SHA-256 指纹 `03fab3ed9656ad370f91fb12fb04ab85a9164cd90d87ee89a10a3f8976b6695a`，清单 `kdp06b/validated-files.json`；文档变动不改变可执行验证对象。
- Compose 组合重复 `/tmp` 已另作 `176d83b` 修复提交；四种 CI 组合静态检查与 runtime hardening gate 通过，远程 CI 待确认。源码专项与远程供应链门禁分别报告。

- 最终自审发现超长代码若交给通用 recursive 兜底会规整原文空白，已专用分段并测试 chars/tokens 下逐字符还原。`kdp06b-final-focused.log` 34 项通过，含结构语料、同步/异步、真实 ES、引用原文；最终指纹已更新。973 项是之前全回归数，未把后补 34 项简单叠加为不重复用例数。

## KDP-05-B1 流式输入与批次预算

- 流式输入/Submission/Controller/S3 单测 15 项通过（`kdp05b-stream-tests.log`）；批次首轮 29 项通过（`kdp05b-batch-tests.log`）。后补 19 项中 18 通过、1 个真实 S3 条件测试跳过（`kdp05b-replay-tests.log`），不能简单相加为去重数量。
- `python3 deploy/test-ingestion-prepared.py --evidence-dir kdp05b` 使用随机 MySQL schema/account 和只属于用例的 S3 对象，6 项全通过（`kdp05b/mysql-s3.log`），包含 5 MiB 流式源文件 SHA-256 往返与跨租户拒绝；完成后清理自有测试资源，不触及业务对象。测试脚本支持指定测试与证据子目录以保留既有结果。
- 四种 CI Compose 组合与 `bash deploy/test-runtime-hardening-config.sh` 通过（`kdp05b-compose.log`）；`mvn -B -o -pl knowledge-service -am -DskipTests package` 成功（`kdp05b-package.log`）。没有部署业务服务或调用真实计费模型。
- 首轮完整回归发现同步入口维度守卫回归，保留 `kdp05b-dimension-regression-failure.log`；恢复守卫后重跑 `KDP_ES_TEST_URL=http://127.0.0.1:49200 mvn -B -o -pl knowledge-service -am -Dparsing.test.artifact=$PWD/knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar test` 通过：**684 项、0 失败/错误、10 跳过，674 实际执行**。knowledge-service 504 项/6 跳过；真实 S3 条件测试已在独立集成命令执行通过。日志 `kdp05b-full-regression.log`。Boot artifact 是本轮打包版本，之后仅恢复同步入口维度守卫；该守卫由本轮源码测试验证，未宣称旧 jar 为最终发布制品。
- 最终源码/配置/测试指纹 `32c2c3b37035c86661bb42ab3f0eac1d5572de179316608dbef379ec8e9e52e0`，31 文件，清单 `kdp05b/validated-files.json`。B1 验收通过；B2 跨副本公平调度继续实施，整个 KDP-05-B 尚未完成。

## KDP-05-B2 公平准入与有界执行

- 首轮 H2/线程池 8 项通过（`kdp05b2-fair-tests.log`）；MySQL/S3 14 项通过（`kdp05b2/mysql-s3.log`），最后 V10 启动检查、资源释放和 TTL 空 scope 兼容后再次 MySQL/S3 14 项通过（`kdp05b2-final/mysql-s3.log`）。只使用脚本分配的随机 schema/account 与已知测试对象，退出已清理。
- `mvn -B -o -pl knowledge-service -am -DskipTests package` 成功（`kdp05b2-package.log`）；`KDP_ES_TEST_URL=http://127.0.0.1:49200 mvn -B -o -pl knowledge-service -am -Dparsing.test.artifact=$PWD/knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar test` 通过：**692 项，0 失败/错误，10 跳过，682 实际执行**（`kdp05b2-full-regression.log`）。knowledge-service 512 项/6 跳过；Boot/真实 ES 已启用，真实 S3 条件项另外执行。没有将源码补测内容错误算入先前 jar。
- 后补数据库池/握手/socket/SQL 超时，15 项聚焦测试通过（`kdp05b2-db-timeout-tests.log`），包括 TCP 已连接但无数据库握手的真实 socket 故障。增加 0 禁用超时的拒绝测试后，最终 MySQL/S3 与聚焦 22 项全部通过（`kdp05b2-db-final/mysql-s3.log`）；该次包含 SQL 超时和最终配置变更，之前 692 项总数没有加上后补用例。
- 最终源码/配置/测试指纹 `99a2a6aab4703cd3b45d68c10cc2089a1fe7e638c07fd18196e42eb0899e6df8`，18 文件，清单 `kdp05b2/validated-files.json`。B1/B2 验收通过，KDP-05-B 完成；未执行生产迁移或部署。

B2 远程补正：`54f8b1f` 的 AgentScope CI `39193808050` 在持久化静态边界失败：新增 `lockAdmission` 读取固定协调守卫，没有业务软删除字段。检查器现只允许该方法的精确 `SELECT GRANT_SEQUENCE, GLOBAL_LIMIT, TENANT_LIMIT ... WHERE GUARD_ID=1 FOR UPDATE`，未放宽普通业务查询或其他 Mapper；`python3 deploy/test-mybatis-boundary.py` 通过（`kdp05b2-boundary.log`）。供应链 CI `39193808043` 仍失败；源码和真实 MySQL 验证与此静态规则修正分开报告。


## KDP-06-C 选择性 OCR（2026-10-19）

- `mvn -B -o -pl knowledge-service -am -Dtest=PdfPageOcrTest,TesseractOcrEngineTest,StructuredDocumentParsingTest,ForkedDocumentParserTest -Dsurefire.failIfNoSpecifiedTests=false test`：43 项通过，`kdp06c-cleanup-regression.log`。初始 300ms 夹具未进入 native 就截止造成 PID 缺失，修为 2s；原失败 `kdp06c-initial-test-failure.log` 保留。
- package 后 `KDP_ES_TEST_URL=http://127.0.0.1:49200 mvn -B -o -pl knowledge-service -am -Dparsing.test.artifact=$PWD/knowledge-service/target/knowledge-service-0.1.0-SNAPSHOT.jar test`：**703 项、0 失败/错误、10 跳过，693 实际执行**；knowledge 523/6 跳过。真实 Boot 和 ES 已启用；`kdp06c-full-regression.log`。未重复操作业务数据或全量迁移。
- 完整回归后仅增加准备 profile/版本标记和更保守的整行按次数去重：`IngestionPreparedConfigTest,ObjectStorageIngestionPreparedStoreTest,PreparedIngestionRecoveryTest` 11 项通过（`kdp06c-snapshot-regression.log`）；`PdfPageOcrTest,TesseractOcrEngineTest` 9 项通过（`kdp06c-merge-regression.log`）。之后重新 package，最终 jar 用于 Linux 验收。
- `python3 deploy/test-parsing-ocr.py` 通过：构建 OCR target、引擎与语言检查、7 个真实 PDF 场景、临时目录无残留；Tesseract 5.5.0，chi_sim/eng/osd 4.1.0，禁网/只读/UID10001/3GiB/2CPU/256pids/512MiB tmpfs。固定报告 `KDP-06-C-ocr.json`；本地 `kdp06c/{container-smoke,engine-version,engine-languages,packages,docker-build}.log`。最终镜像含最终 jar，无旧 jar 冒充最终制品。
- 容器峰值 570646528 字节，单份 1.231～4.241 秒；未测多租户 OCR 吞吐、真实业务 Recall 或复杂版面准确率。宿主/容器系统包版本不是跨平台锁定承诺。
- Compose base/readonly-worker/conversation-shadow/knowledge-split 四组合 config -q、runtime hardening、MyBatis boundary、git diff --check 通过。官方 HTTP 源最初 502/大索引连接失败，日志保留；改 HTTPS/保留签名校验/有界重试后成功。
- 21 个代码/测试/运行配置文件最终指纹 `319ca43a08d3e2ca6ed49d813c01ab66835741d02504fdfd49d40137f8fdfb0b`（`kdp06c/validated-files.json`）。所有证据在既有忽略目录，未把日志或私有环境提交。
