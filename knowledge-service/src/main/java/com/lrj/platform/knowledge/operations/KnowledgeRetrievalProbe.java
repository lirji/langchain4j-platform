package com.lrj.platform.knowledge.operations;

import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryReply;
import com.lrj.platform.protocol.knowledge.KnowledgeQueryRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import static com.lrj.platform.knowledge.operations.KnowledgeOperations.*;

/** combined复用本地检索；拆分角色经固定内部地址转发原凭证，不向验签节点分发签名私钥。 */
public final class KnowledgeRetrievalProbe implements KnowledgeProbe, com.lrj.platform.knowledge.quality.QualityRetriever {
    private final KnowledgeQueryService local;
    private final RestTemplate remote;
    private final String internalHeader;
    public KnowledgeRetrievalProbe(KnowledgeQueryService local, RestTemplate remote, String internalHeader) {
        this.local = local; this.remote = remote; this.internalHeader = internalHeader;
    }
    /** 评测复用实际query入口；只允许服务端配置地址，原凭证保留租户/部门授权。 */
    @Override
    public KnowledgeQueryReply retrieve(TenantContext.Tenant actor, String token, String question,
            com.lrj.platform.protocol.knowledge.RetrievalQuality.Settings settings) {
        if (remote != null) {
            if (token == null || token.isBlank()) throw new OperationsException("forbidden", "verified internal token required");
            HttpHeaders headers = new HttpHeaders(); headers.set(internalHeader, token);
            KnowledgeQueryReply result = remote.postForObject("/rag/query", new HttpEntity<>(
                    new KnowledgeQueryRequest(question, settings.topK(), settings.minScore(), settings.category()), headers), KnowledgeQueryReply.class);
            if (result == null || !actor.tenantId().equals(result.tenantId())) throw new OperationsException("unavailable", "invalid retrieval identity");
            return result;
        }
        if (local == null) throw new OperationsException("unavailable", "knowledge query endpoint unavailable");
        if (!actor.equals(TenantContext.captureRaw())) throw new OperationsException("forbidden", "retrieval identity mismatch");
        var result = local.query(question, settings.topK(), settings.minScore(), settings.category());
        return new KnowledgeQueryReply(result.query(), result.tenantId(), result.hits().stream().map(hit ->
                new com.lrj.platform.protocol.knowledge.KnowledgeHit(hit.id(), hit.score(), hit.docId(), hit.displayName(),
                        hit.category(), hit.index(), hit.text(), hit.source(), hit.shared() ? "public" : "tenant", hit.version())).toList());
    }

    @Override
    public List<CheckResult> evaluate(TenantContext.Tenant actor, String token, Candidate candidate) {
        if (local == null && remote == null) throw new OperationsException("unavailable", "configure the knowledge query endpoint before running retrieval probes");
        return candidate.draft().checks().stream().map(check -> {
            boolean passed;
            if (remote != null) {
                if (token == null || token.isBlank()) throw new OperationsException("forbidden", "verified internal token required for remote probe");
                HttpHeaders headers = new HttpHeaders(); headers.set(internalHeader, token);
                KnowledgeQueryReply reply = remote.postForObject("/rag/query", new HttpEntity<>(
                        new KnowledgeQueryRequest(check.question(), 5, 0.0, candidate.draft().category()), headers), KnowledgeQueryReply.class);
                if (reply == null || !actor.tenantId().equals(reply.tenantId()))
                    throw new OperationsException("unavailable", "retrieval returned an invalid tenant result");
                // 旧query节点缺少版本时必须失败关闭，不能把其他版本的相同文本作为本次发布证据。
                passed = reply.hits().stream().anyMatch(hit -> candidate.documentId().equals(hit.docId())
                        && candidate.release() != null
                        && Long.toString(candidate.release().documentVersion()).equals(hit.version())
                        && "tenant".equals(hit.visibility()) && hit.text() != null && hit.text().contains(check.requiredText()));
            } else {
                if (!actor.equals(TenantContext.captureRaw())) throw new OperationsException("forbidden", "probe identity context mismatch");
                passed = local.query(check.question(), 5, 0.0, candidate.draft().category()).hits().stream()
                        .anyMatch(hit -> candidate.documentId().equals(hit.docId()) && !hit.shared()
                                && Long.toString(candidate.release().documentVersion()).equals(hit.version())
                                && hit.text() != null && hit.text().contains(check.requiredText()));
            }
            return new CheckResult(check.question(), passed, passed ? "published_document_retrieved" : "published_document_or_quote_missing");
        }).toList();
    }
}
