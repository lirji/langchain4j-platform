# S7: 流式shadow实施设计

Python新增独立候选入口, 只装配无状态conversation生成/stream和JWT校验, 不启动Agent API、session或worker。
沿用既有conversation-generation/stream-event契约. 有界并发与输入/输出上限, 逐事件yield形成背压,
总超时/断连finally关闭SDK生成器; token_budget继续经S3模型工厂预留, 未知usage不释放。
错误只发公共code, SSE sequence从0连续, token非空, done/error恰好一个终态, 终态后无事件。

Java主流保持现有护栏、RAG、记忆与grounding权威. 新stream shadow独立有界后台读取候选SSE,
只做顺序/终态/文本比较指标, 不写主状态、不把候选返回给客户端. 从主调用前捕获有界历史和上下文, 主模型/grounding完成后才启动候选避免争抢当前请求预算;
主失败/断连取消候选; 候选失败、超时、队列拒绝不改变主响应。
现1.13.1 SDK TokenStream支持onPartialResponseWithContext和StreamingHandle.cancel:
捕获句柄, 断连时取消, 句柄迟到则立即取消. 首token前无法取得句柄的窗口保留为SDK限制,
不能宣称发出请求前就能取消; 禁止继续输出/写回并在句柄到达时取消。

验收使用本地假HTTP/SSE提供方及独立候选进程, 不访问真实付费模型。
覆盖连续序号、重复终态、截断错误、资源饱和/背压、客户端断连、候选取消和主链不受失败影响。
设计已按下述实现和测试证据闭合。

## 实现与验证结果

LOCAL_PASS。Python 4个并发槽, 0.1s等待/429, body262144字节, 合计输入/输出65536字符。
没有预取队列, ASGI 2.3监听断连和2.4发送失败都会关闭SDK流并释放槽。
SDK终帧是完整快照, 只发尚未输出的后缀, 前缀不一致则失败。
当前AgentScope外层aclose未显式传到底层parser/HTTP response; ClosingOpenAIChatModel
按调用捕获并显式关闭两层, 不依赖GC, 不把资源保存在共享模型实例上。
Java使用HTTP/1.1避免Uvicorn h2c升级误读; 1–2线程/16队列, 总deadline默认5s/上限30s,
有界逐行解析、连续序号、一个终态/非空回复和终态后EOF验证。

聚焦测试包含主链snapshot→primary→grounding→candidate顺序、候选提交失败仍发done、
重复主回调、迟到句柄取消、身份恢复, Python 8项覆盖认证/边界/背压/断连。
`uv run python scripts/test_conversation_stream_process.py --java-repo ../langchain4j-platform`
真实Java内部JWT→独立Uvicorn→本地假OpenAI SSE的成功、错误、取消三项通过;
测试先证实提供方已打开流, 再取消并观察TCP关闭, 不访问真实模型。
Java conversation及上游完整聚合测试通过; 最终全仓统计见QA_REPORT。
原始日志位于Python仓 `.git/codex-cross-runtime-reliability-baseline/conversation-*.log`。

## 开启与回滚

默认关闭。Java `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.conversation-shadow.yml config`
后可启动显式实验overlay; Python独立启动使用`compose.yml`加`compose.conversation-shadow.yml`。
需要相同内部JWT配置、真实网关凭据; Java需具备合法签发能力，RS256仅验签节点启用时启动失败，
本轮不向验签服务自动分发私钥或改变信任边界; 不继承Agent写工具、session或worker的secret/env_file。
预算开启时同时启用S3 authority, 影子消耗会计入租户日限额, 不能承诺对未来请求零影响。
回滚关闭`CONVERSATION_STREAM_SHADOW_ENABLED`/`CONVERSATION_SHADOW_ENABLED`, 停止候选服务;
无持久化数据迁移。正常主流完成后影子可继续在deadline内验证; 下游失败/超时取消。

首帧前拿不到原生SDK句柄的窗口仍存在; 不宣称撤销已提交的记忆/模型费用。
本轮证明隔离和协议/恢复正确性, 未测真实模型质量、TTFT、生产吞吐、Helm候选拓扑或授权撤销生命周期。
生产NO-GO不因本地假模型测试改变。
