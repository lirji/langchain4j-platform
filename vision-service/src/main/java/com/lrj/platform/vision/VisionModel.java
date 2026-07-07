package com.lrj.platform.vision;

/**
 * 视觉模型抽象：把「一张图 + 一段指令」喂给多模态 LLM，拿回文本。
 *
 * <p>刻意做成<strong>自定义接口而非直接暴露 {@code dev.langchain4j.model.chat.ChatModel} Bean</strong>——
 * LangChain4j 的 {@code @AiService} 自动发现按类型枚举 {@code ChatModel} Bean，多于 1 个就冲突。
 * 所以 {@link DefaultVisionModel} 内部持有的视觉 {@code ChatModel} 由 {@link VisionConfig} 直接构造、
 * <strong>不注册成 Bean</strong>（与单体同思路）。
 */
public interface VisionModel {

    /**
     * 看图并产出文本。
     *
     * @param image       图片原始字节（已过 {@link VisionContentGuard} 校验）
     * @param mimeType    归一化后的 MIME（如 {@code image/png}）
     * @param instruction 指令；留空 → 用配置的默认 caption/OCR 指令（描述 + 转写，带缓存）
     * @return 模型产出的描述/转写/回答正文
     */
    String caption(byte[] image, String mimeType, String instruction);

    /** 实际使用的视觉模型名（回填到响应，便于审计/观测）。 */
    String modelName();
}
