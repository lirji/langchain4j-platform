package com.lrj.platform.tax;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import com.lrj.platform.protocol.tax.TaxPolicyEvidence;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** 通过租户感知 RestTemplate 查询 knowledge-service；失败或租户不一致时安全降级为空证据。 */
final class HttpTaxKnowledgeClient implements TaxKnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpTaxKnowledgeClient.class);
    private static final String QUERY = "中国 增值税 发票 风险审查 重复发票 金额税额 开票期间";
    private final RestTemplate restTemplate;
    private final TaxReviewProperties.Knowledge properties;

    HttpTaxKnowledgeClient(RestTemplate restTemplate, TaxReviewProperties.Knowledge properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public List<TaxPolicyEvidence> findEvidence(TaxReviewOutcome outcome) {
        try {
            KnowledgeQueryReply reply = restTemplate.postForObject("/rag/query",
                    new KnowledgeQueryRequest(QUERY, properties.getTopK(), properties.getMinScore(), properties.getCategory()),
                    KnowledgeQueryReply.class);
            String tenantId = TenantContext.current().tenantId();
            if (reply == null || !tenantId.equals(reply.tenantId())) {
                if (reply != null) log.warn("tax knowledge tenant mismatch; evidence discarded");
                return List.of();
            }
            int limit = Math.min(properties.getTopK(), reply.hits().size());
            return java.util.stream.IntStream.range(0, limit)
                    .mapToObj(index -> {
                        var hit = reply.hits().get(index);
                        return new TaxPolicyEvidence("E" + (index + 1), hit.docId(), hit.displayName(), hit.source(),
                                hit.score(), truncate(hit.text(), properties.getEvidenceMaxChars()));
                    })
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("tax knowledge query failed; continuing without evidence: {}", ex.toString());
            return List.of();
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "…";
    }
}
