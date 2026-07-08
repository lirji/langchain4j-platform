package com.lrj.platform.conversation.vision;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;

/**
 * conversation → vision-service 的视觉描述/看图问答客户端。视觉能力本身在 vision-service（已迁移），
 * 这里只做薄委托。默认 {@link NoopVisionClient}（未启用）。
 */
public interface VisionClient {

    VisionCaptionReply describe(VisionCaptionRequest request);

    /** 是否已启用（默认 true；Noop 覆盖为 false，供 controller 走禁用分支）。 */
    default boolean enabled() {
        return true;
    }
}
