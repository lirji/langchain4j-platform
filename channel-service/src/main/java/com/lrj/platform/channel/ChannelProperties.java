package com.lrj.platform.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * channel-service 的配置绑定（前缀 {@code app.channel}）：出站总开关与出站签名密钥、入站签名开关与密钥、
 * 语音 provider 地址、渠道事件发布开关与 topic，以及出站 RestTemplate 的连接/读取超时。
 * 默认全部保守关闭，由 {@link HttpChannelMessageDispatcher}、{@link ChannelSignatureVerifier}、
 * {@link KafkaChannelEventPublisher} 等消费。
 */
@ConfigurationProperties(prefix = "app.channel")
public class ChannelProperties {

    private boolean outboundEnabled = false;
    private boolean outboundSignatureEnabled = false;
    private String outboundSignatureSecret = "";
    private boolean inboundSignatureEnabled = false;
    private String inboundSignatureSecret = "";
    private String voiceProviderUrl = "";
    private boolean eventsEnabled = false;
    private String eventsTopic = "platform.channel.events";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(10);

    public boolean isOutboundEnabled() {
        return outboundEnabled;
    }

    public void setOutboundEnabled(boolean outboundEnabled) {
        this.outboundEnabled = outboundEnabled;
    }

    public boolean isOutboundSignatureEnabled() {
        return outboundSignatureEnabled;
    }

    public void setOutboundSignatureEnabled(boolean outboundSignatureEnabled) {
        this.outboundSignatureEnabled = outboundSignatureEnabled;
    }

    public String getOutboundSignatureSecret() {
        return outboundSignatureSecret;
    }

    public void setOutboundSignatureSecret(String outboundSignatureSecret) {
        this.outboundSignatureSecret = outboundSignatureSecret;
    }

    public boolean isInboundSignatureEnabled() {
        return inboundSignatureEnabled;
    }

    public void setInboundSignatureEnabled(boolean inboundSignatureEnabled) {
        this.inboundSignatureEnabled = inboundSignatureEnabled;
    }

    public String getInboundSignatureSecret() {
        return inboundSignatureSecret;
    }

    public void setInboundSignatureSecret(String inboundSignatureSecret) {
        this.inboundSignatureSecret = inboundSignatureSecret;
    }

    public String getVoiceProviderUrl() {
        return voiceProviderUrl;
    }

    public void setVoiceProviderUrl(String voiceProviderUrl) {
        this.voiceProviderUrl = voiceProviderUrl;
    }

    public boolean isEventsEnabled() {
        return eventsEnabled;
    }

    public void setEventsEnabled(boolean eventsEnabled) {
        this.eventsEnabled = eventsEnabled;
    }

    public String getEventsTopic() {
        return eventsTopic;
    }

    public void setEventsTopic(String eventsTopic) {
        this.eventsTopic = eventsTopic;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
