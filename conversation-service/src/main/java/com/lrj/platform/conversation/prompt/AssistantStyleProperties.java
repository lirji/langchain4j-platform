package com.lrj.platform.conversation.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 外置化对话助手风格（对齐单体 {@code AssistantProperties}）：把系统提示里的
 * language / tone / citation-policy / extra 从代码里挪到配置，改 yml + 重启即可调，不改 Java。
 *
 * <p><b>Per-model override</b>：v2 provider 路由在 LiteLLM，无单一 provider；故 {@link #overrides} 按
 * <b>LiteLLM 逻辑模型名</b>（{@code platform.gateway.model-name}）作 key。字段级 null 回退到顶层默认
 * （null=继承默认，空串=真正清空）。
 */
@ConfigurationProperties(prefix = "app.conversation.assistant")
public class AssistantStyleProperties {

    /** 回答语言。 */
    private String language = "中文";

    /** 语气/风格。 */
    private String tone = "简洁，1–2 句话答完，必要时再展开";

    /** 引用与来源处理策略（互斥三档，供 grounding Layer0 的 [doc=ID] 引用核对配套）。 */
    private String citationPolicy = """
            引用与来源处理（按以下情况分别处理，互斥）：
              1) 如果系统检索到了文档片段并用于回答，必须用 [doc=文件名#片段号] 形式标注来源。
              2) 如果用户的问题明确指向文档/知识库（含『文档』『手册』『文献』『资料里』『根据上述材料』等线索），
                 但没有检索到相关内容，回复『未在文档中找到相关内容』。
              3) 其他情况（用户在本轮提供了信息、闲聊、工具调用、定义性问题等），
                 直接根据用户问题与已有上下文作答 —— 不要加『资料里没有提到 X』之类的免责前言，也不要声明检索状态。""";

    /** 灰度/临时追加指令槽位。 */
    private String extra = "";

    /** 按 LiteLLM 逻辑模型名的覆盖项。 */
    private Map<String, Override> overrides = new HashMap<>();

    /**
     * 针对给定逻辑模型名解析出最终风格；无覆盖项直接用顶层默认，有则字段级 null 回退。
     */
    public ResolvedAssistantStyle resolve(String modelName) {
        Override ov = overrides == null ? null : overrides.get(modelName);
        if (ov == null) {
            return new ResolvedAssistantStyle(language, tone, citationPolicy, extra);
        }
        return new ResolvedAssistantStyle(
                ov.getLanguage() != null ? ov.getLanguage() : language,
                ov.getTone() != null ? ov.getTone() : tone,
                ov.getCitationPolicy() != null ? ov.getCitationPolicy() : citationPolicy,
                ov.getExtra() != null ? ov.getExtra() : extra);
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getTone() {
        return tone;
    }

    public void setTone(String tone) {
        this.tone = tone;
    }

    public String getCitationPolicy() {
        return citationPolicy;
    }

    public void setCitationPolicy(String citationPolicy) {
        this.citationPolicy = citationPolicy;
    }

    public String getExtra() {
        return extra;
    }

    public void setExtra(String extra) {
        this.extra = extra;
    }

    public Map<String, Override> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, Override> overrides) {
        this.overrides = overrides;
    }

    /** 单个逻辑模型的覆盖项。字段 null=继承默认；空串=真正清空。 */
    public static class Override {
        private String language;
        private String tone;
        private String citationPolicy;
        private String extra;

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getTone() {
            return tone;
        }

        public void setTone(String tone) {
            this.tone = tone;
        }

        public String getCitationPolicy() {
            return citationPolicy;
        }

        public void setCitationPolicy(String citationPolicy) {
            this.citationPolicy = citationPolicy;
        }

        public String getExtra() {
            return extra;
        }

        public void setExtra(String extra) {
            this.extra = extra;
        }
    }
}
