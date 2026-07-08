package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 真 A2A Server 入口：
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} —— A2A 发现惯例别名（经 edge-gateway 白名单免鉴权对外）</li>
 *   <li>{@code POST /interop/a2a} —— JSON-RPC 2.0 单端点（需鉴权），代理到 agent-service。
 *       {@code message/stream} 返回 {@code SseEmitter}（真流式），其余方法返回 {@code JsonRpcResponse}</li>
 * </ul>
 *
 * <p>与既有 {@code InteropController} 的 {@code /interop/agent-card} / {@code /interop/a2a/agent-card}
 * （平台自有互操作卡）并存、各用各的，互不破坏。
 */
@RestController
public class A2aController {

    private final A2aService service;
    private final A2aStreamService streamService;
    private final ObjectMapper json;

    public A2aController(A2aService service, A2aStreamService streamService, ObjectMapper json) {
        this.service = service;
        this.streamService = streamService;
        this.json = json;
    }

    /** A2A 发现惯例别名。edge-gateway 白名单放行（免鉴权），下游无租户上下文亦可返回（纯静态元数据）。 */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public A2aAgentCard wellKnownAgentCard() {
        return service.agentCard();
    }

    /**
     * 返回类型声明为 {@code Object}：{@code message/stream} 返回 {@code SseEmitter}（Spring 按实际值类型
     * 走 SSE），其余方法返回 {@code JsonRpcResponse}（Jackson 序列化成 application/json）。
     */
    @PostMapping("/interop/a2a")
    public Object handle(@RequestBody JsonNode body) {
        Object id = idOf(body);

        JsonNode methodNode = body == null ? null : body.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INVALID_REQUEST, "missing or non-string 'method'"));
        }
        String method = methodNode.asText();
        JsonNode params = body.get("params");

        if ("message/stream".equals(method)) {
            try {
                MessageSendParams p = json.convertValue(params, MessageSendParams.class);
                if (p == null || p.message() == null || p.message().textContent().isBlank()) {
                    return JsonRpcResponse.error(id,
                            JsonRpcError.invalidParams("message.parts must contain non-empty text"));
                }
                return streamService.stream(p.message(), service.skillOf(p.message()), id);
            } catch (IllegalArgumentException e) {
                return JsonRpcResponse.error(id, JsonRpcError.invalidParams(e.getMessage()));
            }
        }

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
