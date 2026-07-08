package com.lrj.platform.conversation.history;

/**
 * History-aware 检索压缩（对齐单体 {@code CompressingQueryTransformer}）：把带指代的追问
 * （「它跟 X 啥区别」）结合多轮记忆历史，改写为自包含的检索 query（「Y 跟 X 啥区别」），
 * 再送 RAG 检索，避免追问因缺主语而检索错。
 *
 * <p>只影响「送给知识库的检索 query」——用户真实消息仍原样进入对话记忆与 LLM。
 * 默认 {@link NoopHistoryAwareQueryCompressor} 直通（返回 followUp 原文），零回归。
 */
public interface HistoryAwareQueryCompressor {

    /**
     * @param memoryKey 记忆键（{@code <tenantId>::<chatId>}），用于读取该会话历史
     * @param followUp  本轮用户消息（可能含指代）
     * @return 自包含的检索 query；无历史 / 未开启 / 出错时返回 {@code followUp} 原文
     */
    String compress(String memoryKey, String followUp);
}
