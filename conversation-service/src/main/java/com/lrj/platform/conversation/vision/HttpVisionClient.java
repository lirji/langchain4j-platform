package com.lrj.platform.conversation.vision;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP 委托到 vision-service {@code POST /vision/caption}（instruction 非空即看图问答）。
 * 仅在 {@code app.conversation.vision.enabled=true} 时装配；RestTemplate 带租户/trace 转发拦截器。
 */
@Component
@ConditionalOnProperty(name = "app.conversation.vision.enabled", havingValue = "true")
public class HttpVisionClient implements VisionClient {

    private final RestTemplate visionRestTemplate;

    public HttpVisionClient(RestTemplate visionRestTemplate) {
        this.visionRestTemplate = visionRestTemplate;
    }

    @Override
    public VisionCaptionReply describe(VisionCaptionRequest request) {
        VisionCaptionReply reply = visionRestTemplate.postForObject("/vision/caption", request, VisionCaptionReply.class);
        return reply == null ? new VisionCaptionReply("", "", 0) : reply;
    }
}
