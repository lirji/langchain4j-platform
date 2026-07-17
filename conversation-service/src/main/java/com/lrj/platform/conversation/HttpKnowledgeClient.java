package com.lrj.platform.conversation;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * {@link KnowledgeClient} 的 HTTP 实现（仅 {@code app.conversation.rag.enabled=true} 时装配）：通过
 * {@code knowledgeRestTemplate} POST 到 knowledge-service 的 {@code /rag/query} 拉取检索结果。检索失败或返回
 * 空时降级为空 {@link KnowledgeQueryReply}，让对话在无 RAG 上下文时继续正常进行。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.rag.enabled", havingValue = "true")
public class HttpKnowledgeClient implements KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpKnowledgeClient.class);

    private final RestTemplate knowledgeRestTemplate;

    public HttpKnowledgeClient(RestTemplate knowledgeRestTemplate) {
        this.knowledgeRestTemplate = knowledgeRestTemplate;
    }

    @Override
    public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
        try {
            KnowledgeQueryReply reply = knowledgeRestTemplate.postForObject("/rag/query", request, KnowledgeQueryReply.class);
            return reply == null ? new KnowledgeQueryReply(request.query(), null, List.of()) : reply;
        } catch (RestClientException ex) {
            log.warn("knowledge query failed, continuing without RAG context: {}", ex.toString());
            return new KnowledgeQueryReply(request.query(), null, List.of());
        }
    }
}
