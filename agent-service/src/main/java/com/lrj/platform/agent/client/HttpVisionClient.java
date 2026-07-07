package com.lrj.platform.agent.client;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * {@link VisionClient} 的 HTTP 实现：把截图 base64 后 POST 到 vision-service {@code /vision/caption}。
 * 走带 {@code OutboundTenantForwarder} + {@code OutboundTraceForwarder} 的 {@code visionRestTemplate}，
 * 租户/trace 随内部 JWT 透传——vision-service 的 token 计量因此按同一租户归因。
 *
 * <p>仅在 {@code app.agent.vision.enabled=true} 时装配（配合 {@code app.agent.browser.enabled} 双门控
 * {@code browser_see}）。RestClient 异常不吞——由 {@code BrowserSeeAction} 兜底成对 Agent 友好的提示。
 */
@Component
@ConditionalOnProperty(name = {"app.agent.enabled", "app.agent.vision.enabled"}, havingValue = "true")
public class HttpVisionClient implements VisionClient {

    private final RestTemplate restTemplate;

    public HttpVisionClient(@Qualifier("visionRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String caption(byte[] image, String mimeType, String instruction) {
        if (image == null || image.length == 0) {
            return "";
        }
        VisionCaptionRequest request = new VisionCaptionRequest(
                Base64.getEncoder().encodeToString(image), mimeType, instruction);
        VisionCaptionReply reply = restTemplate.postForObject("/vision/caption", request, VisionCaptionReply.class);
        return reply == null || reply.caption() == null ? "" : reply.caption();
    }
}
