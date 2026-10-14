package com.lrj.platform.knowledge.ingest.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.time.Clock;

/** 同步协调放在入库API角色，继续由既有独立worker处理解析和索引；默认开放管理入口，仅配置来源后装配采集组件。 */
@Configuration
@ConditionalOnExpression("${app.rag.http-sync.enabled:true} && "
        + "('${app.rag.runtime.role:combined}' == 'combined' || '${app.rag.runtime.role:combined}' == 'ingest-api')")
@EnableConfigurationProperties(KnowledgeSyncProperties.class)
public class KnowledgeSyncConfig {
    /** 自动同步必须使用持久化共享状态，禁止进程重启丢失游标或原文。 */
    @Bean
    @Conditional(SourcesConfigured.class)
    KnowledgeSyncStore knowledgeSyncStore(@Qualifier("ingestionJobDataSource") DataSource dataSource,
                                         ObjectMapper json, Environment environment) {
        if (!"jdbc".equals(environment.getProperty("app.rag.ingestion.store"))
                || !"s3".equals(environment.getProperty("app.rag.source.store"))
                || !"redis".equals(environment.getProperty("app.rag.registry.store")))
            throw new IllegalStateException("HTTP sync requires jdbc jobs, s3 sources and redis registry");
        return new KnowledgeSyncStore(dataSource, json, Clock.systemUTC());
    }
    @Bean
    @Conditional(SourcesConfigured.class)
    KnowledgeSyncService knowledgeSyncService(KnowledgeSyncStore store, ObjectMapper json,
            IngestionSubmissionService submissions, DocumentRegistry registry,
            org.springframework.beans.factory.ObjectProvider<com.lrj.platform.knowledge.authz.KnowledgeAuthz> authorization) {
        return new KnowledgeSyncService(store, new HttpKnowledgeSource(json), submissions, registry, authorization.getIfAvailable(com.lrj.platform.knowledge.authz.NoopKnowledgeAuthz::new),
                new com.lrj.platform.audit.AuditLogger(json));
    }
    @Bean
    @Conditional(SourcesConfigured.class)
    KnowledgeSyncScheduler knowledgeSyncScheduler(KnowledgeSyncProperties properties, KnowledgeSyncService service) {
        return new KnowledgeSyncScheduler(properties, service);
    }

    /** 空来源不创建采集组件；按完整列表判断，避免只检查首项字段而静默忽略错误配置。 */
    static class SourcesConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context,
                               AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment())
                    .bind("app.rag.http-sync", KnowledgeSyncProperties.class)
                    .map(properties -> !properties.sources().isEmpty()).orElse(false);
        }
    }
}
