package com.lrj.platform.protocol.knowledge;

/**
 * 知识库运行时共享状态视图（跨服务 DTO），供 {@code GET /rag/config} 返回，前端据此决定是否展示
 * "共享知识库" tab / 共享图片入口。只读、非敏感。
 *
 * @param contractVersion    合同版本，前端能力协商用（当前 = 1）
 * @param publicEnabled       共享库读并入是否开启（对应 {@code app.rag.public.enabled}）
 * @param sharedImagesSupported 共享库是否支持图片入库（当前恒 false —— 共享图片暂不支持）
 */
public record KnowledgeRuntimeView(int contractVersion,
                                   boolean publicEnabled,
                                   boolean sharedImagesSupported) {
}
