package com.lrj.platform.gateway;

import com.lrj.platform.gateway.tenant.TenantAttributionMode;
import com.lrj.platform.gateway.tenant.TenantAwareChatModel;
import com.lrj.platform.gateway.tenant.TenantAwareStreamingChatModel;
import com.lrj.platform.gateway.tenant.TenantIdentityProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 构造指向 LiteLLM 的 {@link ChatModel}（OpenAI-compat）。
 *
 * <p>取代原单体 {@code LlmConfig} 里那串 provider switch —— provider/model 选择已下沉到网关。
 * 保留 {@link ChatModelListener} 灌入（指标 / 成本 / 审计 / token 预算等 per-tenant 归因仍在应用侧）。
 * temp=0 变体供 Judge / 确定性任务使用。
 *
 * <p><strong>租户归因（LiteLLM 侧记账）：</strong>所有构造出口（含 deterministic / 指定模型 /
 * cascade cheap·strong·rater / 流式）统一套 {@link TenantAwareChatModel} 包装 —— 三档开关见
 * {@link TenantAttributionMode}，默认 {@code none} 时包装器纯透传、行为与历史一致。virtual-key
 * 的动态 Authorization 与 trace 传播经 {@code customHeaders(Supplier)} 每次发送求值
 * （{@link GatewayRequestHeadersSupplier}）。
 */
public class GatewayChatModelFactory {

    /** 无 security 依赖时的身份兜底 —— 与 TenantContext.ANONYMOUS 的 tenantId 约定一致。 */
    public static final TenantIdentityProvider ANONYMOUS_IDENTITY = () -> "anonymous";

    private final GatewayClientProperties props;
    private final List<ChatModelListener> listeners;
    private final TenantIdentityProvider identities;
    private final Supplier<Map<String, String>> requestHeadersSupplier; // 可为 null（兼容构造）

    /** 向后兼容构造：无归因基础设施 —— 配 tenant-attribution=none 时行为与历史逐字一致。 */
    public GatewayChatModelFactory(GatewayClientProperties props, List<ChatModelListener> listeners) {
        this(props, listeners, ANONYMOUS_IDENTITY, null);
    }

    public GatewayChatModelFactory(GatewayClientProperties props, List<ChatModelListener> listeners,
                                   TenantIdentityProvider identities,
                                   Supplier<Map<String, String>> requestHeadersSupplier) {
        // fail-fast：virtual-key 档但没有动态 header 通道（如误用两参兼容构造）时，底层会静默
        // 沿用静态 master key —— 恰是业务规则禁止的"缺 key 回退 master key"。宁可构造期就炸。
        if (props.getTenantAttribution() == TenantAttributionMode.VIRTUAL_KEY && requestHeadersSupplier == null) {
            throw new IllegalStateException("platform.gateway.tenant-attribution=virtual-key 需要动态请求头通道"
                    + "（requestHeadersSupplier），不允许回退静态 master key —— 请使用四参构造并传入 "
                    + "GatewayRequestHeadersSupplier（Spring 装配路径自动满足）");
        }
        this.props = props;
        this.listeners = listeners == null ? List.of() : listeners;
        this.identities = identities == null ? ANONYMOUS_IDENTITY : identities;
        this.requestHeadersSupplier = requestHeadersSupplier;
    }

    /** 主模型：用配置里的温度。 */
    public ChatModel build() {
        return build(props.getModelName(), props.getTemperature());
    }

    /** temp=0 变体（Judge / 确定性任务）。 */
    public ChatModel buildDeterministic() {
        return build(props.getModelName(), 0.0);
    }

    /**
     * JSON mode 变体（response_format=json_object）：供每步必须产出合法 JSON 的结构化 AiService
     * （如 ReAct 决策核心）使用 —— 模型侧强制输出合法 JSON，字段值里的裸英文引号不再打断
     * Jackson 解析。要求端点/上游模型支持 OpenAI json_object（DeepSeek 支持，LiteLLM 透传）。
     */
    public ChatModel buildJsonMode() {
        return new TenantAwareChatModel(buildBase(props.getModelName(), props.getTemperature(), true),
                props.getTenantAttribution(), identities);
    }

    /** 指定逻辑模型名 + 温度（模型名对应 LiteLLM model_list 里的 model_name）。 */
    public ChatModel build(String modelName, Double temperature) {
        return new TenantAwareChatModel(buildBase(modelName, temperature),
                props.getTenantAttribution(), identities);
    }

    /**
     * 流式主模型（token 级 SSE）。同样指向 LiteLLM、灌入相同 {@link ChatModelListener}，
     * 使流式调用的指标 / 成本 / token 预算归因与同步链路一致。供 {@code /chat/stream}、
     * 流式反思 / 多 Agent 合成等使用。
     */
    public StreamingChatModel buildStreaming() {
        return buildStreaming(props.getModelName(), props.getTemperature());
    }

    /** 指定逻辑模型名 + 温度的流式变体。 */
    public StreamingChatModel buildStreaming(String modelName, Double temperature) {
        return new TenantAwareStreamingChatModel(buildStreamingBase(modelName, temperature),
                props.getTenantAttribution(), identities);
    }

    private OpenAiChatModel buildBase(String modelName, Double temperature) {
        return buildBase(modelName, temperature, false);
    }

    private OpenAiChatModel buildBase(String modelName, Double temperature, boolean jsonMode) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(temperature)
                .timeout(props.getTimeout())
                .maxRetries(props.getMaxRetries())
                .listeners(listeners)
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses());
        if (jsonMode) {
            builder.responseFormat("json_object");
        }
        if (requestHeadersSupplier != null) {
            builder.customHeaders(requestHeadersSupplier);
        }
        return builder.build();
    }

    private OpenAiStreamingChatModel buildStreamingBase(String modelName, Double temperature) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(temperature)
                .timeout(props.getTimeout())
                .listeners(listeners)
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses());
        if (requestHeadersSupplier != null) {
            builder.customHeaders(requestHeadersSupplier);
        }
        return builder.build();
    }
}
