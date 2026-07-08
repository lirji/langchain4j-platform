# Code Interpreter 动作（深度 Agent · `code_exec`）

> **新项目定位**：`code_exec` 是 **agent-service（:8085）** 深度 Agent（ReAct）循环内部的一个可插拔动作，经边缘网关 **`/agent/run`** 暴露——你不直接调它，是模型在 plan→act→observe 循环里自己决定要不要用。**默认关**（`AGENT_CODE_EXEC_ENABLED=false`）。总览见 [agent-guide.md §7.4](agent-guide.md)。

---

## ⚠️ 安全须知（务必先读）

`code_exec` 是「让模型自己写 Java 源码并执行」的高风险能力。护栏放在**动作内部**，主循环不感知；护栏是**尽力而为**，不是强隔离。

- **默认沙箱 `subprocess`（中等隔离，非容器级）**：源码在一个受限的 JDK **子进程**里跑——独立进程、独立堆（`-Xmx` 限内存）、`environment().clear()` 不继承任何环境变量、以一个**空临时目录**作 cwd（用后递归删除）、墙钟超时后 `destroyForcibly()` 强杀。**但子进程仍与宿主共享内核 / 文件系统 / 网络命名空间**——不是 seccomp/gVisor 那种真隔离。
- **可选沙箱 `jshell`（弱隔离，同 JVM）**：源码用 JDK 内置 `jdk.jshell.JShell` 的 **local 执行引擎**在 **agent-service 同一个 JVM / 同一个堆 / 同一个类加载器**里跑。Java 21 已移除 `SecurityManager`，做不到真隔离——护栏只有 denylist + 超时 + 输出截断。
- 两种模式都保留 `CodeExecAction` 层的 **denylist**（静态子串匹配网络/文件/进程/反射/退出等 API）作纵深防御；denylist 是静态匹配，**可被混淆绕过**。
- 紧循环（`while(true){}`）在 `jshell` 模式下**杀不干净**（local 引擎的已知局限，Java 21 移除了 `Thread.stop`）；`subprocess` 模式靠强杀子进程规避此问题。

**结论**：只在**可信输入**、明确知道风险的场景下开启。面向**不可信输入**的生产场景，应换成一次性容器 / 远程 sandbox-service（seccomp / gVisor / 只读 rootfs / 无网络 namespace）——这是**未来项**。

---

## 做什么

给深度 Agent 加一个 **`code_exec`** 动作：模型自己写一段 **Java 源码**，在受控沙箱里执行，把 stdout + 最后表达式的值喂回循环，作为下一步推理的依据。

它属于深度 Agent 的「真实能力动作」家族——`rag_search`（查文档）/ `nl2sql_query`（查库）/ `mcp_call`（外部工具）/ **`code_exec`（跑代码）**——每个都实现同一个 `AgentAction` 接口（`name()` / `description()` / `run(input)`），被 `DeepAgentService` 用 `List<AgentAction>` 自动收集。**加这个能力没改循环一行代码**，这正是插件式动作设计的意义。

## 为什么

补齐「模型自己算数 / 转格式 / 跑确定性逻辑」这类**不该靠 LLM 心算、也不值得专门造一个工具**的长尾计算需求：

- **精确计算优于 LLM 心算**：大数乘除、取模、位运算、精确到分的金额换算，LLM 逐 token 生成极易算错；一段代码给的是确定性正确结果。
- **格式转换优于 LLM 硬凑**：进制转换、时间戳/日期换算、字符串规整、简单解析/统计，写代码比让模型「想象」输出更稳。
- **确定性逻辑**：排序、去重、集合运算、小规模模拟等——只要能写成纯函数，就该交给代码而不是自然语言推理。

设计上还验证了一件事：**一个能执行任意代码的动作，也能安全地插进 ReAct 循环**——护栏封装在动作里，循环对「这是危险能力」无感知。

## 怎么开

`CodeExecAction` 与其配置 Bean 都挂**双开关** `@ConditionalOnProperty`（`app.agent.enabled` **且** `app.agent.code-exec.enabled` 同时为 `true`），与其它可插拔动作一致：

```bash
# 起 agent-service（本地）
AGENT_ENABLED=true \
AGENT_CODE_EXEC_ENABLED=true \
mvn -pl agent-service -am spring-boot:run
```

或走整套本地栈：`docker compose -f deploy/docker-compose.yml up`，在 agent-service 的环境里加上 `AGENT_CODE_EXEC_ENABLED=true`。

任一开关为假：`CodeExecAction` / `CodeExecProperties` / 沙箱 Bean 都不存在，动作根本不进可用清单——模型不会去尝试一个不存在的能力，**零装配、零开销**。

> 父开关 `AGENT_ENABLED` 默认 `true`（深度 Agent 常开），所以实践中只需再打开子开关 `AGENT_CODE_EXEC_ENABLED=true`。

