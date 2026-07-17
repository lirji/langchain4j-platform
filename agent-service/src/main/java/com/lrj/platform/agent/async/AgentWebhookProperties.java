package com.lrj.platform.agent.async;

import java.time.Duration;

/**
 * {@link AgentTaskWebhookNotifier} 的配置项：是否启用、最大重试次数、重试退避间隔，
 * 以及回调 HTTP 客户端的连接/读取超时。绑定 {@code app.agent.webhook.*} 前缀。
 */
public class AgentWebhookProperties {

    private boolean enabled = true;
    private int maxAttempts = 3;
    private Duration backoff = Duration.ofMillis(250);
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBackoff() {
        return backoff;
    }

    public void setBackoff(Duration backoff) {
        this.backoff = backoff;
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
