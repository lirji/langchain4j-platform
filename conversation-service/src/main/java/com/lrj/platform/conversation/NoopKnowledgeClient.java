package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// 与 HttpKnowledgeClient 用同一属性互斥（RAG 关闭/缺省时兜底）。
// 不用 @ConditionalOnMissingBean：在组件扫描下它对 @Component 的注册顺序敏感、不可靠
// （Spring 官方仅推荐用于自动配置类），曾导致 RAG 关闭时 KnowledgeClient bean 缺失、服务启动失败。
@Component
@ConditionalOnProperty(name = "app.conversation.rag.enabled", havingValue = "false", matchIfMissing = true)
public class NoopKnowledgeClient implements KnowledgeClient {

    @Override
    public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
        return new KnowledgeQueryReply(request.query(), null, List.of());
    }
}
