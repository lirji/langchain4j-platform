package com.lrj.platform.knowledge.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.knowledge.KnowledgeQueryService;
import com.lrj.platform.knowledge.KnowledgeRuntimeProperties;
import com.lrj.platform.knowledge.ingest.job.IngestionJobStore;
import com.lrj.platform.knowledge.ingest.job.IngestionSubmissionService;
import com.lrj.platform.knowledge.lifecycle.DocumentRegistry;
import com.lrj.platform.security.InternalSecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/** 治理元数据复用入库数据库；query/worker角色不装配运营写接口的依赖。 */
@Configuration
@ConditionalOnExpression("'${app.rag.ingestion.store:memory}' == 'jdbc' && "
        + "('${app.rag.runtime.role:combined}' == 'combined' || '${app.rag.runtime.role:combined}' == 'ingest-api')")
public class KnowledgeOperationsConfig {
    @Bean
    JdbcKnowledgeOperationsStore operationsStore(@Qualifier("ingestionJobDataSource") DataSource source, ObjectMapper mapper) {
        return new JdbcKnowledgeOperationsStore(source, mapper);
    }
    @Bean
    KnowledgeOperationsService knowledgeOperationsService(JdbcKnowledgeOperationsStore store) {
        return new KnowledgeOperationsService(store, Clock.systemUTC());
    }
    @Bean
    KnowledgePublicationService knowledgePublicationService(KnowledgeOperationsService operations,
            JdbcKnowledgeOperationsStore store, IngestionSubmissionService submissions, IngestionJobStore jobs, DocumentRegistry registry) {
        return new KnowledgePublicationService(operations, store, submissions, jobs, registry, Clock.systemUTC());
    }
    /** 评测与运营使用同一受治理数据源；query/worker角色不持有写库能力。 */
    @Bean
    com.lrj.platform.knowledge.quality.QualityService qualityService(@Qualifier("ingestionJobDataSource") DataSource source,
            ObjectMapper mapper, JdbcKnowledgeOperationsStore operations, KnowledgeRetrievalProbe probe) {
        return new com.lrj.platform.knowledge.quality.QualityService(
                new com.lrj.platform.knowledge.quality.QualityStore(source, mapper), operations, probe, Clock.systemUTC());
    }

    /** HTTP探针只转发本次认证请求的原令牌；不保存凭证，也不在下游签发新JWT。 */
    @Bean
    KnowledgeRetrievalProbe knowledgeProbe(KnowledgeRuntimeProperties runtime, ObjectProvider<KnowledgeQueryService> local,
            RestTemplateBuilder builder, InternalSecurityProperties security,
            @Value("${app.rag.operations.query-base-url:}") String queryUrl) {
        if (!queryUrl.isBlank()) {
            java.net.URI uri = java.net.URI.create(queryUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("operations query URL must be an HTTP(S) service URL without embedded credentials");
            return new KnowledgeRetrievalProbe(null, builder.rootUri(queryUrl)
                    .setConnectTimeout(Duration.ofSeconds(1)).setReadTimeout(Duration.ofSeconds(3)).build(), security.getInternalHeader());
        }
        return new KnowledgeRetrievalProbe(runtime.getRole() == KnowledgeRuntimeProperties.Role.COMBINED ? local.getIfAvailable() : null,
                null, security.getInternalHeader());
    }
}
