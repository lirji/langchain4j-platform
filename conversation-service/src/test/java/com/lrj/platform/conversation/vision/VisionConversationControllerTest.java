package com.lrj.platform.conversation.vision;

import com.lrj.platform.protocol.vision.VisionCaptionReply;
import com.lrj.platform.protocol.vision.VisionCaptionRequest;
import com.lrj.platform.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionConversationControllerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static void setTenant() {
        TenantContext.set(new TenantContext.Tenant("acme", "alice", Set.of("chat")));
    }

    @Test
    void chatVision_enabled_forwardsImageAndQuestion() {
        VisionClient client = mock(VisionClient.class);
        when(client.enabled()).thenReturn(true);
        when(client.describe(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VisionCaptionReply("图里是一只猫", "gpt-4o-mini", 6));
        VisionConversationController controller = new VisionConversationController(client);
        setTenant();
        MockMultipartFile image = new MockMultipartFile("image", "cat.png", "image/png", new byte[]{1, 2, 3});

        Map<String, Object> res = controller.chatVision(image, "这是什么");

        assertThat(res).containsEntry("reply", "图里是一只猫").containsEntry("model", "gpt-4o-mini")
                .containsEntry("tenantId", "acme");
        ArgumentCaptor<VisionCaptionRequest> captor = ArgumentCaptor.forClass(VisionCaptionRequest.class);
        org.mockito.Mockito.verify(client).describe(captor.capture());
        assertThat(captor.getValue().instruction()).isEqualTo("这是什么");
        assertThat(captor.getValue().mimeType()).isEqualTo("image/png");
        assertThat(captor.getValue().imageBase64()).isNotBlank();
    }

    @Test
    void chatVision_disabled_returnsError() {
        VisionClient client = mock(VisionClient.class);
        when(client.enabled()).thenReturn(false);
        VisionConversationController controller = new VisionConversationController(client);
        setTenant();
        MockMultipartFile image = new MockMultipartFile("image", "x.png", "image/png", new byte[]{1});

        Map<String, Object> res = controller.chatVision(image, "q");

        assertThat((String) res.get("error")).contains("Vision chat not enabled");
    }

    @Test
    void chatVision_emptyImage_returnsError() {
        VisionClient client = mock(VisionClient.class);
        when(client.enabled()).thenReturn(true);
        VisionConversationController controller = new VisionConversationController(client);
        setTenant();
        MockMultipartFile image = new MockMultipartFile("image", "x.png", "image/png", new byte[]{});

        Map<String, Object> res = controller.chatVision(image, "q");

        assertThat((String) res.get("error")).contains("image is required");
    }
}
