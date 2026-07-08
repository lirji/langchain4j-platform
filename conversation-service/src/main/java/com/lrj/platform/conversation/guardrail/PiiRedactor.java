package com.lrj.platform.conversation.guardrail;

import java.util.regex.Pattern;

/**
 * PII 脱敏（移植单体 {@code PiiDetector}）：邮箱 / 中国手机号 / 18 位身份证 → {@code [REDACTED-类别]}。
 * 纯静态、无状态，便于直接单测。
 *
 * <p>平台采用「就地脱敏」而非单体的 output-guardrail reprompt 重写：确定性、保证命中一定被遮蔽，
 * 且流式/非流式一致（reprompt 需缓冲整段、无法用于 token 流）。
 */
public final class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE_CN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private PiiRedactor() {
    }

    /** 返回首个命中的 PII 类别（{@code email|phone|id-card}），无命中返回 null。 */
    public static String firstHit(String text) {
        if (text == null) {
            return null;
        }
        if (EMAIL.matcher(text).find()) {
            return "email";
        }
        if (PHONE_CN.matcher(text).find()) {
            return "phone";
        }
        if (ID_CN.matcher(text).find()) {
            return "id-card";
        }
        return null;
    }

    /** 把所有 email / 手机号 / 身份证号替换为 {@code [REDACTED-类别]}；无命中原样返回。 */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = ID_CN.matcher(text).replaceAll("[REDACTED-id-card]");
        out = EMAIL.matcher(out).replaceAll("[REDACTED-email]");
        out = PHONE_CN.matcher(out).replaceAll("[REDACTED-phone]");
        return out;
    }
}
