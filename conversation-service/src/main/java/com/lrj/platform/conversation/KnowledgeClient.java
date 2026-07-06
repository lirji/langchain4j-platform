package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;

public interface KnowledgeClient {

    KnowledgeQueryReply query(KnowledgeQueryRequest request);
}