### 选沙箱

`AGENT_CODE_EXEC_SANDBOX` 决定隔离方式，默认 `subprocess`：

- `subprocess`（默认，推荐）：`SubprocessCodeSandbox`，独立子进程，隔离更强（见上「安全须知」）。子进程内跑一段自包含 `java --source` 驱动程序，用 JShell 编程式 API 逐片段执行，源码经 **stdin** 传入以避免转义问题。
- `jshell`：`JShellCodeSandbox`，同 JVM，仅 denylist + 超时 + 截断，隔离最弱，一般只用于最小依赖/嵌入式场景。

两种沙箱执行语义一致（裸表达式也会产出值），切换对使用方透明。

## 模型怎么用（它自己调，不是你调）

动作对模型自报的描述（`description()`）：

> 执行一段 Java 代码做精确计算/数据转换/确定性逻辑；actionInput 直接填 Java 源码。仅用于纯计算，禁止访问网络/文件/进程。

模型把 `actionInput` 直接填成 Java 源码（JShell 片段语义，可多条语句）：

- **表达式求值**：`2 + 3 * 4` → 返回 `14`
- **多语句 + 打印**：`int s=0; for(int i=1;i<=100;i++) s+=i; System.out.println(s);` → 返回 `5050`

需要事实用 `rag_search`、查库用 `nl2sql_query`——`code_exec` 只管纯逻辑。

## 护栏与执行链路

`run(input)` 顺序处理，**绝不抛异常**（超时、超限、编译错误、运行异常、禁用、空入参全部映射成「可纠错文本」回给模型，符合 `AgentAction` 契约；`DeepAgentService` 另有兜底 catch）：

1. **禁用判定**：`enabled=false` → 回「已禁用」提示（Bean 已被双开关兜住，这层给直接构造的测试用）。
2. **空入参守卫** → 回可纠错文本。
3. **源码长度**：超 `max-source-chars`（默认 4000）→ 直接拒绝，挡超大 payload / 上下文轰炸。
4. **危险 API 静态 denylist**（`block-unsafe-apis=true`）：小写子串匹配网络（`java.net`/`socket`/`urlconnection`/`httpclient`…）、文件（`java.io.file`/`fileinputstream`/`java.nio.file`/`files.`/`paths.`…）、进程（`runtime.exec`/`processbuilder`/`.exec(`…）、退出（`system.exit`/`.halt(`/`shutdown`…）、反射逃逸（`reflect`/`class.forname`/`setaccessible`…）、`system.load`/`loadlibrary`/`unsafe` 等 token；命中即拦截并打 **WARN 日志**（带 `TenantContext` 租户）。
5. **执行**（选定的 `CodeSandbox`）：把源码按补全边界拆成 snippet 顺序 eval，捕获 stdout/stderr 汇到缓冲，并收集每个表达式的 value。
6. **收口判定**：超时 / 编译或运行错误 / 空输出 / 截断 分别映射成对应可纠错文本。

### 超时实现

- `subprocess`：`process.waitFor(timeoutMs)` 超时 → `destroyForcibly()` 强杀子进程 + 短暂 drain 收尾，返回已捕获的部分输出并标记超时。
- `jshell`：eval 投到共享 **daemon** 线程池，`Future.get(timeoutMs)` 超时即 `cancel(true)`（interrupt 执行线程，能打断 `Thread.sleep` / IO 等**可中断点**）+ best-effort `JShell.stop()`，不等待直接返回超时文本。紧循环等不可中断点无法真正杀死；但线程为 daemon，不挡 JVM 退出、不阻塞后续 run。

### 长度截断

- `max-output-chars`（默认 2000）：回传给模型的输出（stdout + 表达式值）字符上限，超出截断并加「…（输出超过 N 字符已截断）」标记，防 scratchpad 爆。
- `max-source-chars`（默认 4000）：允许提交的源码上限，超出直接拒绝。
- 默认值刻意保守——`code_exec` 是高风险能力，宁可小而稳。

## 端到端跑一遍

`code_exec` 不是独立端点——你调 `/agent/run` 抛一个「适合用代码算」的 goal，模型会在 ReAct 循环里自己选中它。经网关，需 api-key 绑定 `agent` scope：

```bash
curl -s -X POST 'http://localhost:8080/agent/run' \
  -H 'X-Api-Key: dev-key-acme' \
  -H 'Content-Type: application/json' \
  -d '{"goal":"精确计算 (1234 * 5678) + 987654 / 3，用代码算不要心算"}'
```

返回 `AgentRunReply`（字段：`goal` / `steps[]` / `finalAnswer` / `stopReason` / `depth` / `tenantId`），每个 `step` 为 `{n, thought, action, actionInput, observation}`。期望在 `steps` 里看到某步 `action="code_exec"`、`actionInput` 是模型写的 Java 源码、`observation` 带正确数值，最终 `stopReason="DONE"`：

