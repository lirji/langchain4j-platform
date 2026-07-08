package com.lrj.platform.conversation.history;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3.3 History-aware 压缩确定性单测：LLM 藏在 {@link UnaryOperator} 桩后，不连模型。
 */
class LlmHistoryAwareQueryCompressorTest {

    private static final String KEY = "acme::c1";

    @Test
    void emptyHistory_passesThroughFollowUp() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(
                store, s -> "SHOULD-NOT-BE-CALLED", 10);

        // 首轮无历史 → 直通，rewriter 不被调用
        assertThat(c.compress(KEY, "什么是 RAG")).isEqualTo("什么是 RAG");
    }

    @Test
    void blankFollowUp_passesThrough() {
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(
                new InMemoryChatMemoryStore(), s -> "x", 10);
        assertThat(c.compress(KEY, "   ")).isEqualTo("   ");
    }

    @Test
    void withHistory_rewritesUsingRenderedHistoryAndReturnsResult() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages(KEY, List.of(
                UserMessage.from("Milvus 是什么"),
                AiMessage.from("Milvus 是一个向量数据库。")));
        AtomicReference<String> captured = new AtomicReference<>();
        UnaryOperator<String> rewriter = prompt -> {
            captured.set(prompt);
            return "Milvus 和 Qdrant 的区别";
        };
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(store, rewriter, 10);

        String out = c.compress(KEY, "它跟 Qdrant 啥区别");

        assertThat(out).isEqualTo("Milvus 和 Qdrant 的区别");
        // prompt 里带上了渲染后的历史（角色标签 + 原文）与本轮追问
        assertThat(captured.get())
                .contains("用户: Milvus 是什么")
                .contains("助手: Milvus 是一个向量数据库。")
                .contains("它跟 Qdrant 啥区别");
    }

    @Test
    void rewriterException_degradesToFollowUp() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages(KEY, List.of(UserMessage.from("前文")));
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(store, s -> {
            throw new RuntimeException("model down");
        }, 10);

        assertThat(c.compress(KEY, "它是啥")).isEqualTo("它是啥");
    }

    @Test
    void blankRewrite_degradesToFollowUp() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages(KEY, List.of(UserMessage.from("前文")));
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(store, s -> "  ", 10);

        assertThat(c.compress(KEY, "它是啥")).isEqualTo("它是啥");
    }

    @Test
    void stripsSurroundingQuotesFromRewrite() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages(KEY, List.of(UserMessage.from("前文")));
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(
                store, s -> "\"自包含查询\"", 10);

        assertThat(c.compress(KEY, "它是啥")).isEqualTo("自包含查询");
    }

    @Test
    void onlyMostRecentMessagesRendered() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages(KEY, List.of(
                UserMessage.from("最早消息-OLDEST"),
                UserMessage.from("中间消息"),
                UserMessage.from("最近消息-NEWEST")));
        AtomicReference<String> captured = new AtomicReference<>();
        LlmHistoryAwareQueryCompressor c = new LlmHistoryAwareQueryCompressor(store, prompt -> {
            captured.set(prompt);
            return "out";
        }, 2); // 只取最近 2 条

        c.compress(KEY, "追问");

        assertThat(captured.get()).contains("中间消息").contains("最近消息-NEWEST");
        assertThat(captured.get()).doesNotContain("最早消息-OLDEST");
    }
}
