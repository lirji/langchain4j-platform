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
