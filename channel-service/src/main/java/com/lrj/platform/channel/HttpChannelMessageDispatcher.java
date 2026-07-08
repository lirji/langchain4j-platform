package com.lrj.platform.channel;

import com.lrj.platform.channel.feishu.HttpFeishuReplyClient;
import com.lrj.platform.protocol.channel.ChannelMessageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@Component
public class HttpChannelMessageDispatcher implements ChannelMessageDispatcher {

    private final RestTemplate channelRestTemplate;
    private final ChannelProperties properties;
    /** 飞书应用 API 直发客户端；仅 app.channel.feishu.enabled=true 时存在（工作流终态回推原用户用）。软依赖，可缺。 */
    private final ObjectProvider<HttpFeishuReplyClient> feishuReplyClient;

    public HttpChannelMessageDispatcher(RestTemplate channelRestTemplate, ChannelProperties properties,
                                        ObjectProvider<HttpFeishuReplyClient> feishuReplyClient) {
        this.channelRestTemplate = channelRestTemplate;
        this.properties = properties;
        this.feishuReplyClient = feishuReplyClient;
    }

    @Override
    public ChannelDeliveryResult dispatch(String messageId, ChannelMessageRequest request) {
        if (!properties.isOutboundEnabled()) {
            return ChannelDeliveryResult.accepted("outbound disabled");
        }
        if ("webhook".equalsIgnoreCase(request.channel())) {
            return dispatchWebhook(messageId, request);
        }
        if ("feishu".equalsIgnoreCase(request.channel())) {
            return dispatchFeishu(messageId, request);
        }
        if ("voice".equalsIgnoreCase(request.channel())) {
            return dispatchVoice(messageId, request);
        }
        return ChannelDeliveryResult.accepted("adapter pending: " + request.channel());
    }

    private ChannelDeliveryResult dispatchWebhook(String messageId, ChannelMessageRequest request) {
        String url = deliveryUrl(request, "deliveryUrl");
        if (url == null || url.isBlank()) {
            return ChannelDeliveryResult.failed("webhook target URL is required");
        }
        if (properties.isOutboundSignatureEnabled()
                && (properties.getOutboundSignatureSecret() == null || properties.getOutboundSignatureSecret().isBlank())) {
            return ChannelDeliveryResult.failed("outbound signature secret is required");
        }
        try {
            channelRestTemplate.postForEntity(url, new HttpEntity<>(payload(messageId, request), headers(messageId, request)), Void.class);
            return ChannelDeliveryResult.sent("webhook delivered");
        } catch (RestClientException ex) {
            return ChannelDeliveryResult.failed(ex.getMessage());
        }
    }

    private ChannelDeliveryResult dispatchFeishu(String messageId, ChannelMessageRequest request) {
        String url = deliveryUrl(request, "webhookUrl");
        if (httpUrl(url)) {
            // 群自定义机器人 webhook（原有：target/metadata 带 http(s) 地址）。
            try {
                channelRestTemplate.postForEntity(url, new HttpEntity<>(feishuPayload(request), feishuHeaders()), Void.class);
                return ChannelDeliveryResult.sent("feishu delivered");
            } catch (RestClientException ex) {
                return ChannelDeliveryResult.failed(ex.getMessage());
            }
        }
        // 无群 webhook：走飞书应用 API 直发给用户 open_id。工作流终态回推原发起人即走此路——
        // chatId=feishu:<open_id> 已由 WorkflowTerminalKafkaListener.toCallback 解析为 target=open_id。
        HttpFeishuReplyClient replyClient = feishuReplyClient == null ? null : feishuReplyClient.getIfAvailable();
        String openId = request.target();
        if (replyClient != null && openId != null && !openId.isBlank()) {
            replyClient.replyText(openId.trim(), request.message());
            return ChannelDeliveryResult.sent("feishu message dispatched to open_id");
        }
        return ChannelDeliveryResult.failed("feishu delivery requires webhookUrl or feishu app credentials");
    }

    private ChannelDeliveryResult dispatchVoice(String messageId, ChannelMessageRequest request) {
        String url = voiceProviderUrl(request);
        if (!httpUrl(url)) {
            return ChannelDeliveryResult.failed("voice provider URL is required");
        }
        try {
            channelRestTemplate.postForEntity(url, new HttpEntity<>(voicePayload(messageId, request), jsonHeaders()), Void.class);
            return ChannelDeliveryResult.sent("voice delivered");
        } catch (RestClientException ex) {
            return ChannelDeliveryResult.failed(ex.getMessage());
        }
    }

    private String deliveryUrl(ChannelMessageRequest request, String metadataKey) {
        Object value = request.metadata().get(metadataKey);
        if (value instanceof String deliveryUrl && !deliveryUrl.isBlank()) {
            return deliveryUrl;
        }
        return request.target();
    }

    private Map<String, Object> payload(String messageId, ChannelMessageRequest request) {
        return Map.of(
                "messageId", messageId,
                "channel", request.channel(),
                "target", request.target(),
                "message", request.message(),
                "metadata", request.metadata());
    }

    private Map<String, Object> feishuPayload(ChannelMessageRequest request) {
        return Map.of(
                "msg_type", "text",
                "content", Map.of("text", request.message()));
    }

    private Map<String, Object> voicePayload(String messageId, ChannelMessageRequest request) {
        return Map.of(
                "messageId", messageId,
                "target", request.target(),
                "text", request.message(),
                "metadata", request.metadata());
    }

    private HttpHeaders headers(String messageId, ChannelMessageRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.isOutboundSignatureEnabled()) {
            headers.set("X-Channel-Signature", sign(messageId, request));
        }
        return headers;
    }

    private HttpHeaders feishuHeaders() {
        return jsonHeaders();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String voiceProviderUrl(ChannelMessageRequest request) {
        Object value = request.metadata().get("providerUrl");
        if (value instanceof String providerUrl && !providerUrl.isBlank()) {
            return providerUrl;
        }
        return properties.getVoiceProviderUrl();
    }

    private String sign(String messageId, ChannelMessageRequest request) {
        return "sha256=" + hmacSha256(canonical(messageId, request), properties.getOutboundSignatureSecret());
    }

    private static String canonical(String messageId, ChannelMessageRequest request) {
        return value(messageId) + "|" + value(request.channel()) + "|" + value(request.target()) + "|" + value(request.message());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean httpUrl(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private static String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to calculate channel signature", ex);
        }
    }
}
