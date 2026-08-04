package com.lrj.platform.conversation.guardrail;

/**
 * 跨 token 的流式 PII 脱敏器。模型可能把手机号、身份证或邮箱拆到多个 token；直接逐 token 调
 * {@link PiiRedactor} 会在完整模式形成前先把原文发给客户端。本类保留尚可能组成 PII 的尾段，遇到
 * 明确分隔符或流结束后才交给统一 redactor。
 */
public final class StreamingPiiRedactor {

    // RFC 邮箱最长 254 字符；保留 320 字符足以让一个合法邮箱以及固定长度手机号/身份证不跨 flush。
    private static final int RETAINED_CANDIDATE_CHARS = 320;
    private final ConversationGuardrail guardrail;
    private final StringBuilder pending = new StringBuilder();

    public StreamingPiiRedactor(ConversationGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    /** 接收一个模型 token，返回当前可以安全发送的文本；空串表示继续缓冲。 */
    public String accept(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        if (!guardrail.redactsPii()) {
            return token;
        }
        pending.append(token);
        int separator = lastSeparator(pending);
        if (separator >= 0) {
            return drain(separator + 1);
        }
        if (pending.length() > RETAINED_CANDIDATE_CHARS) {
            return drain(pending.length() - RETAINED_CANDIDATE_CHARS);
        }
        return "";
    }

    /** 流正常完成时脱敏并返回剩余尾段。错误/断开时调用方应直接丢弃 pending。 */
    public String finish() {
        return drain(pending.length());
    }

    private String drain(int length) {
        if (length <= 0) {
            return "";
        }
        String safe = guardrail.redactOutput(pending.substring(0, length));
        pending.delete(0, length);
        return safe;
    }

    private static int lastSeparator(CharSequence value) {
        for (int index = value.length() - 1; index >= 0; index--) {
            if (!isPiiCandidateCharacter(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isPiiCandidateCharacter(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '@'
                || value == '.'
                || value == '_'
                || value == '+'
                || value == '-';
    }
}
