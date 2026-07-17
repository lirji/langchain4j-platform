package com.lrj.platform.agent;

/**
 * 可选的 scratchpad 摘要器。当 {@link DeepAgentService} 的笔记超出 {@code maxScratchpadChars} 上限时，
 * 用它把较早的结论压成一段摘要以腾出空间；未提供实现时则直接丢弃最旧的行。
 */
public interface ScratchpadSummarizer {

    String summarize(String notes);
}
