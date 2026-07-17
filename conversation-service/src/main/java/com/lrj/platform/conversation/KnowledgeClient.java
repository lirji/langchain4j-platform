package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;

/**
 * 对话服务检索知识库的抽象：把 {@link KnowledgeQueryRequest} 交给 knowledge-service 换回
 * {@link KnowledgeQueryReply}。默认实现 {@link HttpKnowledgeClient} 走 HTTP，测试中可用 lambda mock。
 */
public interface KnowledgeClient {

    KnowledgeQueryReply query(KnowledgeQueryRequest request);
}
