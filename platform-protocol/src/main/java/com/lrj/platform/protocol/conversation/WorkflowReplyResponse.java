package com.lrj.platform.protocol.conversation;

/**
 * conversation → workflow 的「答复生成」响应契约（C2）。
 *
 * @param reply 生成好的、给用户的中文答复文本
 */
public record WorkflowReplyResponse(String reply) {
}
