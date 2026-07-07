package com.lrj.platform.eventbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lrj.platform.protocol.event.EventTopics;
import com.lrj.platform.protocol.event.WorkflowTerminalMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddedKafka 端到端集成测试（{@code @Tag("kafka-it")}，默认 {@code mvn test} 不加载，
 * 仅 {@code mvn -Pkafka-it test} 运行）。验证 effective exactly-once：
 * <ul>
 *   <li><b>去重</b>：同一 eventId 重复投递 → 消费侧只处理一次；</li>
 *   <li><b>不丢</b>：消费处理瞬时失败 → 容器重投 → 最终恰好处理一次（未丢、未重复）。</li>
 * </ul>
 * 走真实 {@link KafkaEventPublisher}（同步等 broker ack）+ 真实 {@code eventbusKafkaListenerContainerFactory}
 * + {@link ProcessedEventStore}「先查 → 处理 → 成功后标记」的顺序。
 */
@Tag("kafka-it")
@SpringBootTest(
        classes = EventbusExactlyOnceKafkaTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "platform.eventbus.enabled=true",
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.consumer.group-id=eventbus-it",
                "spring.kafka.consumer.auto-offset-reset=earliest",
                "platform.eventbus.consumer.retry-backoff-ms=200",
                "platform.eventbus.consumer.retries=5"
        })
@EmbeddedKafka(partitions = 1, topics = { EventTopics.WORKFLOW_TERMINAL })
class EventbusExactlyOnceKafkaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    EventPublisher publisher;

    @Autowired
    TestConsumer consumer;

    private WorkflowTerminalMessage msg(String eventId) {
        return new WorkflowTerminalMessage(eventId, WorkflowTerminalMessage.CURRENT_SCHEMA_VERSION,
                "acme", "inst", "feishu:u1", "granted", "COMPLETED", "reply", Instant.now(), "trace");
    }

    @Test
    void deduplicatesSameEventIdOnRedelivery() throws Exception {
        String eventId = "workflow:it-dedup";
        publisher.publish(EventTopics.WORKFLOW_TERMINAL, "acme", msg(eventId));
        publisher.publish(EventTopics.WORKFLOW_TERMINAL, "acme", msg(eventId)); // 重复投递

        awaitProcessedCount(eventId, 1);
        Thread.sleep(1500); // 再等一会，确认第二条被去重、不会追加
        assertThat(consumer.countProcessed(eventId)).isEqualTo(1);
        assertThat(consumer.deduped(eventId)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void doesNotLoseOnTransientConsumerFailure() throws Exception {
        String eventId = "workflow:it-noloss";
        consumer.failNextAttempts(eventId, 1); // 首次处理抛异常 → 容器重投
        publisher.publish(EventTopics.WORKFLOW_TERMINAL, "acme", msg(eventId));

        awaitProcessedCount(eventId, 1); // 重投后最终成功处理一次
        Thread.sleep(1500);
        assertThat(consumer.countProcessed(eventId)).isEqualTo(1); // 恰好一次：未丢、未重复
    }

    private void awaitProcessedCount(String eventId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (consumer.countProcessed(eventId) >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for processed count " + expected + " of " + eventId
                + ", actual=" + consumer.countProcessed(eventId));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        // 最小（无 web）应用没有自动装配的 ObjectMapper（真实服务由 starter-web 提供）；显式给一个（带 JavaTime）。
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        TestConsumer testConsumer(ProcessedEventStore store) {
            return new TestConsumer(store);
        }
    }

    /** 复刻 channel 消费者的「先查 → 处理 → 成功后标记」顺序，用于端到端验证去重/不丢。 */
    static class TestConsumer {
        private final ProcessedEventStore store;
        private final List<String> processed = new CopyOnWriteArrayList<>();
        private final ConcurrentHashMap<String, Integer> deduped = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Integer> failRemaining = new ConcurrentHashMap<>();

        TestConsumer(ProcessedEventStore store) {
            this.store = store;
        }

        void failNextAttempts(String eventId, int n) {
            failRemaining.put(eventId, n);
        }

        int countProcessed(String eventId) {
            return (int) processed.stream().filter(eventId::equals).count();
        }

        int deduped(String eventId) {
            return deduped.getOrDefault(eventId, 0);
        }

        @KafkaListener(topics = EventTopics.WORKFLOW_TERMINAL,
                groupId = "eventbus-it",
                containerFactory = "eventbusKafkaListenerContainerFactory")
        void onMessage(String payload) throws Exception {
            WorkflowTerminalMessage m = MAPPER.readValue(payload, WorkflowTerminalMessage.class);
            String eventId = m.eventId();
            if (store.isProcessed(eventId)) {
                deduped.merge(eventId, 1, Integer::sum);
                return;
            }
            // 模拟瞬时失败：抛异常在「记录/标记」之前 → 未标记 → 容器重投
            Integer remaining = failRemaining.get(eventId);
            if (remaining != null && remaining > 0) {
                failRemaining.put(eventId, remaining - 1);
                throw new IllegalStateException("transient failure for " + eventId);
            }
            processed.add(eventId);
            store.markProcessed(eventId);
        }
    }
}
