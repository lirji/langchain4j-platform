package com.lrj.platform.vision.controller;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import com.lrj.platform.vision.VisionContentGuard;
import com.lrj.platform.vision.VisionModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * VisionControllerTest：验证 {@code VisionController#caption} 先经 {@link VisionContentGuard} 校验/归一化 MIME、
 * 再将解码后的图片字节委托给 {@link VisionModel} 并把结果包装为 {@code VisionCaptionReply}（含 model/chars）；
 * 覆盖空 base64、null 请求、超尺寸与非法 MIME 在调用模型前即被拒绝。
 */
class VisionControllerTest {

    private final VisionModel visionModel = mock(VisionModel.class);
    private final VisionContentGuard guard = new VisionContentGuard(1_000, "image/png,image/jpeg");
    private final VisionController controller = new VisionController(visionModel, guard);

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void caption_validates_then_delegates_and_wraps_reply() {
        byte[] image = "hello".getBytes();
        when(visionModel.caption(any(), eq("image/png"), eq("看图"))).thenReturn("识别结果");
        when(visionModel.modelName()).thenReturn("gpt-4o-mini");

        VisionCaptionReply reply = controller.caption(
                new VisionCaptionRequest(b64(image), "image/png", "看图"));

        assertThat(reply.caption()).isEqualTo("识别结果");
        assertThat(reply.model()).isEqualTo("gpt-4o-mini");
        assertThat(reply.chars()).isEqualTo("识别结果".length());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(visionModel).caption(bytes.capture(), eq("image/png"), eq("看图"));
        assertThat(bytes.getValue()).isEqualTo(image); // base64 正确解码为原始截图字节
    }

    @Test
    void caption_normalizes_missing_mime_via_guard() {
        when(visionModel.caption(any(), eq("image/png"), any())).thenReturn("x");
        when(visionModel.modelName()).thenReturn("m");

        controller.caption(new VisionCaptionRequest(b64("z".getBytes()), null, "q"));

        verify(visionModel).caption(any(), eq("image/png"), eq("q"));
    }

    @Test
    void rejects_blank_base64() {
        assertThatThrownBy(() -> controller.caption(new VisionCaptionRequest("  ", "image/png", "q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imageBase64 is required");
    }

    @Test
    void rejects_null_request() {
        assertThatThrownBy(() -> controller.caption(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void guard_rejects_oversized_image_before_model_call() {
        byte[] tooBig = new byte[2_000];
        assertThatThrownBy(() -> controller.caption(
                new VisionCaptionRequest(b64(tooBig), "image/png", "q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void guard_rejects_disallowed_mime() {
        assertThatThrownBy(() -> controller.caption(
                new VisionCaptionRequest(b64("x".getBytes()), "image/webp", "q")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported image type");
    }
}
