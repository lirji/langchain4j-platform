package com.lrj.platform.agent.client;

/**
 * agent 侧调用独立 vision-service 的客户端抽象。把截图字节 + 指令提交给 {@code POST /vision/caption}，
 * 拿回视觉模型产出的文本。做成接口便于单测 mock（{@code browser_see} 动作不直接依赖 HTTP）。
 */
public interface VisionClient {

    /**
     * @param image       截图字节
     * @param mimeType    MIME（如 {@code image/png}）
     * @param instruction 想问的问题；留空 → vision-service 用默认 caption/OCR 指令整体描述
     * @return 视觉模型产出的描述/回答正文
     */
    String caption(byte[] image, String mimeType, String instruction);
}
