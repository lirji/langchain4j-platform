package com.lrj.platform.vision;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.vision.*} 绑定。<strong>默认关</strong>（{@code enabled=false}）→ 整个 vision 链
 * （{@link VisionConfig} / {@code VisionController}）都不装配，零开销、零网络依赖。
 *
 * <p>视觉模型与主文本模型解耦：两者共用同一 LiteLLM base-url（见 {@code platform.gateway.base-url}），
 * 仅逻辑模型名不同（{@link #modelName} 指向 LiteLLM model_list 里的多模态模型）。开启但未配 modelName
 * → {@link VisionConfig} 启动 fail-fast，绝不静默降级到文本模型。
 */
@ConfigurationProperties(prefix = "app.vision")
public class VisionProperties {

    /** 总开关。关闭（默认）时整个 vision 链不装配。 */
    private boolean enabled = false;

    /** 视觉逻辑模型名（LiteLLM model_list 的多模态 model_name）。开启时必填，否则 fail-fast。 */
    private String modelName = "";

    /** temperature：看图转写/描述偏确定性，默认压低到 0.2。 */
    private Double temperature = 0.2;

    /** 单张图片字节上限，挡超大文件 OOM / 超 token。默认 10MB。 */
    private long maxImageBytes = 10_485_760L;

    /** 允许的图片 MIME（逗号分隔）。空 = 不限类型（仅按 {@code image/} 前缀兜底）。 */
    private String allowedMimeTypes = "image/png,image/jpeg,image/webp,image/gif";

    /**
     * caption 结果缓存条数（按图内容 SHA-256 去重）。同一张图重复上传不再重复烧视觉调用。
     * 0 = 关闭缓存。只缓存默认指令的入库路径，不缓存带具体问题的视觉问答。
     */
    private int captionCacheSize = 256;

    /**
     * 入库/OCR 用的默认指令：既描述图像语义、又转写可见文字，<strong>一次调用同时覆盖
     * 「图像理解」与「OCR 转写」</strong>。指令留空的请求走这条。
     */
    private String captionPrompt = """
            你是文档理解助手。请用中文详尽描述这张图片，并满足：
            1) 概述图像主题与可见的关键对象/场景；
            2) 如果是图表（柱状/折线/饼图/表格等），说明它表达的数据趋势与关键数值；
            3) 逐字转写图中所有可见文字（OCR），保留原文语言，不要翻译；
            4) 只陈述图中真实可见的内容，不要臆测或补充图外信息。
            直接输出描述正文，不要寒暄、不要加多余前缀。""";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public String getAllowedMimeTypes() { return allowedMimeTypes; }
    public void setAllowedMimeTypes(String allowedMimeTypes) { this.allowedMimeTypes = allowedMimeTypes; }
    public int getCaptionCacheSize() { return captionCacheSize; }
    public void setCaptionCacheSize(int captionCacheSize) { this.captionCacheSize = captionCacheSize; }
    public String getCaptionPrompt() { return captionPrompt; }
    public void setCaptionPrompt(String captionPrompt) { this.captionPrompt = captionPrompt; }
}
