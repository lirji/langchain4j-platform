package com.lrj.platform.agent;

/**
 * 由 {@link AgentAction} 可选实现的运行结束回调。{@link DeepAgentService} 在每次 {@code run} 结束（无论成败）
 * 后，对实现了本接口的动作逐个触发 {@link #onRunEnd()}，用于释放/复位该动作在单次运行内积累的资源或状态。
 */
public interface AgentRunListener {

    void onRunEnd();
}
