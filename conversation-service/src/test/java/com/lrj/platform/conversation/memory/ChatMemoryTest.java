package com.lrj.platform.conversation.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chat Memory 三滑窗 + store 隔离的确定性单测（不连模型：summary 注入桩函数）。
 */
class ChatMemoryTest {

    /** messages 窗口：只保留最近 N 条，旧的直接丢。 */
    @Test
    void messagesWindow_keepsOnlyRecent() {
        ChatMemoryProvider provider = ChatMemoryConfig.messagesProvider(new InMemoryChatMemoryStore(), 2);
        ChatMemory memory = provider.get("acme::c1");

        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2"));
        memory.add(AiMessage.from("a2"));

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(2);
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("a2");
    }

    /** tokens 窗口：本地 tokenizer（离线）能构建并正常累积消息。 */
    @Test
    void tokensWindow_buildsAndStores() {
        ChatMemoryProvider provider =
                ChatMemoryConfig.tokensProvider(new InMemoryChatMemoryStore(), 1000, "gpt-4o-mini");
        ChatMemory memory = provider.get("acme::c1");

        memory.add(UserMessage.from("你好"));
        assertThat(memory.messages()).isNotEmpty();
    }

    /** 不同 memoryId（含租户前缀）在同一 store 内互不串。 */
    @Test
    void store_isolatesByMemoryId() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        ChatMemoryProvider provider = ChatMemoryConfig.messagesProvider(store, 10);

        provider.get("acme::c1").add(UserMessage.from("acme-msg"));
        provider.get("globex::c1").add(UserMessage.from("globex-msg"));

        assertThat(provider.get("acme::c1").messages()).hasSize(1);
        assertThat(((UserMessage) provider.get("acme::c1").messages().get(0)).singleText())
                .isEqualTo("acme-msg");
        assertThat(((UserMessage) provider.get("globex::c1").messages().get(0)).singleText())
                .isEqualTo("globex-msg");
    }

    /** summary 窗口：溢出的旧消息被压成一条系统摘要置顶，最近消息原样保留。 */
    @Test
    void summaryWindow_compressesOverflowIntoSystemSummary() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        // 确定性桩摘要器：把待压缩文本包起来，便于断言其内容
        Function<String, String> stub = raw -> "S{" + raw.replace("\n", "|") + "}";
        SummarizingChatMemory memory = new SummarizingChatMemory("acme::c1", store, 2, stub);

        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2")); // 触发溢出：u1 被压缩

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        SystemMessage summary = (SystemMessage) messages.get(0);
        assertThat(summary.text())
                .startsWith(SummarizingChatMemory.SUMMARY_PREFIX)
                .contains("用户: u1");
        // 最近两条对话原样保留
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("a1");
        assertThat(((UserMessage) messages.get(2)).singleText()).isEqualTo("u2");
    }

    /** summary 窗口：再次溢出时，新摘要吸收上一版摘要（滚动累积，不丢早期要点）。 */
    @Test
    void summaryWindow_rollingSummaryAbsorbsPrior() {
        InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
        Function<String, String> stub = raw -> "S{" + raw.replace("\n", "|") + "}";
        SummarizingChatMemory memory = new SummarizingChatMemory("acme::c1", store, 2, stub);

        memory.add(UserMessage.from("u1"));
        memory.add(AiMessage.from("a1"));
        memory.add(UserMessage.from("u2")); // 压缩 u1
        memory.add(AiMessage.from("a2"));   // 压缩 a1，新摘要吸收上版

        SystemMessage summary = (SystemMessage) memory.messages().get(0);
        assertThat(summary.text()).contains("用户: u1").contains("助手: a1");
        assertThat(memory.messages()).hasSize(3); // summary + 最近2条
    }
}
