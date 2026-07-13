package contracts.knowledge

import org.springframework.cloud.contract.spec.Contract

/**
 * knowledge-service provider 契约：POST /rag/query 返回租户隔离的检索命中。
 * consumer 侧（conversation-service HttpKnowledgeClient、agent-service HttpKnowledgeClient）依赖此形状。
 */
Contract.make {
    description "POST /rag/query returns tenant-scoped knowledge hits"
    request {
        method POST()
        url "/rag/query"
        headers {
            contentType(applicationJson())
        }
        body(
                query: "refund policy",
                topK: 3,
                minScore: 0.2,
                category: "manual"
        )
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body(
                query: "refund policy",
                tenantId: "acme",
                hits: [
                        [
                                id         : "doc-1#0",
                                score      : 0.87,
                                docId      : "doc-1",
                                displayName: "Refund Manual",
                                category   : "manual",
                                index      : "0",
                                text       : "Refunds are processed within 7 days.",
                                source     : "vector",
                                visibility : "tenant"
                        ]
                ]
        )
    }
}
