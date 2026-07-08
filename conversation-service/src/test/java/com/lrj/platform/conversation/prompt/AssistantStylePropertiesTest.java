package com.lrj.platform.conversation.prompt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantStylePropertiesTest {

    private static AssistantStyleProperties base() {
        AssistantStyleProperties p = new AssistantStyleProperties();
        p.setLanguage("中文");
        p.setTone("简洁");
        p.setCitationPolicy("cite-base");
        p.setExtra("");
        return p;
    }

    @Test
    void noOverride_usesBaseDefaults() {
        ResolvedAssistantStyle style = base().resolve("chat-default");
        assertThat(style.language()).isEqualTo("中文");
        assertThat(style.tone()).isEqualTo("简洁");
        assertThat(style.citationPolicy()).isEqualTo("cite-base");
        assertThat(style.extra()).isEmpty();
    }

    @Test
    void override_forMatchingModel_mergesFieldLevel() {
        AssistantStyleProperties p = base();
        AssistantStyleProperties.Override ov = new AssistantStyleProperties.Override();
        ov.setTone("更口语");            // 只覆盖 tone
        ov.setExtra("XML 标签更敏感");    // 覆盖 extra
        p.setOverrides(Map.of("deepseek-chat", ov));

        ResolvedAssistantStyle style = p.resolve("deepseek-chat");

        assertThat(style.tone()).isEqualTo("更口语");           // 覆盖生效
        assertThat(style.extra()).isEqualTo("XML 标签更敏感");
        assertThat(style.language()).isEqualTo("中文");          // 未覆盖字段回退默认
        assertThat(style.citationPolicy()).isEqualTo("cite-base");
    }

    @Test
    void override_forDifferentModel_ignored() {
        AssistantStyleProperties p = base();
        AssistantStyleProperties.Override ov = new AssistantStyleProperties.Override();
        ov.setTone("更口语");
        p.setOverrides(Map.of("deepseek-chat", ov));

        // 请求的是别的模型 → 覆盖不命中，全用默认
        ResolvedAssistantStyle style = p.resolve("chat-default");
        assertThat(style.tone()).isEqualTo("简洁");
    }

    @Test
    void override_nullField_fallsBackToDefault_emptyStringClears() {
        AssistantStyleProperties p = base();
        p.setExtra("base-extra");
        AssistantStyleProperties.Override ov = new AssistantStyleProperties.Override();
        ov.setExtra("");                 // 空串=真正清空
        // tone 为 null → 回退默认
        p.setOverrides(Map.of("m1", ov));

        ResolvedAssistantStyle style = p.resolve("m1");
        assertThat(style.extra()).isEmpty();          // 空串覆盖
        assertThat(style.tone()).isEqualTo("简洁");    // null 回退默认
    }
}
