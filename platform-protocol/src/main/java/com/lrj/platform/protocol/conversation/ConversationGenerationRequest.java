package com.lrj.platform.protocol.conversation;

import java.util.List;

/**
 * 独立 conversation runtime 的无状态生成契约。
 *
 * <p>可信身份只允许通过内部 JWT 传播，禁止进入请求体；memory/profile/cache 仍由
 * conversation-service 持有。
 */
public record ConversationGenerationRequest(
        String schema_version,
        String message,
        String context,
        Style style,
        List<ConversationHistoryMessage> history
) {
    public record Style(
            String language,
            String tone,
            String citation_policy,
            String extra
    ) {}
}
