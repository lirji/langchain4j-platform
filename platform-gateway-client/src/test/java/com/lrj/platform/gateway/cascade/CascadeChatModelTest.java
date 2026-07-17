package com.lrj.platform.gateway.cascade;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CascadeChatModelTest：验证 {@link CascadeChatModel} 的廉价→强模型级联升级逻辑——低置信
 * （{@link ConfidenceGate} 判否）时升级到强模型、高置信时沿用廉价答案且不调用强模型、廉价模型直接
 * 返回工具调用时短路交回上层工具循环（既不咨询 gate 也不升级）。
 */
class CascadeChatModelTest {

    private static final ChatRequest REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("问题"))
            .build();

    @Test
    void lowConfidenceEscalatesToStrong() {
        AtomicInteger cheapCalls = new AtomicInteger();
        AtomicInteger strongCalls = new AtomicInteger();
        List<String> served = new ArrayList<>();
        CascadeChatModel cascade = new CascadeChatModel(
                textModel("便宜答案", cheapCalls),
                textModel("强模型答案", strongCalls),
                gate(false),
                served::add);

        CascadeChatModel.Outcome outcome = cascade.escalate(REQUEST);

        assertThat(outcome.served()).isEqualTo("strong");
        assertThat(outcome.cheapConfident()).isFalse();
        assertThat(outcome.response().aiMessage().text()).isEqualTo("强模型答案");
        assertThat(cheapCalls.get()).isEqualTo(1);
        assertThat(strongCalls.get()).isEqualTo(1);
        assertThat(served).containsExactly("strong");
    }

    @Test
    void highConfidenceKeepsCheapAndDoesNotCallStrong() {
        AtomicInteger cheapCalls = new AtomicInteger();
        AtomicInteger strongCalls = new AtomicInteger();
        List<String> served = new ArrayList<>();
        CascadeChatModel cascade = new CascadeChatModel(
                textModel("便宜答案", cheapCalls),
                textModel("强模型答案", strongCalls),
                gate(true),
                served::add);

        CascadeChatModel.Outcome outcome = cascade.escalate(REQUEST);

        assertThat(outcome.served()).isEqualTo("cheap");
        assertThat(outcome.cheapConfident()).isTrue();
        assertThat(outcome.response().aiMessage().text()).isEqualTo("便宜答案");
        assertThat(strongCalls.get()).isZero();
        assertThat(served).containsExactly("cheap");
    }

    @Test
    void cheapToolCallShortCircuitsWithoutGateOrEscalation() {
        AtomicInteger strongCalls = new AtomicInteger();
        // 便宜模型直接要调工具：无文本可判、不升级，交回上层工具循环。若真的调用 gate 会抛错。
        ConfidenceGate explodingGate = new ConfidenceGate(new CascadeProperties()) {
            @Override
            public boolean isConfident(String question, String answer) {
                throw new AssertionError("gate must not be consulted on tool-call responses");
            }
        };
        ChatModel cheap = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(
                                ToolExecutionRequest.builder().name("t").arguments("{}").build()))
                        .build();
            }
        };
        CascadeChatModel cascade = new CascadeChatModel(
                cheap, textModel("强模型答案", strongCalls), explodingGate, CascadeMetrics.noop());

        CascadeChatModel.Outcome outcome = cascade.escalate(REQUEST);

        assertThat(outcome.served()).isEqualTo("cheap");
        assertThat(outcome.cheapConfident()).isTrue();
        assertThat(strongCalls.get()).isZero();
    }

    private static ChatModel textModel(String text, AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }

    /** 覆盖 isConfident 以固定置信判定——满足「mock ConfidenceGate」。 */
    private static ConfidenceGate gate(boolean confident) {
        return new ConfidenceGate(new CascadeProperties()) {
            @Override
            public boolean isConfident(String question, String answer) {
                return confident;
            }
        };
    }
}
