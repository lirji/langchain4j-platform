package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真 A2A Server 入口：
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} —— A2A 发现惯例别名（经 edge-gateway 白名单免鉴权对外）</li>
 *   <li>{@code POST /interop/a2a} —— JSON-RPC 2.0 单端点（需鉴权），代理到 agent-service</li>
 * </ul>
 *
 * <p>与既有 {@code InteropController} 的 {@code /interop/agent-card} / {@code /interop/a2a/agent-card}
 * （平台自有互操作卡）并存、各用各的，互不破坏。
 */
@RestController
public class A2aController {

    private final A2aService service;

    public A2aController(A2aService service) {
        this.service = service;
    }

    /** A2A 发现惯例别名。edge-gateway 白名单放行（免鉴权），下游无租户上下文亦可返回（纯静态元数据）。 */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard wellKnownAgentCard() {
        return service.agentCard();
    }

    @PostMapping("/interop/a2a")
    public JsonRpcResponse handle(@RequestBody JsonNode body) {
        Object id = idOf(body);

        JsonNode methodNode = body == null ? null : body.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INVALID_REQUEST, "missing or non-string 'method'"));
        }
        String method = methodNode.asText();
        JsonNode params = body.get("params");
        return service.dispatch(method, params, id);
    }

    /** JSON-RPC id 可为 string / number / null —— 原样回带，类型保持。 */
    private static Object idOf(JsonNode body) {
        JsonNode n = body == null ? null : body.get("id");
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return n.asText();
    }
}
