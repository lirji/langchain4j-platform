package com.lrj.platform.eventbus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InboundIdempotencyTest：验证入站回调幂等范式——同一 messageId 只被受理一次（抢占语义，不是先查后标记）、
 * 处理失败或线程池拒收时归还抢占使渠道重投能重来、处理成功后重投仍被去重、
 * 无 messageId 时放行（宁重复不丢），以及不同 source 的同名 id 互不干扰。
 */
class InboundIdempotencyTest {

    private static final Executor DIRECT = Runnable::run;

    @Test
    void submitsOnceThenDeduplicates() {
        InboundIdempotency idem = new InboundIdempotency(new InMemoryProcessedEventStore(), "dingtalk");
        List<String> handled = new ArrayList<>();

        assertThat(idem.submitOnce("m1", DIRECT, () -> handled.add("m1"))).isTrue();
        assertThat(idem.submitOnce("m1", DIRECT, () -> handled.add("m1"))).isFalse();

        assertThat(handled).containsExactly("m1");
    }

    @Test
    void releasesClaimWhenProcessingFails_soRedeliveryIsHandled() {
        InboundIdempotency idem = new InboundIdempotency(new InMemoryProcessedEventStore(), "feishu");
        List<String> attempts = new ArrayList<>();

        // 第一次处理抛异常：抢占必须归还，否则这条消息被永久当成已处理 = 静默丢消息
        idem.submitOnce("m1", DIRECT, () -> {
            attempts.add("first");
            throw new IllegalStateException("chat down");
        });
        // 渠道重投：应能再次进入处理
        assertThat(idem.submitOnce("m1", DIRECT, () -> attempts.add("retry"))).isTrue();

        assertThat(attempts).containsExactly("first", "retry");
    }

    @Test
    void releasesClaimWhenExecutorRejects() {
        InboundIdempotency idem = new InboundIdempotency(new InMemoryProcessedEventStore(), "dingtalk");
        Executor rejecting = task -> {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        };

        assertThatThrownBy(() -> idem.submitOnce("m1", rejecting, () -> { }))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        // 拒收同样是"没处理"，重投必须能进来
        List<String> handled = new ArrayList<>();
        assertThat(idem.submitOnce("m1", DIRECT, () -> handled.add("m1"))).isTrue();
        assertThat(handled).containsExactly("m1");
    }

    @Test
    void successfulProcessingKeepsClaim() {
        InboundIdempotency idem = new InboundIdempotency(new InMemoryProcessedEventStore(), "dingtalk");
        List<String> handled = new ArrayList<>();

        idem.submitOnce("m1", DIRECT, () -> handled.add("first"));
        idem.submitOnce("m1", DIRECT, () -> handled.add("redelivered"));

        assertThat(handled).containsExactly("first");
    }

    @Test
    void missingMessageId_isProcessedRatherThanDropped() {
        InboundIdempotency idem = new InboundIdempotency(new InMemoryProcessedEventStore(), "dingtalk");
        List<String> handled = new ArrayList<>();

        // 渠道没给可去重的 id：无法幂等，宁可重复也不能丢（丢的是用户可见的提问）
        assertThat(idem.submitOnce(null, DIRECT, () -> handled.add("a"))).isTrue();
        assertThat(idem.submitOnce("", DIRECT, () -> handled.add("b"))).isTrue();

        assertThat(handled).containsExactly("a", "b");
    }

    @Test
    void differentSourcesDoNotCollideOnSameMessageId() {
        // 钉钉 msgId 与飞书 messageId 由各自平台生成，落同一张 PROCESSED_EVENT 表时必须靠 source 前缀区分
        ProcessedEventStore shared = new InMemoryProcessedEventStore();
        InboundIdempotency dingtalk = new InboundIdempotency(shared, "dingtalk");
        InboundIdempotency feishu = new InboundIdempotency(shared, "feishu");

        assertThat(dingtalk.claim("same-id")).isTrue();
        assertThat(feishu.claim("same-id")).isTrue();
        assertThat(dingtalk.claim("same-id")).isFalse();
    }

    @Test
    void keyIsNamespacedBySource() {
        assertThat(InboundIdempotency.key("feishu", "om_1")).isEqualTo("inbound:feishu:om_1");
    }
}
