package com.lrj.platform.eval.retrieval;

import com.lrj.platform.eval.EvalProperties;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 经 {@code {targetBaseUrl}/rag/query}（默认打 edge-gateway，携带 {@code X-API-Key}，网关换发内部 JWT 转 knowledge）
 * 取检索命中，映射为 {@code displayName#index} 有序 id 列表。检索失败返回空列表（该 case recall=0，不中断整跑）。
 */
@Component
public class HttpRetrievalClient implements RetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRetrievalClient.class);

    private final RestTemplate evalRestTemplate;
    private final EvalProperties properties;

    public HttpRetrievalClient(RestTemplate evalRestTemplate, EvalProperties properties) {
        this.evalRestTemplate = evalRestTemplate;
        this.properties = properties;
    }

    @Override
    public List<String> retrieve(String targetBaseUrl, String question, Integer topK, String category) {
        String base = targetBaseUrl == null || targetBaseUrl.isBlank()
                ? properties.getDefaultTargetBaseUrl() : targetBaseUrl;
        HttpHeaders headers = new HttpHeaders();
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set(properties.getApiKeyHeader(), properties.getApiKey());
        }
        KnowledgeQueryRequest body = new KnowledgeQueryRequest(question, topK, null, category);
        try {
            ResponseEntity<KnowledgeQueryReply> resp = evalRestTemplate.exchange(
                    base + "/rag/query", HttpMethod.POST, new HttpEntity<>(body, headers), KnowledgeQueryReply.class);
            KnowledgeQueryReply reply = resp.getBody();
            if (reply == null || reply.hits() == null) {
                return List.of();
            }
            return reply.hits().stream().map(HttpRetrievalClient::sourceId).toList();
        } catch (RestClientException e) {
            log.warn("retrieval query failed for '{}': {}", question, e.toString());
            return List.of();
        }
    }

    /** 与 conversation RagPromptAugmenter / grounding 同口径：{@code displayName#index}。 */
    private static String sourceId(KnowledgeHit hit) {
        String displayName = hit.displayName() == null || hit.displayName().isBlank() ? "doc" : hit.displayName();
        String index = hit.index() == null || hit.index().isBlank() ? "0" : hit.index();
        return displayName + "#" + index;
    }
}
