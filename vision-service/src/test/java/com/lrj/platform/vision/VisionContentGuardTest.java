package com.lrj.platform.vision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VisionContentGuardTest：验证 {@link VisionContentGuard#validate} 的入图校验与 MIME 归一化——拒绝空图、
 * 超尺寸图、白名单外的 MIME（均抛 IllegalArgumentException），缺失/非图片 MIME 归一化为 {@code image/png}，
 * 白名单匹配大小写不敏感。
 */
class VisionContentGuardTest {

    @Test
    void rejects_empty_image() {
        VisionContentGuard guard = new VisionContentGuard(1000, "image/png");
        assertThatThrownBy(() -> guard.validate(new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejects_oversized_image() {
        VisionContentGuard guard = new VisionContentGuard(4, "image/png");
        assertThatThrownBy(() -> guard.validate("toolong".getBytes(), "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejects_disallowed_mime() {
        VisionContentGuard guard = new VisionContentGuard(0, "image/png,image/jpeg");
        assertThatThrownBy(() -> guard.validate("x".getBytes(), "image/webp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported image type");
    }

    @Test
    void normalizes_missing_or_non_image_mime_to_png() {
        VisionContentGuard guard = new VisionContentGuard(0, ""); // 空白名单 = 不限类型
        assertThat(guard.validate("x".getBytes(), null)).isEqualTo("image/png");
        assertThat(guard.validate("x".getBytes(), "application/octet-stream")).isEqualTo("image/png");
    }

    @Test
    void accepts_allowed_mime_case_insensitively() {
        VisionContentGuard guard = new VisionContentGuard(0, "image/png,image/jpeg");
        assertThat(guard.validate("x".getBytes(), "IMAGE/JPEG")).isEqualTo("image/jpeg");
    }
}
