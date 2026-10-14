# Execution State（checkpoint / resume）

> 2026-10-14 交付更新：下文为 2026-09-25 的历史实施记录，“未提交/不 push”等描述适用于当时。当前已按用户明确指令分组提交并推送 main；最新验证和状态以 `docs/delivery/pending-reliability-delivery/DELIVERY_RESULT.md` 为准。

- Goal：按 `REMEDIATION_BACKLOG.md` 执行 FIX_NOW 切片，每片一验证。
- Workflow：`project-refactoring`（EXECUTE），Plan v1（未发生重规划），State v2（全部切片完成）。
- Autonomy：本地仓库写 + 本地测试；**不 push / 不部署**。
- Repository revision：`e8f11cb`（开工基线）。
- Baseline：`mvn -B -o test -DskipITs` 全反应堆 BUILD SUCCESS（25 模块，1:11 min）。

## Slices

| Slice | 状态 | 验证 |
|---|---|---|
| S1 = R1 edge 入站内部头 | DONE | edge-gateway 76 测试全绿（+6）；注入回归：还原旧代码后 3 项失败（伪造头被转发到下游），恢复后全绿 |
| S2 = R2 token 预算预检 | DONE | platform-metering +10 测试；conversation/agent/knowledge/vision 回归全绿；真实 HTTP 冒烟见 Smoke |
| S3 = R3 Redis 语义缓存上限 | DONE | 真实 Redis（dev-infra :46379，lc4j ACL）4 项集成测试全绿；注入「不裁桶」后 3 项失败；conversation 136 全绿 |
| S4 = R4 RS256 门禁 | DONE | cutover 门禁 PASS；注入 HS256 与「签名私钥挂到 agentscope-orchestrator」两类回归均被拦截 |
| S5 = R5 Casdoor 注释 | DONE | 仅注释；edge 编译通过 |

## Final verification

- 全反应堆 `mvn -B -o test -DskipITs`：BUILD SUCCESS，1374 run / 0 fail / 10 skip（基线 1354 / 0 / 6；+20 新测试，其中 4 个 env 门控的真实 Redis 测试在门控开启时已跑绿）。
- deploy 门禁：production-cutover / runtime-hardening / supply-chain / database-migration / observability 全 PASS
  （后三者依赖 `rg`，本机非交互 shell 无 `rg`，用 Cursor 自带 ripgrep 放进 PATH 后执行）。
- 真实冒烟（工作树打包的 jar，dev-infra Redis）：
  - vision-service：预置用满预算的租户 `POST /vision/caption` → 429 + `Retry-After: 45519` + `token_budget_exhausted`；
    另一租户穿过预检进入模型调用（本地无 LiteLLM → 500，审计 `llm.error` 证明已到达模型层）；GET 不拦（405）；
    `platform_token_budget_guard_total{outcome="rejected"} 1.0`。
  - edge-gateway（`EDGE_CASDOOR_ENABLED=false`）：伪造 `X-Internal-Token` 无 api-key → 401；无凭证 → 401；
    有效 api-key → 通过认证并路由（下游未起 → 500，日志为 `Connection refused :8081`）。

## Next executable action

无（GOAL 内 FIX_NOW 全部完成）。下一轮候选见 `REMEDIATION_REPORT.md`「Next Evolution Candidates」。
提交：用户未要求提交，改动留在工作树；每个切片的补丁在会话 scratchpad（S1–S3.patch）。

## SYSTEM_GOVERNANCE_GAP

### GAP-1 关键词路由把「产品代码整改」送进只允许改 Runtime 文件的技能
- Observed：`intent-mapper.py "Real project remediation: fix validated findings in existing repo"` → `remediation`（score 125）；该技能 Forbidden 含 `modify_product_code` / `modify_tests`。含「架构演进」的中文表述 → `architecture-evolution-planner`（不执行代码、需已有拆分决策）。
- Expected：面向已有项目、已有分析、需改产品代码的整改请求应落到可改代码的执行型工作流（实测 `project-refactoring` 的契约匹配）。
- Trigger：执行指令文件名与正文大量使用 "Remediation"。
- Impact：按路由结果执行会在第一处产品代码修改时违反技能契约，或停在只规划状态。
- Workaround：改用「技术债治理」表述复核路由 → `project-refactoring`，在本文件记录。
- Follow-up：独立治理轮次中给 `remediation` 路由增加「目标是 runtime/部署 Finding」的限定，或新增产品代码整改的 canonical task type。

### GAP-2 deploy 门禁脚本对 `rg` 的隐式依赖
- Observed：`deploy/test-{supply-chain,database-migration,observability}-config.sh` 在无 `rg` 的 shell 中以误导性信息失败（如 "expected at least 16 long-running Java services, found 0"），看起来像配置回归。
- Expected：缺工具时明确报「需要 ripgrep」。属项目脚本问题而非共享 Skill 系统；记录在此供下一轮项目整改（P3）。
- Workaround：把本机已有 ripgrep 链进 scratchpad PATH。

### GAP-3 共享 Skill 系统没有「产品代码整改」的执行型状态机/计划工件
- Observed：`project-refactoring` 契约要求 Baseline / Test Protection 两个 Gate，但没有可调用的机器 Gate 工具；本轮以 `.engineering/remediation/` 手写 backlog + state 承载。
- Impact：Planning Gate / plan version 只能人工维护，跨会话 resume 依赖本文件的纪律而非工具校验。
- Workaround：本文件 + `REMEDIATION_BACKLOG.md` 作为唯一权威状态。
