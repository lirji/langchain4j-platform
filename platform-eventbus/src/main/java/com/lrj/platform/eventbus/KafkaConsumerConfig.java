package com.lrj.platform.eventbus;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 消费侧配置：{@link ConcurrentKafkaListenerContainerFactory} + {@link DefaultErrorHandler}
 * + {@link DeadLetterPublishingRecoverer}（重试耗尽后投递到 {@code <topic>.DLT}）。
 * 供 B1b / channel-service 的 {@code @KafkaListener} 使用。
 */
@Configuration
class KafkaConsumerConfig {

    @Bean
    @ConditionalOnMissingBean
    ConsumerFactory<String, String> eventbusConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * DLT 恢复器：默认目的地解析为 {@code <原 topic>.DLT}，与
     * {@link com.lrj.platform.protocol.event.EventTopics#DLT_SUFFIX} 约定一致。
     */
    @Bean
    @ConditionalOnMissingBean
    DefaultErrorHandler eventbusErrorHandler(KafkaTemplate<String, String> eventbusKafkaTemplate,
                                             EventbusProperties properties) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(eventbusKafkaTemplate);
        EventbusProperties.Consumer consumer = properties.getConsumer();
        FixedBackOff backOff = new FixedBackOff(consumer.getRetryBackoffMs(), consumer.getRetries());
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    @ConditionalOnMissingBean(name = "eventbusKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> eventbusKafkaListenerContainerFactory(
            ConsumerFactory<String, String> eventbusConsumerFactory,
            DefaultErrorHandler eventbusErrorHandler,
            EventbusProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventbusConsumerFactory);
        factory.setCommonErrorHandler(eventbusErrorHandler);
        factory.setConcurrency(properties.getConsumer().getConcurrency());
        return factory;
    }
}
