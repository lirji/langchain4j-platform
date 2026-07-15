package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.AuthzEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 app.rag.authz.mode 装配细粒度授权实现（依赖 SDK 自动装配的 {@link AuthzEngine} bean）：
 * <ul>
 *   <li>shadow —— {@link RealKnowledgeAuthz} SHADOW：照写关系、读路径双算记差异但不拦截。</li>
 *   <li>enforce —— {@link RealKnowledgeAuthz} ENFORCE：照写关系、读路径按 ReBAC 真过滤。</li>
 *   <li>disabled（默认/缺省）—— 不注册任何 bean，{@code DocumentService}/{@code KnowledgeQueryService}
 *       的字段保持 {@link NoopKnowledgeAuthz}，行为与接入前逐字一致。</li>
 * </ul>
 * 两个 @Bean 的 {@code havingValue} 互斥，至多装配其一。
 */
@Configuration
public class KnowledgeAuthzConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "shadow")
    public KnowledgeAuthz shadowKnowledgeAuthz(AuthzEngine authzEngine, ObjectProvider<MeterRegistry> meter) {
        return new RealKnowledgeAuthz(authzEngine, AuthzMode.SHADOW, meter.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
    public KnowledgeAuthz enforceKnowledgeAuthz(AuthzEngine authzEngine, ObjectProvider<MeterRegistry> meter) {
        return new RealKnowledgeAuthz(authzEngine, AuthzMode.ENFORCE, meter.getIfAvailable());
    }
}
