package com.lrj.platform.eventbus;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 幂等生产者配置（enable.idempotence=true, acks=all, max.in.flight<=5）。
 * <p>为 B1b 的端到端 exactly-once 预留事务骨架：当
 * {@code platform.eventbus.producer.transactional-id-prefix} 非空时，生产者工厂带上 transactional-id 前缀，
 * 并暴露 {@link KafkaTransactionManager}（供 ChainedTransactionManager 串接）。默认前缀为空 = 仅幂等、不开事务。
 */
@Configuration
class KafkaProducerConfig {

    @Bean
    @ConditionalOnMissingBean
    ProducerFactory<String, String> eventbusProducerFactory(KafkaProperties kafkaProperties,
                                                            EventbusProperties eventbusProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        EventbusProperties.Producer producer = eventbusProperties.getProducer();
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, producer.isIdempotence());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, Math.min(5, producer.getMaxInFlight()));

        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        String txPrefix = producer.getTransactionalIdPrefix();
        if (StringUtils.hasText(txPrefix)) {
            // B1b：开启事务性生产者。本步默认前缀为空，不进入此分支。
            factory.setTransactionIdPrefix(txPrefix);
        }
        return factory;
    }

    @Bean
    @ConditionalOnMissingBean
    KafkaTemplate<String, String> eventbusKafkaTemplate(ProducerFactory<String, String> eventbusProducerFactory) {
        return new KafkaTemplate<>(eventbusProducerFactory);
    }

    /**
     * 事务管理器：仅当配置了 {@code platform.eventbus.producer.transactional-id-prefix} 时才装配
     * （B1b 端到端 exactly-once 用；本步默认不配置该属性，故不装配）。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "platform.eventbus.producer", name = "transactional-id-prefix")
    KafkaTransactionManager<String, String> eventbusKafkaTransactionManager(
            ProducerFactory<String, String> eventbusProducerFactory) {
        return new KafkaTransactionManager<>(eventbusProducerFactory);
    }
}
