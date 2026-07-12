# Comparison

## 候选方案摘要

| 方案 | 名称 | 版本来源 | 启动时间来源 | 改动特征 |
| --- | --- | --- | --- | --- |
| A | Manifest Version + Local WebFlux Controller | jar Manifest `Implementation-Version` | bean 初始化时捕获，或轻量 ready-time bean | 最小代码改动，不改构建元数据 |
| B | Spring Boot BuildProperties + Ready Event | `META-INF/build-info.properties` / `BuildProperties` | `ApplicationReadyEvent` | 构建元数据更规范，改动略多 |

## 评分表

分值：5 最优，1 最差。

| 维度 | 方案 A | 方案 B | 说明 |
| --- | ---: | ---: | --- |
| 正确性 | 4 | 5 | A 在 jar 包运行时正确，但 IDE/test 下版本可能 `unknown`；B 版本语义更明确。 |
| 改动风险 | 5 | 4 | A 不改构建生命周期；B 修改 Maven 插件执行，风险仍可控。 |
| 复杂度 | 5 | 3 | A 只需 controller 和少量 filter 变更；B 多 build-info 与 ready event 处理。 |
| 可维护性 | 4 | 4 | A 简单直观；B 与 Spring Boot 标准对齐。 |
| 扩展性 | 3 | 5 | B 更适合后续 build metadata 扩展。 |
| 测试难度 | 4 | 3 | A 单元测试容易；B 需覆盖 `BuildProperties` 存在/缺失。 |
| 回滚成本 | 5 | 4 | A 删除新增 controller 和白名单即可；B 还需回滚 Maven build-info。 |
| 总分 | 30 | 28 | 当前轻量需求下 A 更贴近最小改动。 |

## 风险对比

- 方案 A 主要风险是“构建版本号”在非 jar 运行场景不可用。可以通过 `unknown` 兜底和测试覆盖降低风险。
- 方案 B 主要风险是把轻量需求扩大为构建体系变更。虽然规范，但当前仓库没有现成 build-info 约定，可能引入不必要配置差异。
- 两个方案都必须处理 `/version` 与两个 `GlobalFilter` 白名单的一致性，否则会出现鉴权或限流行为与预期不一致。

## 推荐判断

推荐采用以方案 A 为主体、吸收方案 B 的 ready-time 思路的合并方案：

- 版本来源采用 Manifest `Implementation-Version`，因为当前 jar 已确认存在该字段。
- 启动时间采用独立 `ApplicationReadyEvent` 监听 bean 捕获，避免“bean 构造时间”与“应用 ready 时间”概念不一致。
- 如 `ApplicationReadyEvent` 尚未触发则兜底为 bean 创建时间，避免空值。
- 暂不引入 `build-info`，降低改动面。
- 若后续运维明确需要 build time、artifact、git commit，再演进到方案 B。

## 所选方案已知弱点

- 本地 IDE/classes 运行时版本可能为 `unknown`，不是 bug，但需要在验收说明中写清楚。
- Manifest 只能提供有限元数据，未来扩展会遇到边界。
- 白名单仍在两个 filter 中重复维护；本次可以只加 `/version`，不做共享抽象以避免超出范围。
