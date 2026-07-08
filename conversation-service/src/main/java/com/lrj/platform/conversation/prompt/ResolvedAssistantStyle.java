package com.lrj.platform.conversation.prompt;

/**
 * 解析后的不可变对话风格，注入 controller 后逐次经 {@code @V} 填进 {@code Assistant} 系统提示占位符。
 * 同时提供 record 访问器与 JavaBean getter（后者便于日志/调试）。
 */
public record ResolvedAssistantStyle(String language, String tone, String citationPolicy, String extra) {

    public String getLanguage() {
        return language;
    }

    public String getTone() {
        return tone;
    }

    public String getCitationPolicy() {
        return citationPolicy;
    }

    public String getExtra() {
        return extra;
    }
}
