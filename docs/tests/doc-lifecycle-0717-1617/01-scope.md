# 文档生命周期分页测试范围

## 约束与审查基线

- 本轮只设计测试与审查交互逻辑，不修改业务代码，也不把测试写入 `src/`。
- 代码基线是 2026-07-17 工作树中的未提交改动；现有未提交测试也计入“已有覆盖”。
- 已实际阅读生产代码、相关授权实现、注册表实现、网关工厂、Controller 调用方和现有测试。本文不假设不存在的 API。
- 本轮未运行 Maven。`mvn test` 会在各模块写入 `target/`，超出本任务唯一允许写入的文档目录；下面仅给出后续落地验证命令。

## 被测模块与生产代码

模块：`knowledge-service`（依赖 `platform-gateway-client`、`platform-security`、`platform-audit` 等上游模块）。

| 类 | 实际受影响方法/结构 | 本轮锁定目标 |
|---|---|---|
| `com.lrj.platform.knowledge.lifecycle.DocumentService` | `list()`、`list(boolean)`、`listPaged(boolean,int,int)`；内部 `readable(boolean,List<DocumentInfo>)`、`sorted(List<DocumentInfo>)` | 授权过滤后计数和切片；1-based 页码 clamp；size 默认/上限；空结果；稳定排序；共享/租户分区 |
| `com.lrj.platform.knowledge.lifecycle.PagedDocuments` | record canonical constructor 与 `items/page/size/total/totalPages` accessors | 分页信封字段和 JSON 字段名；`total` 是过滤后的可见数 |
| `com.lrj.platform.knowledge.controller.DocumentController` | `list(String)`、`listPaged(String,int,Integer)` 及其 `@GetMapping` 条件 | 有 `page` 才选择分页入口；无 `page` 保持数组入口；非空 size 原样传递；空 size 传 0；public/shared 分区选择 |
| `com.lrj.platform.knowledge.KnowledgeChatModelConfig` | `knowledgeChatModel(GatewayChatModelFactory,String)` | 只调用 `factory.build(modelName, 0.0)` 并原样返回；方法不再带 `@ConditionalOnMissingBean` |

为核实交互而阅读、但不作为本轮业务修改对象的代码：

- `DocumentRegistry`、`InMemoryDocumentRegistry`、`RedisDocumentRegistry`
- `KnowledgeAuthz`、`NoopKnowledgeAuthz`、`RealKnowledgeAuthz`、`AuthzMode`
- `GatewayChatModelFactory`、`PlatformGatewayClientAutoConfiguration`
- `TenantContext`
- 前端 `listDocumentsPaged(...)` 与 `RagWorkspaceView.vue` 的分页消费链路

## 当前相关测试

| 路径 | 当前覆盖摘要 | 审查结论 |
|---|---|---|
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/lifecycle/DocumentServicePagingTest.java` | 25/23 条切片、首尾页、页码 clamp、size 默认/截断、空库、无重漏、时间降序、租户/共享分区 | 基础覆盖存在；时间依赖和弱断言较多，缺同时间戳 docId、nullsLast、授权三态与分页交互、精确边界一致性 |
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/controller/DocumentControllerPublicTest.java` | 分页 tenant 非空 size；public + null size；旧数组入口 | 直接委派已覆盖；缺映射注解的回归锁定，直接调用无法证明 HTTP 路由条件 |
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/KnowledgeChatModelConfigTest.java` | 工厂模型名、温度 0、返回同一实例 | 行为覆盖充分；缺“不得恢复 `@ConditionalOnMissingBean`”的结构回归断言 |
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/authz/RealKnowledgeAuthzTest.java` | enforce/shadow、bulk、依赖故障、强一致 | 授权实现本身覆盖较好，但没有穿透到 `DocumentService.listPaged` 的 total/切片 |
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/authz/KnowledgeAuthzIntegrationTest.java` | 真实 auth-platform 条件可用时覆盖 list enforce | 依赖网络且通过 assumption 跳过；类名不是 `*IT`，不能作为默认套件的确定性分页保障 |
| `knowledge-service/src/test/java/com/lrj/platform/knowledge/controller/DocumentControllerTest.java` | 旧 Controller POJO 行为 | 不覆盖新分页映射 |

## 建议的测试分层

1. 纯 POJO 单元/边界测试：直接构造 `DocumentService`，用手动 mock 或确定性内存实现注入，覆盖排序、分页和授权交互。这是主层。
2. Controller 映射结构与委派测试：直接构造 Controller；已有测试保留委派验证，新增反射断言锁定映射条件，不启动 Spring/MockMvc。
3. 分页信封契约测试：直接构造 record，并用普通 `ObjectMapper` 检查 JSON 字段，不启动 Spring。
4. 配置工厂单测：手动 mock `GatewayChatModelFactory`，同时检查 Bean 注解结构。
5. 不新增 DB、Redis、网络或 Spring context 集成测试。排序和切片在 service 层，与具体 registry 后端无关。

## 后续落地运行命令

完整模块及上游依赖：

```bash
INTERNAL_JWT_SECRET=test-secret-at-least-32-bytes-long \
  mvn -pl knowledge-service -am test
```

聚焦单类（必须带 `-pl`）：

```bash
mvn -pl knowledge-service -Dtest=DocumentServicePagingTest test
mvn -pl knowledge-service -Dtest=DocumentControllerPagingMappingTest test
mvn -pl knowledge-service -Dtest=PagedDocumentsTest test
mvn -pl knowledge-service -Dtest=KnowledgeChatModelConfigTest test
```

上述四个聚焦类本身不走内部 JWT；完整模块命令仍按仓库约定提供不少于 32 字节的 `INTERNAL_JWT_SECRET`。
