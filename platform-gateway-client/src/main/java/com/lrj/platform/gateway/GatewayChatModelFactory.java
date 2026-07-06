package com.lrj.platform.gateway;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;

/**
 * 构造指向 LiteLLM 的 {@link ChatModel}（OpenAI-compat）。
 *
 * <p>取代原单体 {@code LlmConfig} 里那串 provider switch —— provider/model 选择已下沉到网关。
 * 保留 {@link ChatModelListener} 灌入（指标 / 成本 / 审计 / token 预算等 per-tenant 归因仍在应用侧）。
 * temp=0 变体供 Judge / 确定性任务使用。
 */
public class GatewayChatModelFactory {

    private final GatewayClientProperties props;
    private final List<ChatModelListener> listeners;

    public GatewayChatModelFactory(GatewayClientProperties props, List<ChatModelListener> listeners) {
        this.props = props;
        this.listeners = listeners == null ? List.of() : listeners;
    }

    /** 主模型：用配置里的温度。 */
    public ChatModel build() {
        return build(props.getModelName(), props.getTemperature());
    }

    /** temp=0 变体（Judge / 确定性任务）。 */
    public ChatModel buildDeterministic() {
        return build(props.getModelName(), 0.0);
    }

    /** 指定逻辑模型名 + 温度（模型名对应 LiteLLM model_list 里的 model_name）。 */
    public ChatModel build(String modelName, Double temperature) {
        return OpenAiChatModel.builder()
                .baseUrl(props.getBaseUrl())
                .apiKey(props.getApiKey())
                .modelName(modelName)
                .temperature(temperature)
                .timeout(props.getTimeout())
                .maxRetries(props.getMaxRetries())
                .listeners(listeners)
                .logRequests(props.isLogRequests())
                .logResponses(props.isLogResponses())
                .build();
    }
}
