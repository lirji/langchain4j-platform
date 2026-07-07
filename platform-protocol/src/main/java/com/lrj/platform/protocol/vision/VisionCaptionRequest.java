package com.lrj.platform.protocol.vision;

/**
 * 视觉描述请求（跨服务契约）。图片以 base64 传输，配可选指令。
 *
 * <p>{@code instruction} 留空 → vision-service 用配置的默认 caption/OCR 指令（图像描述 + 文字转写）；
 * 非空 → 按该问题看图作答。{@code mimeType} 缺省由服务端兜底为 {@code image/png}。
 */
public record VisionCaptionRequest(String imageBase64, String mimeType, String instruction) {
}
