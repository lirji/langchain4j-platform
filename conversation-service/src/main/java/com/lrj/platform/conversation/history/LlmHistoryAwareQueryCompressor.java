package com.lrj.platform.conversation.history;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * LLM 版 history-aware 压缩：从 {@link ChatMemoryStore} 读该会话历史，取最近 N 条渲染为文本，
 * 连同本轮追问交给 {@code rewriter}（网关 ChatModel），改写为自包含的检索 query。
 *
 * <p>模型藏在 {@link UnaryOperator}{@code <String>} 后（对齐 {@code LlmQueryExpander}），单测可注入确定性桩、不连模型。
 * 空历史 / 空追问 / 异常一律降级为原 {@code followUp}——检索永不因压缩失败而中断。
 */
public class LlmHistoryAwareQueryCompressor implements HistoryAwareQueryCompressor {

    private static final Logger log = LoggerFactory.getLogger(LlmHistoryAwareQueryCompressor.class);

    private final ChatMemoryStore store;
    private final UnaryOperator<String> rewriter;
    private final int maxHistoryMessages;

    public LlmHistoryAwareQueryCompressor(ChatMemoryStore store, UnaryOperator<String> rewriter,
                                          int maxHistoryMessages) {
        this.store = store;
        this.rewriter = rewriter;
        this.maxHistoryMessages = Math.max(1, maxHistoryMessages);
    }

    @Override
    public String compress(String memoryKey, String followUp) {
        if (followUp == null || followUp.isBlank() || memoryKey == null) {
            return followUp;
        }
        try {
            String history = renderHistory(store.getMessages(memoryKey));
            if (history.isBlank()) {
                return followUp; // 首轮无历史，追问即自包含
            }
            String rewritten = rewriter.apply(prompt(history, followUp));
            String cleaned = clean(rewritten);
            return cleaned.isBlank() ? followUp : cleaned;
        } catch (RuntimeException e) {
            log.warn("history-aware compression failed, falling back to raw query: {}", e.toString());
            return followUp;
        }
    }

    /** 取最近 {@code maxHistoryMessages} 条，渲染为「角色: 文本」多行（对齐 SummarizingChatMemory 的角色标签）。 */
    private String renderHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        int from = Math.max(0, messages.size() - maxHistoryMessages);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages.subList(from, messages.size())) {
            String text = text(m);
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(role(m)).append(": ").append(text.trim()).append('\n');
        }
        return sb.toString().trim();
    }

    static String prompt(String history, String followUp) {
        return """
                你是检索 query 改写器。根据下面的对话历史，把用户「最新问题」改写为一个**自包含**的检索查询：
                补全其中的指代（它/这个/那个/上面说的等）与省略的主语，使其脱离上下文也能独立检索。
                只输出改写后的查询本身，不要解释、不要引号、不要多余前后缀。若最新问题本身已自包含，原样输出。

                # 对话历史
                %s

                # 最新问题
                %s

                # 自包含检索查询
                """.formatted(history, followUp);
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        // 去掉模型可能加的成对引号
        if (t.length() >= 2
                && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("「") && t.endsWith("」")))) {
            t = t.substring(1, t.length() - 1).strip();
        }
        return t;
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
            return am.text();
        }
        if (m instanceof SystemMessage sm) {
            return sm.text();
        }
        return null;
    }
}
