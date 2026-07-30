package com.lrj.platform.conversation.shadow;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/** 低基数 shadow 指标；不记录 prompt、回复、租户或用户内容。 */
final class ConversationShadowMetrics {

    private final MeterRegistry registry;

    ConversationShadowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String outcome, Duration duration) {
        if (registry == null) {
            return;
        }
        registry.counter("conversation.shadow.requests", "outcome", outcome).increment();
        registry.timer("conversation.shadow.latency", "outcome", outcome).record(duration);
    }

    void comparison(boolean exactMatch) {
        if (registry != null) {
            registry.counter(
                    "conversation.shadow.comparisons",
                    "exact_match",
                    Boolean.toString(exactMatch)).increment();
        }
    }
}
