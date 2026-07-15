package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.AuthzEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 *
 * <p>额外经 {@code @EnableConfigurationProperties} 注册 {@link RagAuthzProperties}（前缀 app.rag.authz）：
 * 无条件注册（disabled 下也在），使候选/批次上限成为领域模型，非法值启动即失败。本阶段仅注册与校验，
 * 判权装配仍由下方 {@code @ConditionalOnProperty} 驱动，运行时行为不变。
 */
@Configuration
@EnableConfigurationProperties(RagAuthzProperties.class)
public class KnowledgeAuthzConfig {

    @Bean
    @ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "shadow")
    public KnowledgeAuthz shadowKnowledgeAuthz(AuthzEngine authzEngine, ObjectProvider<MeterRegistry> meter,
                                               RagAuthzProperties props) {
        return new RealKnowledgeAuthz(authzEngine, AuthzMode.SHADOW, meter.getIfAvailable(), props.getBulkSize());
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
    public KnowledgeAuthz enforceKnowledgeAuthz(AuthzEngine authzEngine, ObjectProvider<MeterRegistry> meter,
                                                RagAuthzProperties props) {
        return new RealKnowledgeAuthz(authzEngine, AuthzMode.ENFORCE, meter.getIfAvailable(), props.getBulkSize());
    }

    /**
     * 跨配置启动校验（<strong>无条件</strong>运行，含 disabled）：{@code app.rag.authz.strict-tenant-only=true}
     * 时拒绝与之矛盾的配置——冲突直接拒绝启动，不静默放行。见 {@link #validateStrictConsistency}。
     */
    @Bean
    InitializingBean ragAuthzStrictConsistencyCheck(
            RagAuthzProperties props,
            @Value("${app.rag.public.enabled:false}") boolean publicEnabled) {
        return () -> validateStrictConsistency(props, publicEnabled);
    }

    /**
     * strict-tenant-only 的跨配置一致性：
     * <ul>
     *   <li>与 {@code app.rag.public.enabled=true} 互斥——公共库 {@code __public__} 会返回<em>跨租户</em>公共正文，
     *       与"严格本租户"矛盾。</li>
     *   <li>要求 {@code mode != disabled}——严格模式必须真判权，disabled 下不过滤即形同虚设。</li>
     * </ul>
     * 违反即抛 {@link IllegalStateException}（拒绝启动）。strict-tenant-only=false 时不校验。
     */
    static void validateStrictConsistency(RagAuthzProperties props, boolean publicEnabled) {
        if (!props.isStrictTenantOnly()) {
            return;
        }
        if (publicEnabled) {
            throw new IllegalStateException(
                    "app.rag.authz.strict-tenant-only=true 与 app.rag.public.enabled=true 互斥"
                            + "（公共库 __public__ 返回跨租户公共正文，违反严格本租户）");
        }
        if (props.getMode() == AuthzMode.DISABLED) {
            throw new IllegalStateException(
                    "app.rag.authz.strict-tenant-only=true 要求 app.rag.authz.mode != disabled（严格模式需真判权）");
        }
    }
}
