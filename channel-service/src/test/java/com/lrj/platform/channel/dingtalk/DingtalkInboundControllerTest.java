package com.lrj.platform.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DingtalkInboundControllerTest：验证 {@link DingtalkInboundController} 处理钉钉入站事件——
 * 合法签名的文本消息被解析（去空白）并交给 {@link DingtalkMessageBridge}、非法签名返回 401 且不转交、
 * 非文本消息被忽略、以及关闭验签开关时跳过签名校验直接受理。
 */
class DingtalkInboundControllerTest {

    private static final String SECRET = "app-secret";
    private final ObjectMapper json = new ObjectMapper();

    private DingtalkProperties props(boolean verify) {
        DingtalkProperties p = new DingtalkProperties();
        p.setEnabled(true);
        p.setTenantId("acme");
        p.setAppKey("app-key");
        p.setAppSecret(SECRET);
        p.setVerifySignature(verify);
        return p;
    }

    private DingtalkInboundController controller(DingtalkProperties p, DingtalkMessageBridge bridge) {
        return new DingtalkInboundController(p, new DingtalkEventCrypto(p.getAppSecret()), bridge, json);
    }

    private static String textEvent() {
        return "{\"msgtype\":\"text\",\"msgId\":\"m_9\",\"conversationId\":\"cid_2\","
                + "\"senderStaffId\":\"staff_1\",\"senderNick\":\"客服A\",\"text\":{\"content\":\" 退款怎么审批？ \"}}";
    }

    private static String sign(String ts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal((ts + "\n" + SECRET).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void textMessage_validSignature_parsedAndHandedToBridge() throws Exception {
        DingtalkMessageBridge bridge = mock(DingtalkMessageBridge.class);
        String ts = "1700000000000";

        ResponseEntity<?> resp = controller(props(true), bridge).onEvent(textEvent(), ts, sign(ts));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<DingtalkInboundMessage> cap = ArgumentCaptor.forClass(DingtalkInboundMessage.class);
        verify(bridge).handle(cap.capture());
        DingtalkInboundMessage m = cap.getValue();
        assertThat(m.msgId()).isEqualTo("m_9");
        assertThat(m.conversationId()).isEqualTo("cid_2");
        assertThat(m.senderStaffId()).isEqualTo("staff_1");
        assertThat(m.text()).isEqualTo("退款怎么审批？"); // trimmed
    }

    @Test
    void invalidSignature_rejectedWith401() {
        DingtalkMessageBridge bridge = mock(DingtalkMessageBridge.class);

        ResponseEntity<?> resp = controller(props(true), bridge).onEvent(textEvent(), "1700000000000", "bad-sign");

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verify(bridge, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonTextMessage_ignored() throws Exception {
        DingtalkMessageBridge bridge = mock(DingtalkMessageBridge.class);
        String ts = "1700000000000";
        String body = "{\"msgtype\":\"picture\",\"msgId\":\"m_1\",\"conversationId\":\"cid_2\"}";

        controller(props(true), bridge).onEvent(body, ts, sign(ts));

        verify(bridge, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verifyDisabled_skipsSignatureCheck() {
        DingtalkMessageBridge bridge = mock(DingtalkMessageBridge.class);

        ResponseEntity<?> resp = controller(props(false), bridge).onEvent(textEvent(), null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(bridge).handle(org.mockito.ArgumentMatchers.any());
    }
}
