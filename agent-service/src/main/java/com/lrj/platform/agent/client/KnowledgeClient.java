package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;

/**
 * agent-service 访问 knowledge-service RAG 检索的客户端抽象。以 {@link KnowledgeQueryRequest}
 * 提问、返回 {@link KnowledgeQueryReply}，供 Agent 工具引入外部知识。默认实现 {@link HttpKnowledgeClient}。
 */
public interface KnowledgeClient {

    KnowledgeQueryReply query(KnowledgeQueryRequest request);
}
