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

class HttpChannelMessageDispatcherTest {

    @Test
    void keepsMessagesAcceptedWhenOutboundDisabled() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(false);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties);

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
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties);

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
    void marksVoiceAdapterPendingWhenOutboundEnabled() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("voice", "user-1", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.detail()).contains("adapter pending");
    }

    @Test
    void postsFeishuTextMessageWhenOutboundEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties);

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
    void failsFeishuWhenWebhookUrlMissing() {
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("feishu", "chat-1", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("feishu webhook URL is required");
    }

    @Test
    void signsWebhookWhenOutboundSignatureEnabled() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        ChannelProperties properties = new ChannelProperties();
        properties.setOutboundEnabled(true);
        properties.setOutboundSignatureEnabled(true);
        properties.setOutboundSignatureSecret("secret");
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(restTemplate, properties);

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
        HttpChannelMessageDispatcher dispatcher = new HttpChannelMessageDispatcher(new RestTemplate(), properties);

        var result = dispatcher.dispatch("message-1", new ChannelMessageRequest("webhook", "http://callback.local/messages", "hello", Map.of()));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("outbound signature secret is required");
    }
}
