package contracts.agent

import org.springframework.cloud.contract.spec.Contract

/**
 * agent-service provider 契约：POST /agent/run 执行 ReAct 循环并返回步骤 + 最终答案。
 * consumer 侧（interop-service HttpAgentInteropClient）依赖此形状（含 tenantId 回填）。
 */
Contract.make {
    description "POST /agent/run executes a ReAct run and returns steps"
    request {
        method POST()
        url "/agent/run"
        headers {
            contentType(applicationJson())
        }
        body(
                goal: "summarize refund policy"
        )
    }
    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body(
                goal: "summarize refund policy",
                finalAnswer: "Refunds are issued within 7 days.",
                stopReason: "DONE",
                depth: 0,
                tenantId: "acme",
                steps: [
                        [
                                n          : 1,
                                thought    : "check the refund manual",
                                action     : "rag_search",
                                actionInput: "refund policy",
                                observation: "Refunds within 7 days."
                        ]
                ]
        )
    }
}
