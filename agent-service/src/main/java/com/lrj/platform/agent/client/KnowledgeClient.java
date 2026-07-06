package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;

public interface KnowledgeClient {

    KnowledgeQueryReply query(KnowledgeQueryRequest request);
}
