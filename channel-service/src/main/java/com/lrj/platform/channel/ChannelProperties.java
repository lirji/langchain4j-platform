package com.lrj.platform.channel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.channel")
public class ChannelProperties {

    private boolean outboundEnabled = false;
    private boolean outboundSignatureEnabled = false;
    private String outboundSignatureSecret = "";
    private boolean inboundSignatureEnabled = false;
    private String inboundSignatureSecret = "";
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
