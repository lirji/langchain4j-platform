package com.lrj.platform.asynctask;

import java.time.Duration;

/**
 * 异步任务 webhook 投递配置项（前缀 {@code app.async-task.webhook}）：开关、终态投递传输方式（http/kafka）、
 * 重试次数/退避、连接与读超时、outbox 轮询间隔/批量、已投递记录保留时长与 claim 租约 TTL。由
 * {@link AsyncTaskWebConfig} 绑定，被 webhook notifier / outbox / dispatcher 共同读取。
 */
public class AsyncTaskWebhookProperties {

    private boolean enabled = true;
    /**
     * 终态投递传输方式（B1b）：{@code http}（默认，现有 webhook outbox/notifier 直投）
     * | {@code kafka}（改为发布 {@code platform.asynctask.lifecycle} 事件，由 channel-service 消费回推）。
     * 设为 {@code kafka} 时既有 HTTP 通道自动让位（见 AsyncTaskWebhookNotifier / OutboxEnqueuer 的传输守卫）。
     */
    private String transport = "http";
    private int maxAttempts = 3;
    private Duration backoff = Duration.ofMillis(250);
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(3);
    private long pollIntervalMs = 30_000;
    private int batchSize = 50;
    private Duration deliveredRetention = Duration.ofDays(7);
    private Duration claimTtl = Duration.ofMinutes(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    /** true = 终态走 Kafka 事件（B1b），HTTP 通道让位。 */
    public boolean isKafkaTransport() {
        return "kafka".equalsIgnoreCase(transport);
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

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getDeliveredRetention() {
        return deliveredRetention;
    }

    public void setDeliveredRetention(Duration deliveredRetention) {
        this.deliveredRetention = deliveredRetention;
    }

    public Duration getClaimTtl() {
        return claimTtl;
    }

    public void setClaimTtl(Duration claimTtl) {
        this.claimTtl = claimTtl;
    }
}
