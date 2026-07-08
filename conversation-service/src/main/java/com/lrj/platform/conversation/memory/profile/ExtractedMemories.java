package com.lrj.platform.conversation.memory.profile;

import java.util.List;

/**
 * 一轮对话抽取出的用户长期事实集合（可为空——多数对话没有值得跨会话记住的内容）。
 */
public record ExtractedMemories(List<MemoryFact> facts) {
}
