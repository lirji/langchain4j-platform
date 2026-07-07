package com.lrj.platform.protocol.agent;

/**
 * Prompt Chaining 入口请求（{@code POST /agent/chain}）。
 *
 * <p>只带初始输入；链的步骤定义走服务端 {@code app.agent.chaining.steps} yml（预定义代码路径编排，
 * 不由请求方决定流程），与单体 {@code /chat/chain} 的 body {@code {"input":"..."}} 对齐。
 */
public record ChainRunRequest(String input) {
}
