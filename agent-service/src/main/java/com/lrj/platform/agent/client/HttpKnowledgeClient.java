package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * {@link KnowledgeClient} 的 HTTP 实现，经 {@code knowledgeRestTemplate}（透传租户与 traceId）
 * 调用 knowledge-service 的 {@code /rag/query} 做检索。RestClient 异常被捕获并降级为空
 * {@link KnowledgeQueryReply}，不向调用方抛出。默认装配（{@code matchIfMissing=true}）。
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true", matchIfMissing = true)
public class HttpKnowledgeClient implements KnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpKnowledgeClient.class);

    private final RestTemplate restTemplate;

    public HttpKnowledgeClient(@Qualifier("knowledgeRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
        try {
            KnowledgeQueryReply reply = restTemplate.postForObject("/rag/query", request, KnowledgeQueryReply.class);
            return reply == null ? new KnowledgeQueryReply(request.query(), null, List.of()) : reply;
        } catch (RestClientException ex) {
            log.warn("agent knowledge query failed: {}", ex.toString());
            return new KnowledgeQueryReply(request.query(), null, List.of());
        }
    }
}
