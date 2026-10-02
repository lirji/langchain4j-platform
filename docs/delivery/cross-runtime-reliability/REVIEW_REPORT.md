# 跨运行时可靠性复审

范围：Java基线e8f11cb、Python基线c4fc90d至本任务提交。实现结束后重新按失效场景核对源码、
实际diff和测试，不将实现阶段的通过断言直接当作架构结论。本轮由主Agent完成自复审，未声称独立评审人批准。

## 已修复的复审发现

| Finding | 实际失败场景与修正 | 证据 |
|---|---|---|
| R1 | AgentScope终帧为完整累积内容，直接追加导致答案翻倍；只发后缀且核对前缀 | 独立HTTP/SSE精确比较、candidate测试 |
| R2 | 外层生成器aclose不关闭底层HTTP；逐调用捕获parser/response关闭；parser关闭异常仍关闭HTTP | 真TCP取消、test_closing_model |
| R3 | JDK h2c升级在Uvicorn误读请求体；该边界强制HTTP/1.1 | 修复前真实跨进程失败、修复后3项通过 |
| R4 | grounding IllegalStateException曾被当作断连，未发终态；按验证失败发公共error，重复callback只终结一次 | StreamingConversationControllerTest |
| R5 | shadow定时器注册晚于快速完成可能遗留timeout；先注册deadline，晚到future与句柄均取消 | 超时、拒绝、迟到SDK句柄用例 |
| R6 | 新readonly kind未进入progress/webhook允许集合；补齐协议集合 | 独立worker RUN/DAG真实恢复链路 |
| R7 | RS256仅验签节点无法mint候选；显式启用时启动失败，不自动给验签服务发私钥 | RSA公钥节点配置测试 |
| R9 | 既有远程CI无法取IAM SNAPSHOT，补固定公开源码构建；依赖审计升级3个已有包，0漏洞 | SDK源/consumer测试、uv audit |
| R10 | cutover grep-q早退使Linux管道SIGPIPE误报; 完整消费输入, 保留安全断言 | 141→0复现、clean gate PASS、远程重跑 |
| R8 | worker字符串分支、空catch和未用import；使用既有kind枚举、固定协议常量和无敏感信息诊断 | 两仓Code Hygiene无blocking |

## 核对的不变量

- RAG结果缺失租户不向模型泄漏片段；body不选择可信身份。
- channel持久化先于ACK，领取/写回受租约epoch限制；队列、重试和管理员重放有界。
- 每个模型出口调用前预留；Lua原子准入，owner/operation/day绑定；终态usage结算幂等。
- 退款网络未知结果只读原回执，绝不重放写；原租户/用户/参数约束保持。
- readonly调度接受时记录可信身份并缩小scope；持久化输入/版本配置可重建；旧executor写回被拒。
- 候选快照先于主模型，候选预算在当前主验证之后预留；候选失败不改主响应/状态。
- producer完整SHA、全manifest摘要、消费文件摘要与DTO组合受CI固定；缺源不绿。

## 仍需运营/发布验收的边界

1. inbox/worker是至少一次；外部回复或只读调用/模型费用可重复，不承诺端到端exactly-once。
2. 预算为token准入与实际usage记账；估算可能低于提供商usage，未知usage保留额度，非严格美元硬上限。
   当前Redis单主，多key Lua不宣称支持Redis Cluster。embedding/rerank不属于Chat模型额度。
3. 原生首帧前SDK无句柄窗口仍存在；取消不撤销已经提交的记忆/远程效果。
4. 只读worker按接受时授权执行，最大任务年龄24小时；组织权限撤销需要取消待执行任务并验收生产授权生命周期。
5. S6/S7提供显式Compose拓扑；生产Helm worker/candidate拓扑、RS256委托签发方案、真实模型质量、容量、恢复演练与切流仍需外部证据。
6. 本轮不改历史ESS阻塞、生产runbook NO-GO或用户原有未提交安全/缓存调整。

结论：七项本地实现无未处理blocking finding; 远程CI新增Java依赖安全阻塞main交付, cutover Linux SIGPIPE已修复待远程复验。
已形成SECURITY_MIGRATION_PROPOSAL; 不绕过扫描。生产结论仍NO-GO。
