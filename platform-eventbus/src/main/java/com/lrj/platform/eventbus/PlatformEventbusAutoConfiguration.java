package com.lrj.platform.eventbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import javax.sql.DataSource;

/**
 * platform-eventbus 自动装配。铁律：默认关闭（{@code platform.eventbus.enabled=false}）时
 * 只有 {@link NoopEventPublisher} + {@link InMemoryProcessedEventStore}，全链零 Kafka 依赖。
 * <p>装配规则：
 * <ul>
 *   <li>EventPublisher：{@code enabled=true} 且 classpath 有 Kafka → {@link KafkaEventPublisher}；否则 {@link NoopEventPublisher}。</li>
 *   <li>ProcessedEventStore：{@code processed-event-store=jdbc} → {@link JdbcProcessedEventStore}；否则内存。</li>
 *   <li>Kafka 生产/消费基础设施（幂等生产者 / DLT 容器工厂）仅在 {@code enabled=true} 且有 Kafka 时装配。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(EventbusProperties.class)
public class PlatformEventbusAutoConfiguration {

    // ---------------- EventPublisher（两实现同类内声明，kafka 在前保证 OnMissingBean 顺序可靠） ----------------

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty(prefix = "platform.eventbus", name = "enabled", havingValue = "true")
    public EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> eventbusKafkaTemplate,
                                              ObjectMapper objectMapper,
                                              EventbusProperties properties) {
        return new KafkaEventPublisher(eventbusKafkaTemplate, objectMapper,
                properties.getProducer().getSendTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher noopEventPublisher() {
        return new NoopEventPublisher();
    }

    // ---------------- ProcessedEventStore（消费幂等去重） ----------------

    @Bean
    @ConditionalOnMissingBean(ProcessedEventStore.class)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "platform.eventbus", name = "processed-event-store", havingValue = "jdbc")
    public ProcessedEventStore jdbcProcessedEventStore(DataSource dataSource) {
        return new JdbcProcessedEventStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(ProcessedEventStore.class)
    public ProcessedEventStore inMemoryProcessedEventStore() {
        return new InMemoryProcessedEventStore();
    }

    // ---------------- Kafka 基础设施：仅 enabled=true 且有 Kafka 时加载 ----------------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty(prefix = "platform.eventbus", name = "enabled", havingValue = "true")
    @Import({KafkaProducerConfig.class, KafkaConsumerConfig.class})
    static class KafkaEventbusInfrastructure {
    }
}
