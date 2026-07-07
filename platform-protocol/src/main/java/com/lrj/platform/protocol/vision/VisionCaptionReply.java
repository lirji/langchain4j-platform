package com.lrj.platform.protocol.vision;

/**
 * 视觉描述响应（跨服务契约）。
 *
 * @param caption 视觉模型产出的描述/转写正文
 * @param model   实际使用的视觉模型名（对应 LiteLLM model_list）
 * @param chars   {@code caption} 字符数（便于调用方快速判空 / 观测）
 */
public record VisionCaptionReply(String caption, String model, int chars) {
}
