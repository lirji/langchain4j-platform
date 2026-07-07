package com.lrj.platform.workflow;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.transaction.ChainedTransactionManager;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * B1b：Kafka 原生事务接线。把 Flowable/JDBC 的 {@code workflowTransactionManager} 与 platform-eventbus 的
 * {@link KafkaTransactionManager}（{@code eventbusKafkaTransactionManager}）用 {@link ChainedTransactionManager}
 * 串成一个复合事务管理器，供 {@link WorkflowTerminalEventPublisher} 把「WF_OUTBOX 权威写 + Kafka 发布」
 * 绑进同一事务，达到端到端 exactly-once。
 *
 * <p><b>提交顺序（DB 先提交语义）</b>：{@link ChainedTransactionManager} 按传入顺序 begin、<em>逆序</em> commit。
 * 传入 {@code [kafka, db]} → 提交序 {@code [db, kafka]}：DB 先落地，再提交 Kafka。若 DB 提交成功而 Kafka 提交失败，
 * 结果是「DB 有记录、消息未发」——outbox 行仍在，可由既有 {@code WorkflowOutboxDispatcher}（HTTP）兜底，不会丢终态。
 *
 * <p><b>装配条件</b>：仅当 {@code app.workflow.enabled=true}（本类）且
 * {@code platform.eventbus.producer.transactional-id-prefix} 非空（该 bean）时才装配——后者正是
 * platform-eventbus 创建 {@code eventbusKafkaTransactionManager} 的同一开关。默认二者皆关，
 * 此 bean 不存在，{@link WorkflowTerminalEventPublisher} 退回无事务顺序执行（Noop 发布器）。
 */
@Configuration
@ConditionalOnClass(KafkaTransactionManager.class)
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowEventbusTransactionConfig {

    @Bean
    @ConditionalOnProperty(prefix = "platform.eventbus.producer", name = "transactional-id-prefix")
    public PlatformTransactionManager workflowKafkaChainedTransactionManager(
            KafkaTransactionManager<String, String> eventbusKafkaTransactionManager,
            @Qualifier("workflowTransactionManager") PlatformTransactionManager workflowTransactionManager) {
        // 顺序 [kafka, db] → 逆序提交 [db, kafka]，即 DB 先提交。
        return new ChainedTransactionManager(eventbusKafkaTransactionManager, workflowTransactionManager);
    }
}
