# 覆盖矩阵

状态说明：

- **已有**：当前工作树测试已有有效断言。
- **缺口 → 草案**：应由 `TEST_PLAN.md` 中完整代码草案补齐。
- **TODO 疑点**：当前行为可能有问题，不应先把现状锁成正确行为；详见 `03-suspected-issues.md`。
- **不新增**：已有下层覆盖或不属于本变更的合理输入契约。

## DocumentService.list / list(boolean)

| 公共方法/分支 | 场景与输入 | 期望 | 当前状态 | 对应测试 |
|---|---|---|---|---|
| `list()` | 当前 tenant，默认 Noop authz | 等价于 `list(false)` | 已有 | `KnowledgeAuthzIntegrationTest#listAndOverwrite_enforced` 间接；旧 `DocumentServiceTest` 列表行为 |
| `list(false)` | authz disabled，非空 | 不调用过滤器，返回当前租户全集并排序 | 缺口 → 草案 | `DocumentServicePagingTest#disabledAuthz_skipsFilteringAndKeepsVisibleTotal` |
| `list(false)` | authz disabled，空 registry | 空列表 | 已有；短路未验证 | 当前 `emptyLibrary_totalZero_totalPagesOne`；草案 `emptyResult_clampsPageAndSkipsAuthzBulkCheck` 补短路 |
| `list(false)` | authz shadow，判权只允许子集 | 仍返回全集；调用 bulk 供观测 | 缺口 → 草案 | `DocumentServicePagingTest#shadowAuthz_observesAllCandidatesButDoesNotReduceTotalOrItems` |
| `list(false)` | authz enforce，允许子集 | 只返回允许 docId | 仅真实外部条件测试 | `DocumentServicePagingTest#enforceAuthz_filtersBeforeTotalsPageClampAndSlice` |
| `list(false)` | enforce bulk 依赖失败 | fail-closed 为空 | 授权层已有；API 语义 TODO | `RealKnowledgeAuthzTest#filterReadable_enforce_failClosed_onError`；ISSUE-03 |
| `list(true)` | enforce 已开启 | 共享库仍跳过文档级判权 | 缺口 → 草案 | `DocumentServicePagingTest#sharedLibrary_bypassesTenantDocumentAuthz` |
| `list(boolean)` 排序 | 不同 `uploadedAt`，registry 顺序乱 | 时间降序 | 已有但使用实时上传 | 当前 `sortedByUploadedAtDescending`；草案 `sortsByUploadedAtDescendingThenDocIdAscendingWithNullsLast` 改为固定时钟数据 |
| 同上 | 相同 `uploadedAt`，docId 乱序 | docId 升序 | 缺口 → 草案 | 同上 |
| 同上 | `uploadedAt=null` | 非空时间全部在前；null 组按 docId 升序 | 缺口 → 草案 | 同上 |
| 同上 | `docId=null` | 输入契约未定义 | 待验证，不写断言 | Edge E-09 |

## DocumentService.listPaged(boolean,int,int)

| 分支/输入 | 期望 | 当前状态 | 对应测试 |
|---|---|---|---|
| `page=1,size=10,total=25` | 10 项，page=1，total=25，totalPages=3 | 已有 | `firstPage_sliceSizeAndTotals` |
| `page=3,size=10,total=25` | 5 项 | 已有 | `lastPage_hasRemainder` |
| 静态数据跨全部页 | 精确顺序、不重不漏 | 已有但只校验集合 | 草案加强 | `staticSnapshot_pagesAreExactOrderedPartitionWithoutDuplicatesOrGaps` |
| `page=0/-5` | clamp 到 1 | 已有 | `pageBelowOne_clampsToFirst_andSizeDefaultsAndCaps` |
| `page=Integer.MIN_VALUE` | clamp 到 1 | 缺口 → 草案 | `sizeAndPageIntegerBoundaries_keepEnvelopeInternallyConsistent` |
| `page>totalPages` | clamp 到末页 | 已有 | `pageBeyondEnd_clampsToLastPage` |
| `page=Integer.MAX_VALUE` | clamp 到末页且切片不溢出 | 缺口 → 草案 | `sizeAndPageIntegerBoundaries_keepEnvelopeInternallyConsistent` |
| `size=0` | size=10 | 已有 | `pageBelowOne...` |
| `size<0` | size=10 | 仅间接/不完整 | 草案加强 | `sizeAndPageIntegerBoundaries_keepEnvelopeInternallyConsistent` |
| `size=100` | 不截断 | 缺口 → 草案 | 同上 |
| `size=101/9999` | 截断 100 | 已有 size 字段；totalPages 未锁定 | 草案加强 | 同上 |
| `total=0` 且任意高页 | items 空，page=1，totalPages=1 | 基本已有 | `emptyResult_clampsPageAndSkipsAuthzBulkCheck` 加强高页和 authz 短路 |
| total 恰好整除 size | `totalPages=total/size`，末页满 | 当前跨页测试间接 | 草案精确 | `staticSnapshot_pagesAreExactOrderedPartitionWithoutDuplicatesOrGaps`（20/5） |
| total 不整除 size | ceil，末页余数 | 已有 | `lastPage_hasRemainder` |
| enforce 过滤后再分页 | total/totalPages/page clamp/items 均基于可读子集 | 缺口 → 草案 | `enforceAuthz_filtersBeforeTotalsPageClampAndSlice` |
| shadow + 分页 | total/items 保持全集，bulk 仍收到全部候选 | 缺口 → 草案 | `shadowAuthz_observesAllCandidatesButDoesNotReduceTotalOrItems` |
| disabled + 分页 | total 是分区全集；不做 bulk | 缺口 → 草案 | `disabledAuthz_skipsFilteringAndKeepsVisibleTotal` |
| shared + enforce | 共享 total/items 不受租户 ReBAC 子集影响 | 缺口 → 草案 | `sharedLibrary_bypassesTenantDocumentAuthz` |
| tenant A / tenant B / public | 三个 registry 分区分别计数和切片 | 当前只 tenant/public | `tenantAndSharedPartitions_haveIndependentTotalsAndSlices` 补跨租户 |
| 连续翻页期间发生新增/删除 | offset 可能重/漏 | TODO 疑点 | ISSUE-02，不锁定现状 |
| 超大分区 | 先取全量、全量 authz、全量排序再切片 | 资源风险 | Edge E-10；本轮不做性能阈值断言 |

