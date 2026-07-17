package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpChannelMessageDispatcherTest：验证 {@link HttpChannelMessageDispatcher} 各渠道投递行为——
 * 出站关闭时保持 ACCEPTED、webhook/语音/飞书正确 POST 载荷、语音 provider 缺失或元数据覆盖、
 * 飞书群 webhook 与经应用 API 直发 open_id（工作流终态回推）两条路径、以及出站 HMAC 签名与密钥缺失的失败分支。
 */
class HttpChannelMessageDispatcherTest {

    @Test
    void keepsMessagesAcceptedWhenOutboundDisabled() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(false);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties, null);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.detail()).isEqualTo("outbound disabled");
    }

    @Test
    void postsWebhookWhenOutboundEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties, null);

        server.expect(once(), requestTo("http://callback.local/messages"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "messageId": "message-1",
                          "channel": "webhook",
                          "target": "http://callback.local/messages",
                          "message": "hello"
                        }
                        """))
                .andRespond(withSuccess());

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("SENT");
        server.verify();
    }

    @Test
    void failsVoiceWhenProviderUrlMissing() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties, null);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("voice", "user-1", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("voice provider URL is required");
    }

    @Test
    void postsVoiceTextMessageWhenOutboundEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        properties.setVoiceProviderUrl("http://voice.local/calls");
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties, null);

        server.expect(once(), requestTo("http://voice.local/calls"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "messageId": "message-1",
                          "target": "user-1",
                          "text": "hello"
                        }
                        """))
                .andRespond(withSuccess());

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("voice", "user-1", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.detail()).isEqualTo("voice delivered");
        server.verify();
    }

    @Test
    void letsVoiceMetadataOverrideProviderUrl() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        properties.setVoiceProviderUrl("http://voice.local/default");
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties, null);

        server.expect(once(), requestTo("http://voice.local/override"))
                .andRespond(withSuccess());

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest(
                "voice",
                "user-1",
                "hello",
                Map.of("providerUrl", "http://voice.local/override")));

        assertThat(result.status()).isEqualTo("SENT");
        server.verify();
    }

    @Test
    void postsFeishuTextMessageWhenOutboundEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties, null);

        server.expect(once(), requestTo("http://feishu.local/webhook"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "msg_type": "text",
                          "content": {"text": "hello"}
                        }
                        """))
                .andRespond(withSuccess());

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest(
                "feishu",
                "chat-1",
                "hello",
                Map.of("webhookUrl", "http://feishu.local/webhook")));

        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.detail()).isEqualTo("feishu delivered");
        server.verify();
    }

    @Test
    void failsFeishuWhenNoWebhookAndNoReplyClient() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        // 无群 webhook 且无飞书应用直发客户端（feishu 未启用）→ 无法投递
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties, null);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("feishu", "chat-1", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("feishu delivery requires webhookUrl or feishu app credentials");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchesFeishuTerminalReplyToOpenIdViaAppApi() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        // 工作流终态回推：chatId=feishu:ou_123 → target=ou_123，无 webhookUrl → 走应用 API 直发。
        com.lrj.platform.channel.feishu.HttpFeishuReplyClient replyClient =
                org.mockito.Mockito.mock(com.lrj.platform.channel.feishu.HttpFeishuReplyClient.class);
        org.springframework.beans.factory.ObjectProvider<com.lrj.platform.channel.feishu.HttpFeishuReplyClient> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(replyClient);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties, provider);

        var result = dispatcher.dispatch("message-1",
                new ChannelMessageRequest("feishu", "ou_123", "您的退款已通过审核。", Map.of()));

        assertThat(result.status()).isEqualTo("SENT");
        org.mockito.Mockito.verify(replyClient).replyText("ou_123", "您的退款已通过审核。");
    }

    @Test
    void signsWebhookWhenOutboundSignatureEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        properties.setOutboundSignatureEnabled(true);
        properties.setOutboundSignatureSecret("secret");
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties, null);

        server.expect(once(), requestTo("http://callback.local/messages"))
                .andExpect(header("X-Channel-Signature", "sha256=3fb17502c45341517023fca56038fdcba4b9592f1f1bc0b85ac51ebee3cf66be"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess());

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("SENT");
        server.verify();
    }

    @Test
    void failsSignedWebhookWhenSecretMissing() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        properties.setOutboundSignatureEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties, null);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("outbound signature secret is required");
    }
}
