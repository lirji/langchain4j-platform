package com.lrj.platform.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FeishuInboundControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    private FeishuProperties props(String verificationToken) {
        FeishuProperties p = new FeishuProperties();
        p.setEnabled(true);
        p.setTenantId("acme");
        p.setVerificationToken(verificationToken);
        return p;
    }

    private FeishuInboundController controller(FeishuProperties p, FeishuMessageBridge bridge) {
        return new FeishuInboundController(p, new FeishuEventCrypto(""), bridge, json);
    }

    @Test
    void urlVerification_returnsChallenge() {
        FeishuMessageBridge bridge = mock(FeishuMessageBridge.class);
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c-abc\",\"token\":\"vt\"}";

        ResponseEntity<?> resp = controller(props("vt"), bridge).onEvent(body, null, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getBody()).get("challenge")).isEqualTo("c-abc");
        verify(bridge, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidVerificationToken_rejected() {
        FeishuMessageBridge bridge = mock(FeishuMessageBridge.class);
        String body = "{\"type\":\"url_verification\",\"challenge\":\"c\",\"token\":\"WRONG\"}";

        ResponseEntity<?> resp = controller(props("vt"), bridge).onEvent(body, null, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void messageReceiveEvent_parsedAndHandedToBridge() {
        FeishuMessageBridge bridge = mock(FeishuMessageBridge.class);
        String body = """
                {"header":{"event_type":"im.message.receive_v1","token":"vt"},
                 "event":{
                   "sender":{"sender_id":{"open_id":"ou_1"}},
                   "message":{"message_id":"om_9","chat_id":"oc_2","message_type":"text","content":"{\\"text\\":\\"你好\\"}"}
                 }}""";

        ResponseEntity<?> resp = controller(props("vt"), bridge).onEvent(body, null, null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<FeishuInboundMessage> cap = ArgumentCaptor.forClass(FeishuInboundMessage.class);
        verify(bridge).handle(cap.capture());
        FeishuInboundMessage m = cap.getValue();
        assertThat(m.messageId()).isEqualTo("om_9");
        assertThat(m.openId()).isEqualTo("ou_1");
        assertThat(m.chatId()).isEqualTo("oc_2");
        assertThat(m.text()).isEqualTo("你好");
    }

    @Test
    void nonTextMessage_ignored() {
        FeishuMessageBridge bridge = mock(FeishuMessageBridge.class);
        String body = """
                {"header":{"event_type":"im.message.receive_v1","token":"vt"},
                 "event":{"message":{"message_id":"om_1","message_type":"image","content":"{}"}}}""";

        controller(props("vt"), bridge).onEvent(body, null, null, null);

        verify(bridge, never()).handle(org.mockito.ArgumentMatchers.any());
    }
}
