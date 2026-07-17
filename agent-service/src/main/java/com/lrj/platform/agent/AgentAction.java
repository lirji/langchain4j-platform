package com.lrj.platform.agent;

/**
 * Agent 可调用工具的统一契约。每个实现暴露一个 {@link #name()} / {@link #description()} 供 ReAct
 * 决策核心选择，以及 {@link #run(String)} 执行动作并返回观察文本。所有实现由 Spring 以
 * {@code List<AgentAction>} 注入并按名注册进 {@link DeepAgentService}，是本仓库「接口 + @ConditionalOnProperty
 * 多实现」可插拔工具注册表的核心抽象（如 {@code RagSearchAction}、{@code AnalyticsSqlAction}、{@code CodeExecAction}）。
 */
public interface AgentAction {

    /**
     * 工具名称
     *
     * @return
     */
    String name();

    /**
     * 工具描述
     *
     * @return
     */
    String description();

    /**
     * 调用工具传参
     *
     * @param input
     * @return
     */
    String run(String input);
}
