package contracts.knowledge

import org.springframework.cloud.contract.spec.Contract

/**
 * knowledge-service provider 契约：空 query 触发 400（controller 把 IllegalArgumentException 翻成 badRequest）。
 */
Contract.make {
    description "POST /rag/query rejects blank query with 400"
    request {
        method POST()
        url "/rag/query"
        headers {
            contentType(applicationJson())
        }
        body(
                query: " "
        )
    }
    response {
        status BAD_REQUEST()
    }
}
