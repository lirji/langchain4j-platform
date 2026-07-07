package com.lrj.platform.interop.a2a;

import com.lrj.platform.protocol.agent.AgentTaskView;

import java.util.Optional;

/**
 * interop → agent-service 的 A2A 代理网关：把 A2A 语义映射到 agent-service 既有的 typed HTTP 端点
 * （{@code /agent/run}、{@code /agent/run/async}、{@code /agent/tasks/**}）。
 *
 * <p>实现类挂租户/trace forwarder（内部 JWT 透传），保证内网调用带上当前租户身份。
 */
public interface A2aAgentGateway {

    /** 同步 chat：{@code POST /agent/run}，返回 finalAnswer 文本。 */
    String chat(String text);

    /** 异步任务：{@code POST /agent/run/async}，返回创建的任务视图（含 taskId + 初始状态）。 */
    AgentTaskView submitTask(String goal, String webhookUrl);

    /** 任务查询：{@code GET /agent/tasks/{taskId}}；不存在返回空。 */
    Optional<AgentTaskView> getTask(String taskId);

    /** 任务取消：{@code DELETE /agent/tasks/{taskId}}；成功返回 true，不存在/不可取消返回 false。 */
    boolean cancelTask(String taskId);
}
