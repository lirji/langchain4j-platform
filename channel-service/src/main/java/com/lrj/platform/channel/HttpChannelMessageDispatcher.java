package com.lrj.platform.channel;

import com.lrj.platform.protocol.channel.ChannelMessageRequest;
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

    public HttpChannelMessageDispatcher(RestTemplate channelRestTemplate, ChannelProperties properties) {
        this.channelRestTemplate = channelRestTemplate;
        this.properties = properties;
    }

    @Override
    public ChannelDeliveryResult dispatch(String messageId, ChannelMessageRequest request) {
        if (!properties.isOutboundEnabled()) {
            return ChannelDeliveryResult.accepted("outbound disabled");
        }
        if (!"webhook".equalsIgnoreCase(request.channel())) {
            return ChannelDeliveryResult.accepted("adapter pending: " + request.channel());
        }
        String url = deliveryUrl(request);
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

    private String deliveryUrl(ChannelMessageRequest request) {
        Object value = request.metadata().get("deliveryUrl");
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

    private HttpHeaders headers(String messageId, ChannelMessageRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.isOutboundSignatureEnabled()) {
            headers.set("X-Channel-Signature", sign(messageId, request));
        }
        return headers;
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
