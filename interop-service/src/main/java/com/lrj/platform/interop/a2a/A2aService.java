package com.lrj.platform.interop.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.interop.InteropProperties;
import com.lrj.platform.interop.a2a.MessageSendParams.PushNotificationConfig;
import com.lrj.platform.protocol.agent.AgentTaskView;
import com.lrj.platform.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 真 A2A 协议核心：把 JSON-RPC method 路由到 agent-service（经 {@link A2aAgentGateway} 代理），
 * 做 A2A 协议 ↔ agent 内部端点的翻译。
 *
 * <p>覆盖方法：{@code message/send}（chat 同步 / deep-research 异步 Task）、{@code tasks/get}
 * （轮询任务状态）、{@code tasks/cancel}。内部走 typed-HTTP，A2A 只做对外互操作面。
 */
@Service
public class A2aService {

    private static final Logger log = LoggerFactory.getLogger(A2aService.class);

    public static final String SKILL_CHAT = "chat";
    public static final String SKILL_RESEARCH = "deep-research";

    private final A2aAgentGateway gateway;
    private final A2aTaskMapper mapper;
    private final InteropProperties props;
    private final ObjectMapper json;
    private final A2aPushNotificationStore pushStore;

    public A2aService(A2aAgentGateway gateway,
                      A2aTaskMapper mapper,
                      InteropProperties props,
                      ObjectMapper json,
                      A2aPushNotificationStore pushStore) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.props = props;
        this.json = json;
        this.pushStore = pushStore;
    }

    /** 非流式方法分派。 */
    public JsonRpcResponse dispatch(String method, JsonNode params, Object id) {
        try {
            return switch (method) {
                case "message/send" -> handleMessageSend(params, id);
                case "tasks/get" -> handleTaskGet(params, id);
                case "tasks/cancel" -> handleTaskCancel(params, id);
                case "tasks/pushNotificationConfig/set" -> handlePushConfigSet(params, id);
                case "tasks/pushNotificationConfig/get" -> handlePushConfigGet(params, id);
                default -> JsonRpcResponse.error(id, JsonRpcError.methodNotFound(method));
            };
        } catch (IllegalArgumentException e) {
            return JsonRpcResponse.error(id, JsonRpcError.invalidParams(e.getMessage()));
        } catch (RestClientException e) {
            log.warn("A2A method {} failed calling agent-service", method, e);
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.INTERNAL_ERROR,
                    "agent-service call failed: " + e.getMessage()));
        } catch (Exception e) {
            log.error("A2A method {} failed", method, e);
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // —— message/send ——

    private JsonRpcResponse handleMessageSend(JsonNode params, Object id) {
        MessageSendParams p = parse(params, MessageSendParams.class);
        A2aMessage msg = (p == null) ? null : p.message();
        if (msg == null || msg.textContent().isBlank()) {
            throw new IllegalArgumentException("message.parts must contain non-empty text");
        }
        String skill = skillOf(msg);
        String text = msg.textContent();

        if (SKILL_RESEARCH.equals(skill)) {
            // 异步：代理到 agent-service /agent/run/async，返回 A2A Task（submitted/working）。
            // webhook 一律指向 interop 自己的 push 回调（而非客户端 URL）：终态由 A2aPushForwarder 按 A2A
            // 信封中继。这样即便 push 配置晚于 send 通过 tasks/pushNotificationConfig/set 登记，也能生效。
            AgentTaskView task = gateway.submitTask(text, props.getA2a().getPushCallbackUrl());
            if (task == null) {
                return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.INTERNAL_ERROR,
                        "agent-service did not return a task"));
            }
            PushNotificationConfig push = pushConfig(p);
            if (push != null && push.url() != null && !push.url().isBlank()) {
                pushStore.put(TenantContext.current().tenantId(), task.taskId(), push);
            }
            return JsonRpcResponse.success(id, mapper.toA2aTask(task));
        }

        // 默认 chat skill：同步代理到 /agent/run
        String contextId = (msg.contextId() != null && !msg.contextId().isBlank())
                ? msg.contextId() : UUID.randomUUID().toString();
        String reply = gateway.chat(text);
        return JsonRpcResponse.success(id, A2aMessage.agentText(reply, null, contextId));
    }

    // —— tasks/get ——

    private JsonRpcResponse handleTaskGet(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) {
            throw new IllegalArgumentException("id is required");
        }
        Optional<AgentTaskView> task = gateway.getTask(p.id());
        return task.map(t -> JsonRpcResponse.success(id, mapper.toA2aTask(t)))
                .orElseGet(() -> JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id())));
    }

    // —— tasks/cancel ——

    private JsonRpcResponse handleTaskCancel(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) {
            throw new IllegalArgumentException("id is required");
        }
        Optional<AgentTaskView> existing = gateway.getTask(p.id());
        if (existing.isEmpty()) {
            return JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id()));
        }
        if (isTerminal(existing.get().status())) {
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.TASK_NOT_CANCELABLE,
                    "Task is already in a terminal state: " + p.id()));
        }
        boolean canceled = gateway.cancelTask(p.id());
        if (!canceled) {
            return JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcError.TASK_NOT_CANCELABLE,
                    "Task could not be canceled: " + p.id()));
        }
        return gateway.getTask(p.id())
                .map(t -> JsonRpcResponse.success(id, mapper.toA2aTask(t)))
                .orElseGet(() -> JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id())));
    }

    // —— Agent Card ——

    public A2aAgentCard agentCard() {
        List<String> text = List.of("text/plain");
        A2aAgentCard.Skill chat = new A2aAgentCard.Skill(
                SKILL_CHAT, "Chat",
                "Single-turn / multi-turn agent run with tools and citation.",
                List.of("chat", "agent", "qa"),
                List.of("用三句话介绍 LangChain4j"), text, text);
        A2aAgentCard.Skill research = new A2aAgentCard.Skill(
                SKILL_RESEARCH, "Deep research",
                "Long-running deep agent run. Returned as an async A2A Task; poll via tasks/get.",
                List.of("research", "async", "deep-agent"),
                List.of("对比 PGVector / Milvus / Qdrant 三个向量库"), text, text);

        return new A2aAgentCard(
                props.getA2a().getAgentName(),
                props.getA2a().getAgentDescription(),
                props.getA2a().getBaseUrl() + "/interop/a2a",
                props.getA2a().getVersion(),
                "0.2.0",
                new A2aAgentCard.Capabilities(true, true, false),
                text, text,
                List.of(chat, research),
                Map.of("apiKey", new A2aAgentCard.SecurityScheme(
                        "apiKey", "header", "X-Api-Key", "Per-tenant API key (edge-gateway).")),
                List.of(Map.of("apiKey", List.of())));
    }

    /** message 的 metadata.skill 决定走哪个 skill；缺省 chat。 */
    public String skillOf(A2aMessage msg) {
        if (msg != null && msg.metadata() != null) {
            Object s = msg.metadata().get("skill");
            if (s instanceof String str && !str.isBlank()) {
                return str;
            }
        }
        return SKILL_CHAT;
    }

    // —— tasks/pushNotificationConfig/set|get（A2A 规范方法，登记/查询 push 回调配置）——

    private JsonRpcResponse handlePushConfigSet(JsonNode params, Object id) {
        TaskPushNotificationConfig p = parse(params, TaskPushNotificationConfig.class);
        if (p == null || p.taskId() == null || p.taskId().isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        PushNotificationConfig cfg = p.pushNotificationConfig();
        if (cfg == null || cfg.url() == null || cfg.url().isBlank()) {
            throw new IllegalArgumentException("pushNotificationConfig.url is required");
        }
        pushStore.put(TenantContext.current().tenantId(), p.taskId(), cfg);
        return JsonRpcResponse.success(id, p);
    }

    private JsonRpcResponse handlePushConfigGet(JsonNode params, Object id) {
        TaskQueryParams p = parse(params, TaskQueryParams.class);
        if (p == null || p.id() == null) {
            throw new IllegalArgumentException("id is required");
        }
        return pushStore.get(TenantContext.current().tenantId(), p.id())
                .map(cfg -> JsonRpcResponse.success(id, new TaskPushNotificationConfig(p.id(), cfg)))
                .orElseGet(() -> JsonRpcResponse.error(id, JsonRpcError.taskNotFound(p.id())));
    }

    private static PushNotificationConfig pushConfig(MessageSendParams p) {
        if (p == null || p.configuration() == null) {
            return null;
        }
        return p.configuration().pushNotificationConfig();
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private <T> T parse(JsonNode params, Class<T> type) {
        if (params == null || params.isNull()) {
            return null;
        }
        return json.convertValue(params, type);
    }
}
