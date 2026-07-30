package com.lrj.platform.conversation.shadow;

import com.lrj.platform.protocol.conversation.ConversationGenerationRequest;

/**
 * 无状态 conversation candidate 的影子观察端口。
 *
 * <p>实现必须 best-effort，不能修改 primary 返回、memory、cache、grounding 或 guardrail 决策。
 */
@FunctionalInterface
public interface ConversationShadowObserver {

    void observe(ConversationGenerationRequest request, String primaryReply);
}
