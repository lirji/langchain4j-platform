package com.lrj.platform.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 事务链装配的开关断言（铁律：默认关闭时不装配任何 Kafka 事务 bean）。
 * 不起真 Kafka，只用 mock 事务管理器验证 {@code @ConditionalOnProperty} 的存在/缺失语义。
 */
class WorkflowEventbusTransactionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(StubTxManagers.class, WorkflowEventbusTransactionConfig.class);

    @Test
    void chainedManagerAbsentByDefault_whenWorkflowDisabled() {
        runner.run(context -> assertThat(context).doesNotHaveBean("workflowKafkaChainedTransactionManager"));
    }

    @Test
    void chainedManagerAbsent_whenNoTransactionalIdPrefix() {
        runner.withPropertyValues("app.workflow.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean("workflowKafkaChainedTransactionManager"));
    }

    @Test
    void chainedManagerPresent_whenEnabledAndPrefixSet() {
        runner.withPropertyValues(
                        "app.workflow.enabled=true",
                        "platform.eventbus.producer.transactional-id-prefix=wf-tx-")
                .run(context -> assertThat(context).hasBean("workflowKafkaChainedTransactionManager"));
    }

    @Configuration
    static class StubTxManagers {
        @Bean
        @SuppressWarnings("unchecked")
        KafkaTransactionManager<String, String> eventbusKafkaTransactionManager() {
            return mock(KafkaTransactionManager.class);
        }

        @Bean
        PlatformTransactionManager workflowTransactionManager() {
            return mock(PlatformTransactionManager.class);
        }
    }
}
