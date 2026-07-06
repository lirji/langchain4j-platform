package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnMissingBean(KnowledgeClient.class)
public class NoopKnowledgeClient implements KnowledgeClient {

    @Override
    public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
        return new KnowledgeQueryReply(request.query(), null, List.of());
    }
}