```json
{
  "goal": "精确计算 (1234 * 5678) + 987654 / 3，用代码算不要心算",
  "steps": [
    {
      "n": 1,
      "thought": "这是精确算术，交给 code_exec",
      "action": "code_exec",
      "actionInput": "(1234L * 5678L) + 987654L / 3L",
      "observation": "7335770"
    }
  ],
  "finalAnswer": "结果是 7335770。",
  "stopReason": "DONE",
  "depth": 0,
  "tenantId": "acme"
}
```

> 需要有 tool-calling 能力的模型（如 Ollama `qwen2.5+` / `llama3.1+`）才能稳定选中并填对 `actionInput`。多租户：api-key → 内部 JWT → 下游 `TenantContext`；被拦截的代码 WARN 日志与最终回包都带当前租户 `tenantId`。

## 局限（诚实声明）

- **不是真沙箱**：见文首「安全须知」。`jshell` 模式与宿主同 JVM；`subprocess` 模式虽有进程/堆/环境/cwd 隔离，仍共享内核/文件系统/网络命名空间。denylist 是静态匹配，可被混淆绕过；紧循环在 `jshell` 模式杀不干净。
- **对不可信输入不安全**：默认关就是这个原因。需要真正挡住恶意代码读文件 / 联网 / 提权时，换外部受限容器 / 远程 sandbox-service（seccomp / gVisor / 只读 rootfs / 无网络 namespace）。
- **只适合纯逻辑**：无网络、无文件、无进程——要事实走 `rag_search`，要查库走 `nl2sql_query`，要外部工具走 `mcp_call`。

## 相关文件

- `agent-service/.../actions/CodeExecAction.java` — 动作本体（门控 + denylist + 超时/截断编排，`run` 不抛）
- `agent-service/.../actions/CodeSandbox.java` — 沙箱接口（`Outcome` 收口，绝不外抛）
- `agent-service/.../actions/SubprocessCodeSandbox.java` — **默认**子进程沙箱
- `agent-service/.../actions/JShellCodeSandbox.java` + `JShellRunner.java` — 可选同 JVM JShell 沙箱
- `agent-service/.../actions/CodeExecProperties.java` / `CodeExecConfig.java` — `app.agent.code-exec.*` 绑定与条件化装配
- `agent-service/.../AgentAction.java` — 动作接口；`DeepAgentService.java` — ReAct 循环本体
- 深度 Agent 总览与其它动作见 [agent-guide.md](agent-guide.md)；外部工具动作见 [mcp-guide.md](../互操作渠道/mcp-guide.md)；运行配置见 [operations.md](../参考/operations.md)；接口速查见 [api-reference.md](../参考/api-reference.md)

## 开关速查

| 环境变量 | 属性（`app.agent.*`） | 默认 | 作用 |
|---|---|---|---|
| `AGENT_ENABLED` | `enabled` | `true` | 父开关：深度 Agent 循环本体（常开）。 |
| `AGENT_CODE_EXEC_ENABLED` | `code-exec.enabled` | `false` | 子开关：本动作。与父开关**同时** true 才装配。 |
| `AGENT_CODE_EXEC_SANDBOX` | `code-exec.sandbox` | `subprocess` | 沙箱：`subprocess`（默认，子进程隔离）/ `jshell`（同 JVM，弱隔离）。 |
| `AGENT_CODE_EXEC_TIMEOUT_MS` | `code-exec.timeout-ms` | `3000` | 单次执行墙钟超时（ms）；超时中断并回可纠错文本。 |
| `AGENT_CODE_EXEC_MAX_OUTPUT_CHARS` | `code-exec.max-output-chars` | `2000` | 回传模型的输出字符上限，超出截断并加标记。 |
| `AGENT_CODE_EXEC_MAX_SOURCE_CHARS` | `code-exec.max-source-chars` | `4000` | 允许提交的源码字符上限，超出直接拒绝。 |
| `AGENT_CODE_EXEC_BLOCK_UNSAFE_APIS` | `code-exec.block-unsafe-apis` | `true` | 是否静态拦截危险 API 源码（网络/文件/进程/退出/反射）。 |
| `AGENT_CODE_EXEC_MAX_HEAP_MB` | `code-exec.max-heap-mb` | `64` | 子进程沙箱 `-Xmx` 堆上限（MB）；`<=0` 表示不加 `-Xmx`。 |
| `AGENT_CODE_EXEC_JAVA_EXECUTABLE` | `code-exec.java-executable` | 空 | 起子进程用的 `java` 路径；留空用当前 JVM 的 `java.home`/bin/java。 |
