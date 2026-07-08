package com.lrj.platform.conversation.vision;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认实现：视觉对话未启用。{@code app.conversation.vision.enabled=false}（默认）时装配。
 * 不用 {@code @ConditionalOnMissingBean}（对齐 {@code NoopKnowledgeClient} 记录的装配顺序坑）。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.vision.enabled", havingValue = "false", matchIfMissing = true)
public class NoopVisionClient implements VisionClient {

    @Override
    public VisionCaptionReply describe(VisionCaptionRequest request) {
        return new VisionCaptionReply("", "", 0);
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
