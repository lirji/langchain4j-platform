package com.lrj.platform.vision;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 图片入口守卫：大小 + MIME 类型。移植自单体 {@code ai/vision/VisionContentGuard} 的字节/类型闸门
 * （注入/PII 文本闸门依赖尚未迁移的 guardrail 模块，本轮不移植）。
 *
 * <p>越限一律抛 {@link IllegalArgumentException} → controller 翻 400，绝不静默放行超大/异类文件
 * 进入昂贵的视觉调用。
 */
public class VisionContentGuard {

    private static final String DEFAULT_MIME = "image/png";

    private final long maxImageBytes;
    /** 允许的 MIME 白名单（小写）。空 = 不限具体类型，仅要求 {@code image/} 前缀。 */
    private final Set<String> allowedMimeTypes;

    public VisionContentGuard(long maxImageBytes, String allowedMimeTypesCsv) {
        this.maxImageBytes = maxImageBytes;
        this.allowedMimeTypes = parse(allowedMimeTypesCsv);
    }

    /**
     * 校验图片字节与 MIME，返回归一化后的 MIME（缺失/非 image 前缀兜底为 image/png）。
     *
     * @throws IllegalArgumentException 图片为空、超限，或 MIME 不在白名单
     */
    public String validate(byte[] image, String mimeType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        if (maxImageBytes > 0 && image.length > maxImageBytes) {
            throw new IllegalArgumentException(
                    "image too large: " + image.length + " > " + maxImageBytes + " bytes");
        }
        String mime = normalize(mimeType);
        if (!allowedMimeTypes.isEmpty() && !allowedMimeTypes.contains(mime)) {
            throw new IllegalArgumentException(
                    "unsupported image type: " + mime + " (allowed: " + allowedMimeTypes + ")");
        }
        return mime;
    }

    private static String normalize(String mimeType) {
        if (mimeType == null) {
            return DEFAULT_MIME;
        }
        String m = mimeType.trim().toLowerCase();
        return m.startsWith("image/") ? m : DEFAULT_MIME;
    }

    private static Set<String> parse(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            Arrays.stream(csv.split(","))
                    .map(s -> s.trim().toLowerCase())
                    .filter(s -> !s.isEmpty())
                    .forEach(set::add);
        }
        return set;
    }
}
