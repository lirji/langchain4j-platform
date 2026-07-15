package com.lrj.platform.knowledge.authz;

import com.lrj.authz.protocol.SubjectRef;
import com.lrj.authz.sdk.SubjectResolver;
import com.lrj.platform.security.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * enforce 模式下装配 @CheckAccess 声明式判权所需的 {@link SubjectResolver}（从 {@link TenantContext} 取当前用户）。
 *
 * <p>注册它会触发 SDK 的 {@code CheckAccessAspect} 装配（需 classpath 有 aspectjweaver，见 pom 的
 * spring-boot-starter-aop）。<strong>仅 enforce 注册</strong>：shadow 是观察期、disabled 全关，
 * 均不启用声明式硬判权端点（避免在观察期硬拦截分享操作）。
 */
@Configuration
@ConditionalOnProperty(name = "app.rag.authz.mode", havingValue = "enforce")
public class KnowledgeAccessConfig {

    @Bean
    @ConditionalOnMissingBean
    public SubjectResolver subjectResolver() {
        return () -> SubjectRef.user(TenantContext.current().userId());
    }
}