## PagedDocuments

| 公共结构/场景 | 期望 | 当前状态 | 对应测试 |
|---|---|---|---|
| 五个 accessor | 精确保留 service 计算结果 | 已由分页测试逐项覆盖 | 多个 `DocumentServicePagingTest` |
| JSON 信封字段 | 恰有 `items,page,size,total,totalPages` 且值正确 | 缺口 → 草案 | `PagedDocumentsTest#serializesStableEnvelopeFieldNamesAndValues` |
| `items` 的 service 输出 | 是本页副本，不暴露 `subList` | 实现使用 `List.copyOf`，无需求级断言 | 不新增，避免锁定未声明的 record 通用不可变性 |
| 构造器非法组合 | record 自身不校验；合法性由 service 保证 | 不新增 | service 的 envelope 一致性测试覆盖合法生产路径 |

## DocumentController

| 方法/HTTP 分支 | 期望 | 当前状态 | 对应测试 |
|---|---|---|---|
| `list(null)` | 调用 `documents.list(false)`，返回数组 | 已有 | `DocumentControllerPublicTest#list_default_isTenantScoped` |
| `list("public")` | 调用 `documents.list(true)` | 已有 | `list_withPublicVisibility_readsSharedPartition_noSpecialScope` |
| `listPaged(null,2,10)` | 非空 size 原样传入 tenant 分区 | 已有 | `listPaged_delegatesWithVisibilityAndPaging` |
| `listPaged("public",1,null)` | shared=true，size 传 0 | 已有 | `listPaged_public_readsSharedPartition_sizeDefaultsWhenNull` |
| `visibility="shared"` | 与 public 同义 | 旧 `isPublic` 逻辑已有其它路径覆盖；分页未单列 | 不重复造；映射与 service 分区测试足够 |
| 无 `page` query | 只命中旧 `@GetMapping`，即使有 size 也保持数组契约 | 缺映射锁定 → 草案 | `DocumentControllerPagingMappingTest#pageParameterIsTheOnlyConditionSelectingPagedEnvelope` |
| 有 `page` query | `@GetMapping(params="page")` | 缺映射锁定 → 草案 | 同上 |
| `page=` 空字符串 | Spring 默认值 1 | 注解结构可验证；实际 binder 未启动 | 草案断言 `defaultValue="1"`；HTTP binder 行为待框架契约 |
| 非数字/超 int query | Spring 参数绑定 400，service 不执行 | 框架标准行为 | 不新增 Spring context 测试 |

## KnowledgeChatModelConfig

| 分支/结构 | 期望 | 当前状态 | 对应测试 |
|---|---|---|---|
| 指定模型名 | 原样传给工厂 | 已有 | `knowledgeChatModel_builtViaFactory_withDeterministicTemperature` |
| 温度 | 精确 `0.0` | 已有 | 同上 |
| 返回值 | 与工厂产物同一实例 | 已有 | 同上 |
| 工厂交互 | 只调用 `build(modelName,0.0)` 一次 | 已有 verify；缺 no-more | 草案加强同一测试 |
| Bean 条件 | 方法有 `@Bean` 且没有 `@ConditionalOnMissingBean` | 缺口 → 草案 | `knowledgeChatModel_isUnconditionalApplicationBeanWithExpectedSignature` |
| 默认模型名属性解析 | `@Value` 由 Spring 解析 | 本轮禁止 Spring context；表达式已在生产签名核实 | 待验证：由应用启动冒烟覆盖，不新增纯单测伪造容器行为 |

## 异常、安全与并发摘要

| 类别 | 场景 | 结论 |
|---|---|---|
| 租户安全 | TenantContext 未设置 | 会落 `anonymous` 分区；这是平台兜底，不是分页新增行为，不新增断言 |
| ThreadLocal | 测试后不 clear | 会污染后续用例；所有草案强制 `@AfterEach TenantContext.clear()` |
| authz 异常 | enforce 下 bulk 失败 | 当前 200 空信封，安全上 fail-closed，业务语义待确认（ISSUE-03） |
| 状态一致性 | enforce 上传在后置校验失败 | 已写数据但请求失败，影响后续分页（ISSUE-01） |
| 并发 | 两页请求之间 registry 变化 | offset 页边界漂移（ISSUE-02） |
| 注入 | page/size 非数字 | Spring binder 400；没有字符串拼接/SQL 注入面 |
