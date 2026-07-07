package com.lrj.platform.vision.controller;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import com.lrj.platform.vision.VisionContentGuard;
import com.lrj.platform.vision.VisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * 视觉端点。仅在 {@code app.vision.enabled=true} 时注册（{@link VisionModel} 也才存在）。
 *
 * <p>两种入参形态：
 * <ul>
 *   <li>{@code POST /vision/caption}（application/json）—— {@link VisionCaptionRequest}
 *       {@code {imageBase64, mimeType, instruction}}，跨服务调用（如 agent {@code browser_see}）走这条。</li>
 *   <li>{@code POST /vision/caption}（multipart/form-data）—— 表单字段 {@code file} + 可选 {@code instruction}，
 *       便于人工/工具直接上传图片文件。</li>
 * </ul>
 * {@code instruction} 留空 → 用配置的默认 caption/OCR 指令（描述 + 转写，带缓存）。
 * 租户身份沿现有过滤器链注入的 {@code TenantContext}，视觉 token 计量随之按租户归因。
 */
@RestController
@ConditionalOnProperty(name = "app.vision.enabled", havingValue = "true")
public class VisionController {

    private static final Logger log = LoggerFactory.getLogger(VisionController.class);

    private final VisionModel visionModel;
    private final VisionContentGuard guard;

    public VisionController(VisionModel visionModel, VisionContentGuard guard) {
        this.visionModel = visionModel;
        this.guard = guard;
    }

    @PostMapping(value = {"/vision/caption", "/vision/describe"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public VisionCaptionReply caption(@RequestBody(required = false) VisionCaptionRequest request) {
        if (request == null || request.imageBase64() == null || request.imageBase64().isBlank()) {
            throw new IllegalArgumentException("imageBase64 is required");
        }
        byte[] image = decode(request.imageBase64());
        return run(image, request.mimeType(), request.instruction());
    }

    @PostMapping(value = {"/vision/caption", "/vision/describe"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VisionCaptionReply captionUpload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "instruction", required = false) String instruction) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        byte[] image;
        try {
            image = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read uploaded file: " + e.getMessage(), e);
        }
        return run(image, file.getContentType(), instruction);
    }

    private VisionCaptionReply run(byte[] image, String mimeType, String instruction) {
        String mime = guard.validate(image, mimeType);
        String caption = visionModel.caption(image, mime, instruction);
        String text = caption == null ? "" : caption;
        return new VisionCaptionReply(text, visionModel.modelName(), text.length());
    }

    /** 守卫/入参校验失败 → 400（超限/异类图片、空 base64、坏 base64 等，绝不静默）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        log.warn("vision request rejected: {}", e.getMessage());
        return Map.of("error", "bad_request", "message", e.getMessage() == null ? "" : e.getMessage());
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imageBase64 is not valid base64", e);
        }
    }
}
