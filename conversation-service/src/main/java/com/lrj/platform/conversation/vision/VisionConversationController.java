package com.lrj.platform.conversation.vision;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import com.lrj.platform.security.TenantContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * {@code POST /chat/vision}（multipart）：看图对话——把图片 + 问题委托给 vision-service（默认关）。
 * 图片 base64 编码后经 {@link VisionClient} 转发；问题作为 vision-service 的 {@code instruction}（非空即看图问答）。
 * 未启用 → 明确禁用提示。
 */
@RestController
public class VisionConversationController {

    private final VisionClient visionClient;

    public VisionConversationController(VisionClient visionClient) {
        this.visionClient = visionClient;
    }

    @PostMapping(value = "/chat/vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> chatVision(@RequestPart("image") MultipartFile image,
                                          @RequestParam(value = "message", required = false) String message) {
        TenantContext.Tenant tenant = TenantContext.current();
        if (!visionClient.enabled()) {
            return Map.of("error", "Vision chat not enabled. Set app.conversation.vision.enabled=true.",
                    "tenantId", tenant.tenantId());
        }
        if (image == null || image.isEmpty()) {
            return Map.of("error", "image is required", "tenantId", tenant.tenantId());
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            return Map.of("error", "failed to read image: " + e.getMessage(), "tenantId", tenant.tenantId());
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        VisionCaptionRequest request = new VisionCaptionRequest(base64, image.getContentType(), message);
        VisionCaptionReply reply = visionClient.describe(request);
        return Map.of(
                "reply", reply.caption(),
                "model", reply.model() == null ? "" : reply.model(),
                "chars", reply.chars(),
                "tenantId", tenant.tenantId(),
                "userId", tenant.userId());
    }
}
