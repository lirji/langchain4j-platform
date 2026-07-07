package com.lrj.platform.gateway.cascade;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 成本级联 ChatModel：包裹「便宜模型 + 强模型」，先便宜后升级。
 *
 * <p>实现 {@link ChatModel}，所以任何吃 {@code ChatModel} 的地方（{@code AiServices.builder} / 直接
 * {@code chat()}）都能透明用上级联。<strong>被包裹的 cheap/strong 两个模型经
 * {@code GatewayChatModelFactory.build(modelName, temperature)} 程序化构造（各自都挂上了全部
 * {@code ChatModelListener}——审计 / 成本 / token 预算照常计量两条链），且本类<strong>不注册成第二个
 * 全局 ChatModel Bean</strong>（否则破坏 langchain4j {@code @AiService} 按类型自动发现，见
 * {@code CascadeChatModelFactory} 注释）。</strong>
 *
 * <p>流程：便宜模型作答 → {@link ConfidenceGate} 判置信 → 够用则返回便宜结果（{@code served=cheap}），
 * 否则强模型重答（{@code served=strong}）。便宜模型触发工具调用时直接返回（无文本可判、交给上层工具
 * 循环），不升级。指标经 {@link CascadeMetrics} 回调（{@code served=cheap|strong}）。
 */
public class CascadeChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CascadeChatModel.class);

    private final ChatModel cheap;
    private final ChatModel strong;
    private final ConfidenceGate gate;
    private final CascadeMetrics metrics;

    public CascadeChatModel(ChatModel cheap, ChatModel strong, ConfidenceGate gate, CascadeMetrics metrics) {
        this.cheap = cheap;
        this.strong = strong;
        this.gate = gate;
        this.metrics = metrics == null ? CascadeMetrics.noop() : metrics;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return escalate(request).response();
    }

    /**
     * 级联主逻辑：返回最终 {@link ChatResponse} + 由谁作答 + 便宜模型是否被判置信。
     */
    public Outcome escalate(ChatRequest request) {
        ChatResponse cheapResp = cheap.chat(request);

        // 便宜模型直接要调工具：没有可判的文本，交回上层工具循环，不升级。
        if (cheapResp.aiMessage() != null && cheapResp.aiMessage().hasToolExecutionRequests()) {
            served("cheap");
            return new Outcome(cheapResp, "cheap", true);
        }

        String question = lastUserText(request);
        String cheapText = cheapResp.aiMessage() == null ? null : cheapResp.aiMessage().text();

        if (gate.isConfident(question, cheapText)) {
            served("cheap");
            return new Outcome(cheapResp, "cheap", true);
        }

        log.debug("cascade: cheap answer low-confidence, escalating to strong model");
        ChatResponse strongResp = strong.chat(request);
        served("strong");
        return new Outcome(strongResp, "strong", false);
    }

    private void served(String who) {
        metrics.served(who);
    }

    /** 取请求里最后一条 UserMessage 的文本（自评用）；取不到返回空串。 */
    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage um && um.hasSingleText()) {
                return um.singleText();
            }
        }
        return "";
    }

    /**
     * 级联单次结果。
     *
     * @param response       返回给调用方的最终响应
     * @param served         "cheap" | "strong"，谁最终作答
     * @param cheapConfident 便宜模型答案是否被判置信（true 时 served 必为 cheap）
     */
    public record Outcome(ChatResponse response, String served, boolean cheapConfident) {
    }
}
