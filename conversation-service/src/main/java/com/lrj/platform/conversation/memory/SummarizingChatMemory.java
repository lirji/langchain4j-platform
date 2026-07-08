package com.lrj.platform.conversation.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 摘要滑窗记忆（对齐单体 {@code SummarizingChatMemory}）：保留最近 {@code maxMessages} 条对话消息，
 * 更早的溢出消息经 {@code summarizer} 压缩为一条 {@link SystemMessage} 摘要，随对话增长滚动更新。
 *
 * <p>与 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}（直接丢弃旧消息）不同：这里把旧消息
 * 压缩进摘要而非丢弃，长对话仍保留早期上下文的要点。系统摘要消息始终置于最前，不计入 {@code maxMessages}。
 *
 * <p>{@code summarizer} 由 {@link ChatMemoryConfig} 注入为「调网关 ChatModel 压缩」，测试注入确定性桩函数，
 * 因此本类纯逻辑可单测、不连模型。
 */
public class SummarizingChatMemory implements ChatMemory {

    /** 摘要系统消息前缀，便于识别与调试。 */
    static final String SUMMARY_PREFIX = "[对话摘要] ";

    private final Object id;
    private final ChatMemoryStore store;
    private final int maxMessages;
    private final Function<String, String> summarizer;

    public SummarizingChatMemory(Object id, ChatMemoryStore store, int maxMessages,
                                 Function<String, String> summarizer) {
        this.id = id;
        this.store = store;
        this.maxMessages = Math.max(1, maxMessages);
        this.summarizer = summarizer;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        List<ChatMessage> all = new ArrayList<>(store.getMessages(id));
        all.add(message);
        store.updateMessages(id, compress(all));
    }

    @Override
    public List<ChatMessage> messages() {
        return store.getMessages(id);
    }

    @Override
    public void clear() {
        store.deleteMessages(id);
    }

    /**
     * 把消息列表压到窗口内：拆出既有摘要（最新的 SystemMessage）与对话消息；对话消息若超过 {@code maxMessages}，
     * 把最旧的溢出部分连同既有摘要交给 {@code summarizer} 生成新摘要，只保留最近 {@code maxMessages} 条对话。
     */
    private List<ChatMessage> compress(List<ChatMessage> all) {
        SystemMessage summary = null;
        List<ChatMessage> convo = new ArrayList<>();
        for (ChatMessage m : all) {
            if (m instanceof SystemMessage sm) {
                summary = sm; // 只保留最新系统摘要
            } else {
                convo.add(m);
            }
        }
        if (convo.size() <= maxMessages) {
            return withSummary(summary, convo);
        }
        int overflow = convo.size() - maxMessages;
        List<ChatMessage> toSummarize = new ArrayList<>(convo.subList(0, overflow));
        List<ChatMessage> recent = new ArrayList<>(convo.subList(overflow, convo.size()));
        String prior = summary == null ? "" : summary.text();
        String newSummary = summarizer.apply(renderForSummary(prior, toSummarize));
        SystemMessage merged = SystemMessage.from(SUMMARY_PREFIX + safe(newSummary));
        return withSummary(merged, recent);
    }

    private static List<ChatMessage> withSummary(SystemMessage summary, List<ChatMessage> convo) {
        List<ChatMessage> out = new ArrayList<>(convo.size() + 1);
        if (summary != null) {
            out.add(summary);
        }
        out.addAll(convo);
        return out;
    }

    /** 把「既有摘要 + 待压缩消息」拼成喂给摘要器的纯文本。 */
    private static String renderForSummary(String prior, List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isBlank()) {
            sb.append(prior.replaceFirst("^" + java.util.regex.Pattern.quote(SUMMARY_PREFIX), "")).append('\n');
        }
        for (ChatMessage m : messages) {
            sb.append(role(m)).append(": ").append(text(m)).append('\n');
        }
        return sb.toString().trim();
    }

    private static String role(ChatMessage m) {
        if (m instanceof UserMessage) {
            return "用户";
        }
        if (m instanceof AiMessage) {
            return "助手";
        }
        return "系统";
    }

    private static String text(ChatMessage m) {
        if (m instanceof UserMessage um) {
            return um.singleText();
        }
        if (m instanceof AiMessage am) {
            return am.text() == null ? "" : am.text();
        }
        if (m instanceof SystemMessage sm) {
            return sm.text();
        }
        return String.valueOf(m);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
