package com.lrj.platform.eventbus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件总线配置。全部默认关闭 / 内存，保证 dev/test 零外部依赖。
 */
@ConfigurationProperties(prefix = "platform.eventbus")
public class EventbusProperties {

    /** 总开关。false（默认）时走 {@link NoopEventPublisher}，无任何 Kafka 依赖。 */
    private boolean enabled = false;

    /** 消费幂等去重存储：memory（默认，内存）| jdbc（跨重启）。 */
    private String processedEventStore = "memory";

    /** 生产者设置（幂等；事务开关为 B1b 预留，默认不开事务）。 */
    private final Producer producer = new Producer();

    /** 消费者设置（DLT / 并发）。 */
    private final Consumer consumer = new Consumer();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProcessedEventStore() {
        return processedEventStore;
    }

    public void setProcessedEventStore(String processedEventStore) {
        this.processedEventStore = processedEventStore;
    }

    public Producer getProducer() {
        return producer;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public static class Producer {
        /** 幂等生产者（enable.idempotence）。默认开。 */
        private boolean idempotence = true;

        /** in-flight 上限（幂等要求 <= 5）。 */
        private int maxInFlight = 5;

        /**
         * 同步发布等待 broker ack 的超时。publish() 阻塞至 broker 确认（acks=all）后才返回——
         * 供 outbox relay「确认落 broker 再 markDelivered」，避免异步 fire-and-forget 下把未确认的发送误标已投。
         * 超时/失败抛异常，由 relay 按退避重投。
         */
        private java.time.Duration sendTimeout = java.time.Duration.ofSeconds(10);

        /**
         * 事务性生产者的 transactional-id 前缀。
         * 空（默认）= 不开事务，仅幂等；B1b 端到端 exactly-once 时配非空前缀启用事务。
         */
        private String transactionalIdPrefix = "";

        public boolean isIdempotence() {
            return idempotence;
        }

        public void setIdempotence(boolean idempotence) {
            this.idempotence = idempotence;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public java.time.Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(java.time.Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public String getTransactionalIdPrefix() {
            return transactionalIdPrefix;
        }

        public void setTransactionalIdPrefix(String transactionalIdPrefix) {
            this.transactionalIdPrefix = transactionalIdPrefix;
        }
    }

    public static class Consumer {
        /** 监听容器并发度。 */
        private int concurrency = 1;

        /** 投递失败重试次数（超过后进 <topic>.DLT）。 */
        private int retries = 3;

        /** 重试间隔（毫秒）。 */
        private long retryBackoffMs = 500;

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public long getRetryBackoffMs() {
            return retryBackoffMs;
        }

        public void setRetryBackoffMs(long retryBackoffMs) {
            this.retryBackoffMs = retryBackoffMs;
        }
    }
}
