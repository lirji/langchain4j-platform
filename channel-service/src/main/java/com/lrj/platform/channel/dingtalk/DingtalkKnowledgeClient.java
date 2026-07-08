package com.lrj.platform.channel.dingtalk;

import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * 桥 → knowledge-service 的 {@code /rag/query} 客户端，供「无命中转人工」兜底闸门用。
 * 用带租户/trace 转发器的 RestTemplate（与 conversation 侧 {@code knowledgeRestTemplate} 同款），
 * 故调用前需先 set 好 {@code TenantContext}（检索按该租户隔离）。
 *
 * <p><b>失败即视为无命中</b>：知识库不可达时返回空 hits，闸门会走「转人工」而非让 LLM 裸答——
 * 客服场景「宁可转人工，不可乱答」的取舍。异常只记 warn，不抛断链路。
 */
public class DingtalkKnowledgeClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkKnowledgeClient.class);

    private final RestTemplate restTemplate;

    public DingtalkKnowledgeClient(RestTemplate dingtalkKnowledgeRestTemplate) {
        this.restTemplate = dingtalkKnowledgeRestTemplate;
    }

    /** 查知识库；失败返回空 hits（等价无命中 → 转人工）。 */
    public KnowledgeQueryReply query(KnowledgeQueryRequest request) {
        try {
            KnowledgeQueryReply reply = restTemplate.postForObject("/rag/query", request, KnowledgeQueryReply.class);
            return reply == null ? empty(request) : reply;
        } catch (RestClientException ex) {
            log.warn("dingtalk knowledge gate query failed, treating as no-hit → human: {}", ex.toString());
            return empty(request);
        }
    }

    private static KnowledgeQueryReply empty(KnowledgeQueryRequest request) {
        return new KnowledgeQueryReply(request.query(), null, List.of());
    }
}
