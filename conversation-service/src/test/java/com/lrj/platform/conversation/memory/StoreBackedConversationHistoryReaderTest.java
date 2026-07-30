package com.lrj.platform.conversation.memory;

import com.lrj.platform.protocol.conversation.ConversationHistoryMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoreBackedConversationHistoryReaderTest {

    @Test
    void returnsNewestBoundedMessagesInChronologicalOrder() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        when(store.getMessages("acme::c1")).thenReturn(List.of(
                UserMessage.from("u1"),
                AiMessage.from("a1"),
                UserMessage.from("u2"),
                AiMessage.from("a2")));
        StoreBackedConversationHistoryReader reader =
                new StoreBackedConversationHistoryReader(store, 3, 100, 100);

        assertThat(reader.snapshot("acme", "c1")).containsExactly(
                new ConversationHistoryMessage("assistant", "a1"),
                new ConversationHistoryMessage("user", "u2"),
                new ConversationHistoryMessage("assistant", "a2"));
    }

    @Test
    void independentlyBoundsTotalAndPerMessageCharacters() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        when(store.getMessages("acme::c1")).thenReturn(List.of(
                SystemMessage.from("old-system"),
                UserMessage.from("older"),
                AiMessage.from("abcdef")));

        StoreBackedConversationHistoryReader perMessageBound =
                new StoreBackedConversationHistoryReader(store, 10, 100, 3);
        StoreBackedConversationHistoryReader totalBound =
                new StoreBackedConversationHistoryReader(store, 10, 4, 100);

        assertThat(perMessageBound.snapshot("acme", "c1")).extracting(
                ConversationHistoryMessage::content).containsExactly("old", "old", "abc");
        assertThat(totalBound.snapshot("acme", "c1")).containsExactly(
                new ConversationHistoryMessage("assistant", "abcd"));
    }

    @Test
    void tenantAndChatIdAreComposedInsideReader() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        when(store.getMessages("tenant-a::same")).thenReturn(List.of(UserMessage.from("a")));
        when(store.getMessages("tenant-b::same")).thenReturn(List.of(UserMessage.from("b")));
        StoreBackedConversationHistoryReader reader =
                new StoreBackedConversationHistoryReader(store, 10, 100, 100);

        assertThat(reader.snapshot("tenant-a", "same")).containsExactly(
                new ConversationHistoryMessage("user", "a"));

        verify(store).getMessages("tenant-a::same");
    }

    @Test
    void zeroLimitsDisableSnapshotWithoutReadingStore() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        StoreBackedConversationHistoryReader reader =
                new StoreBackedConversationHistoryReader(store, 0, 100, 100);

        assertThat(reader.snapshot("acme", "c1")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void operatorLimitsCannotExceedContractHardCaps() {
        ChatMemoryStore store = mock(ChatMemoryStore.class);
        List<dev.langchain4j.data.message.ChatMessage> messages = IntStream.range(0, 40)
                .mapToObj(index -> UserMessage.from("x".repeat(5000)))
                .map(dev.langchain4j.data.message.ChatMessage.class::cast)
                .toList();
        when(store.getMessages("acme::c1")).thenReturn(messages);
        StoreBackedConversationHistoryReader reader =
                new StoreBackedConversationHistoryReader(store, 100, 1_000_000, 100_000);

        List<ConversationHistoryMessage> result = reader.snapshot("acme", "c1");

        assertThat(result).hasSize(StoreBackedConversationHistoryReader.CONTRACT_MAX_MESSAGES);
        assertThat(result).allSatisfy(message ->
                assertThat(message.content()).hasSize(
                        StoreBackedConversationHistoryReader.CONTRACT_MAX_MESSAGE_CHARS));
    }
}
