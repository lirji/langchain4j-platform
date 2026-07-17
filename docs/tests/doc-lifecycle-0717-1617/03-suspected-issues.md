# 交互逻辑疑似问题

以下问题均来自对当前仓库实际代码的调用链阅读。它们没有被当作正确行为写入通过型断言；建议先确认契约或修复业务代码，再启用对应回归测试。

## ISSUE-01：上传失败后可能已写入 registry 与各内容存储（高）

**预期行为**

一次上传若向调用方返回异常/403，应当没有可观察的新增文档；至少 registry、向量、镜像、ES、图谱和授权关系应保持一致，分页 `total` 不应因失败请求而改变。

**现状**

`DocumentService.upload(...)` 的当前顺序是：切分与 embedding → 写向量库 → 写 `DocumentMirror` → 写 ES/图谱 → `registry.put(info)` → 在新建且非 shared 时检查 enforce 模式的 department → `knowledgeAuthz.onDocumentCreated(...)`。

因此两条失败路径都发生在内容和 registry 已落地之后：

1. enforce 模式且 `TenantContext.current().department()` 为空时，代码在 `registry.put(info)` 后抛 403；
2. `onDocumentCreated(...)` 写授权关系时依赖报错，异常向外传播，但此前所有内容写入已完成。

这会制造“HTTP 失败但 registry 已有记录”的幽灵状态。enforce 下它可能暂时被列表判权过滤；切回 disabled/shadow、授权服务恢复或后续覆盖时会再次暴露，且分页 total/页边界可能变化。

**复现路径（建议修复后写成回归测试，当前只记 TODO）**

1. 设置 `TenantContext`：tenant=`acme`、user=`alice`、scopes=`ingest`、department=`null`。
2. 给 `DocumentService` 注入 `mode() == ENFORCE` 的确定性 `KnowledgeAuthz`。
3. 调用 `upload("ghost.md", "text/markdown", "body", "manual")`。
4. 当前会抛 `ResponseStatusException(403)`。
5. 随后把 authz 切为 disabled 或直接检查同一个 `DocumentRegistry`：当前可找到 `computeDocId("acme","ghost.md")`，`listPaged(false,1,10).total()` 可能成为 1；向量/镜像也已经写入。

**建议处置**

- 最低限度：把 enforce 的 department 前置校验移动到任何 sink 写入之前。
- 授权关系写入失败：明确一致性策略。可采用先授权后内容并做补偿、事务/outbox 状态机，或捕获失败后按相反顺序补偿各 sink；不能继续让“失败响应 + 已提交内容”成为隐式行为。
- 修复后增加回归测试：断言异常状态码，同时断言 registry 为空、分页 total=0，并验证内容 sink 没有残留。测试名建议 `failedEnforceCreation_doesNotChangeRegistryOrPagedTotal`，标记 `// TODO(issue-01): enable after production fix`，不要按当前残留行为写断言。

## ISSUE-02：稳定排序不能保证并发写入下的跨请求分页不重不漏（中，契约待确认）

**预期行为**

如果产品把“稳定分页”理解为用户翻页期间不重复、不漏项，那么在相邻页请求之间发生上传、覆盖或删除时，也应有快照/cursor 语义；或者 API 文档必须明确只保证单次静态数据集上的确定顺序。

**现状**

`listPaged` 每次都重新执行 `registry.list` → authz 过滤 → 全量排序 → offset 切片，没有快照版本或 cursor。排序只消除了底层 map/HVALS 迭代随机性，不能阻止数据集在两次请求之间改变导致 offset 漂移。

**复现路径**

1. 固定排序数据为 `[d3,d2,d1]`（时间降序），请求 `page=1,size=2`，得到 `[d3,d2]`。
2. 在第二次请求前上传更新的 `d4`，新排序为 `[d4,d3,d2,d1]`。
3. 请求 `page=2,size=2`，offset 切片得到 `[d2,d1]`。
4. `d2` 在两页重复；类似地，页首项删除会造成未读项被跳过。

**建议处置**

- 若只要求静态集合稳定：把契约改写为“同一数据快照内稳定排序”，并修正当前测试注释中“稳定排序保证跨页不重不漏”的过强表述。
- 若要求并发一致：改 cursor/keyset pagination（`uploadedAt,docId`）或引入 registry snapshot/version token。
- 测试草案只覆盖静态快照的精确顺序；并发变更场景标记 `// TODO(issue-02): define snapshot/cursor contract first`，不锁定当前重/漏行为。

## ISSUE-03：enforce 授权依赖故障被呈现为“空知识库”（中低，产品/安全待确认）

**预期行为**

安全上必须 fail-closed；同时调用方最好能区分“确实没有可见文档”和“授权依赖不可用，暂时拒绝展示”，避免前端错误显示“暂无文档”。

**现状**

`RealKnowledgeAuthz.filterReadable(...)` 在 ENFORCE 模式捕获依赖异常并返回空集；`DocumentService.listPaged(...)` 将其正常过滤为 `items=[]、total=0、totalPages=1`，Controller 返回正常信封。前端据 `total==0` 显示空库提示。日志和指标能看到依赖错误，但 API 消费方不能区分。

**复现路径**

1. registry 中放入 3 个 `acme` 文档。
2. ENFORCE 模式下让 `AuthzEngine.checkBulk(...)` 抛 `RuntimeException("down")`。
3. 调用 `listPaged(false,1,10)`。
4. 当前结果是 `total=0` 的正常分页信封，而不是可辨识的依赖故障。

**建议处置**

- 保持 fail-closed，但讨论是否由授权层抛出可映射为 503 的类型化异常，或在响应/运行状态中携带 degraded 标识。
- 若团队明确“200 空集”就是安全合同，则在 API 文档写明，并增加观测告警；确认前不要在分页测试里断言授权异常等同合法空库。
- 建议测试名 `authzDependencyFailure_isFailClosedButObservable`，标记 `// TODO(issue-03): expected HTTP/result contract pending`。

## 已审查但未判定为 bug

- `Comparator.nullsLast(Comparator.reverseOrder())` 的组合会把非空时间降序、null 放末尾，当前实现方向正确；缺的是固定数据回归测试。
- `@GetMapping` 无 params 的旧入口与 `params="page"` 的新入口同时存在时，Spring MVC 会优先更具体的参数条件；仍应以注解结构测试防止未来误删条件。
- `size` 超上限的计算与 `totalPages` 使用同一个 `safeSize`，当前代码一致；需要边界测试防回归。
- shared 列表绕过文档 ReBAC 与现有公共库“普通登录用户可读”合同一致，不应把它当越权 bug。
